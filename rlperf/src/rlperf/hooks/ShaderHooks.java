package rlperf.hooks;

import net.optifine.shaders.Program;
import net.optifine.shaders.Programs;
import net.optifine.shaders.Shaders;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL20;

import rlperf.dh.DhBridge;
import rlperf.dh.DhShaderPatch;

/**
 * Called from code injected into OptiFine's shader loader. Lets OptiFine load a shader pack's
 * Distant Horizons programs (dh_terrain, dh_water) like any other program, and gives every
 * program the extra macro and uniforms such packs expect. Does nothing unless Distant Horizons
 * is installed.
 */
public final class ShaderHooks {
    private ShaderHooks() {}

    public static final boolean DH_PRESENT =
            ShaderHooks.class.getClassLoader().getResource("com/seibel/distanthorizons/api/DhApi.class") != null;

    /** The pack's LOD programs; their id is 0 when the loaded pack has none. */
    public static Program dhTerrain, dhWater;
    private static Program compiling;
    private static String compilingFile;

    /** Shaders.<clinit>, just before the program list is frozen. */
    public static void registerPrograms(Programs programs) {
        if (!DH_PRESENT) return;
        dhTerrain = programs.makeGbuffers("dh_terrain", Shaders.ProgramNone);
        dhWater = programs.makeGbuffers("dh_water", Shaders.ProgramNone);
        DhBridge.init();
    }

    public static boolean isDhProgram(Program p) {
        return p != null && (p == dhTerrain || p == dhWater);
    }

    /** Head of createVertShader / createFragShader. */
    public static void compiling(Program program, String filename) {
        compiling = program;
        compilingFile = filename;
    }

    /** Stands in for glShaderSourceARB: LOD programs read Distant Horizons' vertex format, not Minecraft's. */
    public static void shaderSource(int shader, CharSequence code) {
        if (isDhProgram(compiling) && compilingFile != null) {
            code = DhShaderPatch.patch(code.toString(), compilingFile.endsWith(".vsh"));
            if (Boolean.getBoolean("rlperf.dhTrace")) {
                try {
                    java.nio.file.Files.write(new java.io.File("logs/rlperf" + compilingFile.replace('/', '_')).toPath(), code.toString().getBytes("UTF-8"));
                } catch (Exception e) {
                    // debugging aid only
                }
            }
        }
        ARBShaderObjects.glShaderSourceARB(shader, code);
    }

    /** Stands in for glLinkProgramARB. */
    public static void linkProgram(int program) {
        if (isDhProgram(compiling)) {
            GL20.glBindAttribLocation(program, 0, "vPosition");
            GL20.glBindAttribLocation(program, 1, "iris_color");
            GL20.glBindAttribLocation(program, 2, "irisExtra");
        }
        if (DH_PRESENT) DhBridge.forgetUniforms(program);
        ARBShaderObjects.glLinkProgramARB(program);
    }

    /** Tail of ShaderMacros.getFixedMacroLines. */
    public static String macros(String lines) {
        return DH_PRESENT ? lines + DhShaderPatch.MACROS : lines;
    }

    /** Tail of Shaders.useProgram. */
    public static void afterUseProgram(Program program) {
        if (DH_PRESENT && program != null && program.getId() != 0) DhBridge.setCommonUniforms(program.getId());
    }

    /** Tail of Shaders.beginWater: the point where Iris-style loaders have Distant Horizons draw translucent LODs. */
    public static void afterBeginWater() {
        if (DH_PRESENT && !Shaders.isShadowPass) DhBridge.renderTranslucentLods();
    }

    /**
     * Distant Horizons finishes by binding program 0. Under an OptiFine shader pack that would leave
     * the terrain that follows with no shader at all, so give it back the program OptiFine has active.
     */
    public static int programForDh(int program) {
        if (program == 0 && Shaders.shaderPackLoaded && Shaders.isRenderingWorld && Shaders.activeProgram != null) {
            return Shaders.activeProgram.getId();
        }
        return program;
    }
}
