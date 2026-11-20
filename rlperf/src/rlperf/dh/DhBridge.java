package rlperf.dh;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import net.optifine.shaders.Program;
import net.optifine.shaders.Shaders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogDrawMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeBufferRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderSetupEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeTextureClearEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.forge112.RenderHelper;

import rlperf.hooks.ShaderHooks;

/**
 * Lets Distant Horizons draw its LODs through an OptiFine shader pack, the way Iris does it
 * (net.irisshaders.iris.compat.dh): the LODs are drawn with the pack's own dh_terrain / dh_water
 * programs into the pack's colour buffers, with their depth kept in a separate texture that the
 * pack's later passes read as dhDepthTex.
 */
public final class DhBridge {
    private DhBridge() {}

    private static final Logger LOG = LogManager.getLogger("RLPerf");
    private static final int DEPTH_UNIT = 26, DEPTH_UNIT_OPAQUE = 27;   // texture units OptiFine leaves alone

    private static final FloatBuffer MAT4 = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MAT3 = BufferUtils.createFloatBuffer(9);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);

    // this frame's LOD projection, as the pack sees it in dhProjection
    private static final float[] projection = identity(), projectionInverse = identity(), previousProjection = identity();
    private static float nearPlane = 0.1f, farPlane = 1024f;
    private static int renderDistance = 256;

    private static int fbo, depthTexture, opaqueDepthTexture, opaqueDepthWidth, opaqueDepthHeight;
    private static int savedFbo;
    private static Program savedProgram, lodProgram;
    private static boolean overriding;
    private static final Framebuffer FRAMEBUFFER = new Framebuffer();
    private static final Map<Integer, int[]> UNIFORMS = new HashMap<Integer, int[]>();
    private static final String[] COMMON = {"dhNearPlane", "dhFarPlane", "dhRenderDistance", "dhProjection", "dhProjectionInverse",
            "dhPreviousProjection", "dhDepthTex", "dhDepthTex0", "dhDepthTex1"};
    private static final String[] LOD = {"iris_ModelViewMatrix", "iris_ProjectionMatrix", "iris_NormalMatrix", "mircoOffset",
            "clipDistance", "worldYOffset", "modelOffset"};

    private static final boolean TRACE = Boolean.getBoolean("rlperf.dhTrace");
    private static int frame, buffersThisFrame;

    /** -Drlperf.dhTrace=true: one line per bridge step, for one frame in every 150. */
    private static void trace(String step) {
        if (!TRACE || frame % 150 != 2) return;
        LOG.info("[dh-trace] " + step + " | fbo=" + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) + " program=" + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                + " active=" + (Shaders.activeProgram == null ? "null" : Shaders.activeProgram.getName()) + " overriding=" + overriding
                + " shadow=" + Shaders.isShadowPass + " glError=" + GL11.glGetError());
    }

    /** True while LODs should go through the shader pack: a pack is loaded and it has a dh_terrain program. */
    private static boolean packSupportsLods() {
        return Shaders.shaderPackLoaded && ShaderHooks.dhTerrain != null && ShaderHooks.dhTerrain.getId() != 0;
    }

    public static void init() {
        try {
            ModAccessorInjector.INSTANCE.bind(IIrisAccessor.class, new Accessor());
        } catch (Throwable t) {
            LOG.warn("Could not register the OptiFine shader accessor with Distant Horizons", t);
        }
        DhApi.events.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                // OptiFine draws its shadow map before Distant Horizons has seen a camera matrix; give it one.
                RenderHelper.setModelViewMatrixFromBuffer(fill(BufferUtils.createFloatBuffer(16), identity()));
                RenderHelper.setProjectionMatrixFromBuffer(fill(BufferUtils.createFloatBuffer(16), identity()));
                bindEvents();
                LOG.info("Distant Horizons will render through OptiFine shader packs that have dh_terrain programs");
            }
        });
    }

    /** What Distant Horizons asks a shader loader. */
    private static final class Accessor implements IIrisAccessor {
        @Override public String getModName() { return "OptiFine (RLPerf bridge)"; }
        @Override public boolean isShaderPackInUse() { return Shaders.shaderPackLoaded; }
        @Override public boolean isRenderingShadowPass() { return Shaders.isShadowPass; }
        @Override public boolean isReverseZDuringShaders() { return false; }
        @Override public int getFramebufferDepthTextureId(Object framebuffer) { return -1; }
    }

    /** Stands in for Distant Horizons' own framebuffer while a pack is drawing the LODs. */
    private static final class Framebuffer implements IDhApiFramebuffer {
        @Override public boolean overrideThisFrame() { return true; }
        @Override public void bind() { GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo); }
        @Override public void addDepthAttachment(int texture, boolean stencil) {}
        @Override public int getId() { return fbo; }
        @Override public int getStatus() { bind(); return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER); }
        @Override public void addColorAttachment(int index, int texture) {}
        @Override public void destroy() {}
        @Override public int getPriority() { return 100; }
    }

    private static void bindEvents() {
        DhApi.events.bind(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (!Shaders.isShadowPass) frame++;
                trace("beforeRender");
                // No LODs in the shadow map, and none at all under a pack that cannot light them.
                if (Shaders.isShadowPass || (Shaders.shaderPackLoaded && !packSupportsLods())) {
                    event.cancelEvent();
                    return;
                }
                overriding = packSupportsLods();
                DhApi.Delayed.renderProxy.setDeferTransparentRendering(overriding);
                if (overriding) {
                    DhApi.Delayed.configs.graphics().fog().drawMode().setValue(EDhApiFogDrawMode.FOG_DISABLED);
                    DhApi.Delayed.configs.graphics().ambientOcclusion().enabled().setValue(false);
                    DhApi.Delayed.configs.graphics().genericRendering().renderingEnabled().setValue(false);
                } else {
                    DhApi.Delayed.configs.graphics().fog().drawMode().clearValue();
                    DhApi.Delayed.configs.graphics().ambientOcclusion().enabled().clearValue();
                    DhApi.Delayed.configs.graphics().genericRendering().renderingEnabled().clearValue();
                }
            }
        });
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new DhApiBeforeDeferredRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                trace("beforeDeferredRender");
                if (Shaders.isShadowPass || !packSupportsLods()) event.cancelEvent();
            }
        });
        DhApi.events.bind(DhApiBeforeRenderSetupEvent.class, new DhApiBeforeRenderSetupEvent() {
            @Override public void beforeSetup(DhApiEventParam<DhApiRenderParam> event) {
                OverrideInjector.INSTANCE.unbind(IDhApiFramebuffer.class, FRAMEBUFFER);
                if (!overriding) return;
                savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                savedProgram = Shaders.activeProgram;
                adoptColourBuffers();
                OverrideInjector.INSTANCE.bind(IDhApiFramebuffer.class, FRAMEBUFFER);
                trace("renderSetup status=" + FRAMEBUFFER.getStatus() + " depthTex=" + depthTexture);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
            }
        });
        DhApi.events.bind(DhApiBeforeTextureClearEvent.class, new DhApiBeforeTextureClearEvent() {
            @Override public void beforeClear(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (!overriding) return;
                attachDepth();
                if (event.value.renderPass == EDhApiRenderPass.OPAQUE) {
                    // Only the LOD depth: the colour buffers already hold the pack's sky.
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                    GL11.glDepthMask(true);
                    GL11.glClearDepth(1.0);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                }
                trace("textureClear pass=" + event.value.renderPass);
                event.cancelEvent();
            }
        });
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
                if (!overriding) return;
                boolean opaque = event.value.renderPass == EDhApiRenderPass.OPAQUE;
                if (!opaque && event.value.renderPass != EDhApiRenderPass.TRANSPARENT) return;
                attachDepth();
                if (opaque) {
                    updateProjection(event.value.partialTicks);
                } else {
                    copyOpaqueDepth();
                    GL11.glDisable(GL11.GL_CULL_FACE);
                }
                Program program = !opaque && ShaderHooks.dhWater.getId() != 0 ? ShaderHooks.dhWater : ShaderHooks.dhTerrain;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                Shaders.useProgram(program);      // the pack's own uniforms and draw buffers
                lodProgram = program;
                fillLodUniforms(program.getId(), event.value.worldYOffset);
                buffersThisFrame = 0;
                trace("renderPass " + event.value.renderPass + " lodProgram=" + program.getName() + "#" + program.getId() + " near=" + nearPlane + " far=" + farPlane);
            }
        });
        DhApi.events.bind(DhApiBeforeBufferRenderEvent.class, new DhApiBeforeBufferRenderEvent() {
            @Override public void beforeRender(DhApiEventParam<EventParam> event) {
                if (!overriding || lodProgram == null) return;
                // Distant Horizons has just bound its own program for this buffer.
                GL20.glUseProgram(lodProgram.getId());
                int at = locations(lodProgram.getId(), LOD)[6];
                if (at >= 0) GL20.glUniform3f(at, event.value.modelPos.x, event.value.modelPos.y, event.value.modelPos.z);
                if (buffersThisFrame++ == 0) trace("first buffer modelPos=" + event.value.modelPos + " modelOffsetAt=" + at);
            }
        });
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
                if (overriding) event.cancelEvent();    // the pack does its own fog
            }
        });
        DhApi.events.bind(DhApiBeforeGenericObjectRenderEvent.class, new DhApiBeforeGenericObjectRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
                if (overriding) event.cancelEvent();    // clouds and beacons have no pack program here
            }
        });
        DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (Shaders.shaderPackLoaded) {
                    OverrideInjector.INSTANCE.unbind(IDhApiFramebuffer.class, FRAMEBUFFER);
                    event.cancelEvent();                // nothing to copy: the LODs are already in the pack's buffers
                }
            }
        });
        DhApi.events.bind(DhApiBeforeRenderCleanupEvent.class, new DhApiBeforeRenderCleanupEvent() {
            @Override public void beforeCleanup(DhApiEventParam<DhApiRenderParam> event) {
                if (!overriding) return;
                trace("cleanup buffers=" + buffersThisFrame);
                // Hand OptiFine back exactly what it had: its framebuffer, then its program (which sets its draw buffers).
                lodProgram = null;
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
                if (savedProgram != null) Shaders.useProgram(savedProgram);
            }
        });
    }

    /**
     * Distant Horizons hooks vanilla's translucent setup to draw water LODs under a shader loader.
     * OptiFine replaces that method, so the hook never runs; this is the same call from OptiFine's side.
     */
    public static void renderTranslucentLods() {
        if (!packSupportsLods()) return;
        ClientApi.INSTANCE.renderDeferredLodsForShaders();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        if (Shaders.activeProgram != null) GL20.glUseProgram(Shaders.activeProgram.getId());
    }

    // ---- framebuffer ---------------------------------------------------------------------------

    /** Point our framebuffer at whatever colour textures OptiFine's has attached right now. */
    private static void adoptColourBuffers() {
        if (fbo == 0) fbo = GL30.glGenFramebuffers();
        int[] textures = new int[8];
        for (int i = 0; i < 8; i++) {
            textures[i] = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        for (int i = 0; i < 8; i++) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, textures[i], 0);
        }
        attachDepth();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
    }

    /** LOD depth lives in Distant Horizons' own depth texture, never in the pack's depth buffer. */
    private static void attachDepth() {
        DhApiResult<Integer> depth = DhApi.Delayed.renderProxy.getDhDepthTextureId();
        if (!depth.success || depth.payload == null || depth.payload == depthTexture || fbo == 0) return;
        depthTexture = depth.payload;
        int bound = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bound);
    }

    /** dhDepthTex1: LOD depth before any translucent LODs are drawn. */
    private static void copyOpaqueDepth() {
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        int width = VIEWPORT.get(2), height = VIEWPORT.get(3);
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (opaqueDepthTexture == 0) opaqueDepthTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, opaqueDepthTexture);
        if (width != opaqueDepthWidth || height != opaqueDepthHeight) {
            opaqueDepthWidth = width;
            opaqueDepthHeight = height;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
    }

    // ---- uniforms ------------------------------------------------------------------------------

    public static void forgetUniforms(int program) {
        UNIFORMS.remove(program);
        UNIFORMS.remove(-program);
    }

    private static int[] locations(int program, String[] names) {
        int key = names == COMMON ? program : -program;
        int[] at = UNIFORMS.get(key);
        if (at == null) {
            at = new int[names.length];
            for (int i = 0; i < names.length; i++) at[i] = GL20.glGetUniformLocation(program, names[i]);
            UNIFORMS.put(key, at);
        }
        return at;
    }

    /** Uniforms every program of a DH-aware pack may read. Called each time OptiFine activates a program. */
    public static void setCommonUniforms(int program) {
        int[] at = locations(program, COMMON);
        if (at[0] >= 0) GL20.glUniform1f(at[0], nearPlane);
        if (at[1] >= 0) GL20.glUniform1f(at[1], farPlane);
        if (at[2] >= 0) GL20.glUniform1i(at[2], renderDistance);
        if (at[3] >= 0) GL20.glUniformMatrix4(at[3], false, fill(MAT4, projection));
        if (at[4] >= 0) GL20.glUniformMatrix4(at[4], false, fill(MAT4, projectionInverse));
        if (at[5] >= 0) GL20.glUniformMatrix4(at[5], false, fill(MAT4, previousProjection));
        if (at[6] < 0 && at[7] < 0 && at[8] < 0) return;
        if (at[6] >= 0) GL20.glUniform1i(at[6], DEPTH_UNIT);
        if (at[7] >= 0) GL20.glUniform1i(at[7], DEPTH_UNIT);
        if (at[8] >= 0) GL20.glUniform1i(at[8], DEPTH_UNIT_OPAQUE);
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT_OPAQUE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, opaqueDepthTexture != 0 ? opaqueDepthTexture : depthTexture);
        GL13.glActiveTexture(active);
    }

    /** What the patched LOD programs read in place of Minecraft's matrices. */
    private static void fillLodUniforms(int program, int worldYOffset) {
        int[] at = locations(program, LOD);
        MAT4.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT4);
        float[] modelView = new float[16];
        MAT4.get(modelView);
        if (at[0] >= 0) GL20.glUniformMatrix4(at[0], false, fill(MAT4, modelView));
        if (at[1] >= 0) GL20.glUniformMatrix4(at[1], false, fill(MAT4, projection));
        if (at[2] >= 0) {
            // The camera matrix is a pure rotation, so its normal matrix is its own upper 3x3.
            MAT3.clear();
            for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++) MAT3.put(modelView[col * 4 + row]);
            MAT3.flip();
            GL20.glUniformMatrix3(at[2], false, MAT3);
        }
        if (at[3] >= 0) GL20.glUniform1f(at[3], 0.01f);
        if (at[4] >= 0) GL20.glUniform1f(at[4], nearPlane);
        if (at[5] >= 0) GL20.glUniform1f(at[5], (float) worldYOffset);
    }

    /** Minecraft's projection with Distant Horizons' near and far planes: what packs get as dhProjection. */
    private static void updateProjection(float partialTicks) {
        System.arraycopy(projection, 0, previousProjection, 0, 16);
        renderDistance = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() * 16;
        nearPlane = DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(partialTicks);
        farPlane = (float) ((renderDistance + 512) * Math.sqrt(2.0));
        MAT4.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT4);
        MAT4.get(projection);
        projection[10] = -(farPlane + nearPlane) / (farPlane - nearPlane);
        projection[14] = -2.0f * farPlane * nearPlane / (farPlane - nearPlane);
        invert(projection, projectionInverse);
    }

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    private static FloatBuffer fill(FloatBuffer buffer, float[] m) {
        buffer.clear();
        buffer.put(m);
        buffer.flip();
        return buffer;
    }

    /** General 4x4 inverse (column-major), by cofactors. Leaves out untouched if m is singular. */
    static void invert(float[] m, float[] out) {
        float[] inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0f) return;
        for (int i = 0; i < 16; i++) out[i] = inv[i] / det;
    }
}
