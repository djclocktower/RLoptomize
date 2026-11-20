package rlperf.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Properties;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Every patch is gated: mod classes must match the exact file the patch was written against
 * (SHA-1 of the class file), vanilla classes must have the expected method. Anything else is
 * left untouched, and a failed patch never takes the class down with it.
 */
public class RLPerfTransformer implements IClassTransformer, Opcodes {
    static final Logger LOG = LogManager.getLogger("RLPerf");
    private static Properties cfg;

    private static final String HOOKS_OF = "rlperf/hooks/OptiFineHooks";
    private static final String HOOKS_WG = "rlperf/hooks/WorldgenHooks";
    private static final String SHADER_OPTION = "net/optifine/shaders/config/ShaderOption";
    private static final String BLOCK_POS = "Lnet/minecraft/util/math/BlockPos;";
    private static final String NEIGHBOR_DESC = "(" + BLOCK_POS + "Lnet/minecraft/block/Block;" + BLOCK_POS + ")V";

    // OptiFine 1.12.2 HD U G5
    private static final String SHA_SHADER_PACK_PARSER = "49ac54fa4f837de32abafaa3c0be359ce00d1740";
    private static final String SHA_SHADER_OPTION_SWITCH = "fd8ff50285ab79cda94fbac5f0cda3b5e3b1df80";

    private static Properties loadConfig() {
        Properties p = new Properties();
        try {
            File home = Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
            File f = new File(home, "config/rlperf.properties");
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) { p.load(in); }
            }
        } catch (Exception e) {
            LOG.warn("Could not read config/rlperf.properties, using defaults", e);
        }
        return p;
    }

    /** Patches default to on; switch one off in config/rlperf.properties or with -Drlperf.<key>=false. */
    static boolean enabled(String key) {
        return !"false".equalsIgnoreCase(setting(key, "true"));
    }

    static synchronized String setting(String key, String fallback) {
        String v = System.getProperty("rlperf." + key);
        if (v == null) {
            if (cfg == null) cfg = loadConfig();
            v = cfg.getProperty(key, fallback);
        }
        return v.trim();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || transformedName == null) return bytes;
        try {
            switch (transformedName) {
                case "net.optifine.shaders.config.ShaderPackParser":
                    return enabled("optifineShaderParser") ? patchShaderPackParser(bytes) : bytes;
                case "net.minecraft.world.World":
                    return enabled("populateCascadeGuard") ? patchWorld(bytes) : bytes;
                case "net.minecraft.world.chunk.Chunk":
                    return enabled("populateCascadeGuard") ? patchChunk(bytes) : bytes;
                case "net.minecraft.server.MinecraftServer":
                    return enabled("chunkLoadPacing") || enabled("worldgenPreload") || slowTickLog() ? patchServer(bytes) : bytes;
                case "net.optifine.shaders.Shaders":
                    return dhBridge() ? patchShaders(bytes) : bytes;
                case "net.optifine.shaders.config.ShaderMacros":
                    return dhBridge() ? patchShaderMacros(bytes) : bytes;
                case "com.seibel.distanthorizons.lwjgl.lwjgl2.LWJGL2Service":
                    return dhBridge() ? patchDhGl(bytes) : bytes;
                case "net.minecraft.server.management.PlayerChunkMap":
                    return enabled("chunkLoadPacing") ? patchPlayerChunkMap(bytes) : bytes;
                default:
                    return DonorPatches.apply(transformedName, bytes);
            }
        } catch (Throwable t) {
            LOG.error("Patch failed for " + transformedName + "; class left unchanged", t);
        }
        return bytes;
    }

    // ---- OptiFine: shader option parsing -------------------------------------------------------

    private static byte[] patchShaderPackParser(byte[] bytes) throws Exception {
        if (!SHA_SHADER_PACK_PARSER.equals(rawSha1("net.optifine.shaders.config.ShaderPackParser"))
                || !SHA_SHADER_OPTION_SWITCH.equals(rawSha1("net.optifine.shaders.config.ShaderOptionSwitch"))) {
            LOG.info("OptiFine is not 1.12.2 HD U G5; shader parser patch skipped");
            return bytes;
        }
        ClassNode cn = read(bytes);
        MethodNode used = method(cn, "isOptionUsed", "(L" + SHADER_OPTION + ";[Ljava/lang/String;)Z");
        MethodNode option = method(cn, "getShaderOption", "(Ljava/lang/String;Ljava/lang/String;)L" + SHADER_OPTION + ";");
        MethodNode macros = method(cn, "findMacros",
                "(Ljava/lang/String;[Lnet/optifine/shaders/config/ShaderMacro;)[Lnet/optifine/shaders/config/ShaderMacro;");
        if (used == null || option == null || macros == null || !headIsPlain(used) || !headIsPlain(option)) {
            LOG.warn("ShaderPackParser does not look as expected; shader parser patch skipped");
            return bytes;
        }

        // isOptionUsed(so, lines): a plain ShaderOptionSwitch is "used" iff an #ifdef names it.
        InsnList in = new InsnList();
        LabelNode orig = new LabelNode();
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new LdcInsnNode(Type.getObjectType("net/optifine/shaders/config/ShaderOptionSwitch")));
        in.add(new JumpInsnNode(IF_ACMPNE, orig));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, SHADER_OPTION, "getName", "()Ljava/lang/String;", false));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(INVOKESTATIC, HOOKS_OF, "isIfdefUsed", "(Ljava/lang/String;[Ljava/lang/String;)Z", false));
        in.add(new InsnNode(IRETURN));
        in.add(orig);
        in.add(new FrameNode(F_SAME, 0, null, 0, null));
        used.instructions.insert(in);

        // getShaderOption(line, path): most lines cannot match any option pattern.
        in = new InsnList();
        orig = new LabelNode();
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(INVOKESTATIC, HOOKS_OF, "cannotBeOption", "(Ljava/lang/String;)Z", false));
        in.add(new JumpInsnNode(IFEQ, orig));
        in.add(new InsnNode(ACONST_NULL));
        in.add(new InsnNode(ARETURN));
        in.add(orig);
        in.add(new FrameNode(F_SAME, 0, null, 0, null));
        option.instructions.insert(in);

        // findMacros(line, macros): same result, one pass over the line instead of one per macro.
        macros.instructions.clear();
        macros.tryCatchBlocks.clear();
        if (macros.localVariables != null) macros.localVariables.clear();
        macros.instructions.add(new VarInsnNode(ALOAD, 0));
        macros.instructions.add(new VarInsnNode(ALOAD, 1));
        macros.instructions.add(new MethodInsnNode(INVOKESTATIC, HOOKS_OF, "findMacros",
                "(Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;", false));
        macros.instructions.add(new TypeInsnNode(CHECKCAST, "[Lnet/optifine/shaders/config/ShaderMacro;"));
        macros.instructions.add(new InsnNode(ARETURN));

        LOG.info("Patched OptiFine shader option parsing");
        return write(cn);
    }

    // ---- Distant Horizons through OptiFine shader packs ---------------------------------------

    private static final String HOOKS_SH = "rlperf/hooks/ShaderHooks";
    private static final String PROGRAM = "Lnet/optifine/shaders/Program;";
    private static final String SHA_SHADERS = "755b44c46e4cf94ef8f518a152bce592c59026b6";
    private static final String SHA_SHADER_MACROS = "d29a60f0fc14a148bb8ead6faf0d9675d336885e";
    private static Boolean dhBridge;

    /** Only when the bridge is switched on, Distant Horizons is installed and OptiFine is exactly G5. */
    private static synchronized boolean dhBridge() {
        if (dhBridge == null) {
            boolean ok = false;
            try {
                ok = enabled("distantHorizonsShaders")
                        && Launch.classLoader.getResource("com/seibel/distanthorizons/api/DhApi.class") != null
                        && SHA_SHADERS.equals(rawSha1("net.optifine.shaders.Shaders"))
                        && SHA_SHADER_MACROS.equals(rawSha1("net.optifine.shaders.config.ShaderMacros"));
            } catch (Exception e) {
                // not there: no bridge
            }
            dhBridge = ok;
        }
        return dhBridge;
    }

    private static byte[] patchShaders(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode clinit = method(cn, "<clinit>", "()V");
        MethodNode vert = method(cn, "createVertShader", "(" + PROGRAM + "Ljava/lang/String;)I");
        MethodNode frag = method(cn, "createFragShader", "(" + PROGRAM + "Ljava/lang/String;)I");
        MethodNode setup = method(cn, "setupProgram", "(" + PROGRAM + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        MethodNode use = method(cn, "useProgram", "(" + PROGRAM + ")V");
        MethodNode water = method(cn, "beginWater", "()V");
        if (clinit == null || vert == null || frag == null || setup == null || use == null || water == null) {
            LOG.warn("OptiFine Shaders does not look as expected; Distant Horizons bridge skipped");
            return bytes;
        }
        int done = 0;
        // programs.getPrograms() -> ProgramsAll: add the LOD programs to the list first
        for (AbstractInsnNode n : clinit.instructions.toArray()) {
            if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).name.equals("getPrograms")
                    && ((MethodInsnNode) n).desc.equals("()[Lnet/optifine/shaders/Program;")) {
                InsnList in = new InsnList();
                in.add(new InsnNode(DUP));
                in.add(new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "registerPrograms", "(Lnet/optifine/shaders/Programs;)V", false));
                clinit.instructions.insertBefore(n, in);
                done++;
                break;
            }
        }
        for (MethodNode m : new MethodNode[] {vert, frag}) {
            InsnList head = new InsnList();
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new VarInsnNode(ALOAD, 1));
            head.add(new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "compiling", "(" + PROGRAM + "Ljava/lang/String;)V", false));
            m.instructions.insert(head);
            done += redirect(m, "glShaderSourceARB", "(ILjava/lang/CharSequence;)V", "shaderSource");
        }
        done += redirect(setup, "glLinkProgramARB", "(I)V", "linkProgram");
        for (AbstractInsnNode n : use.instructions.toArray()) {
            if (n.getOpcode() != RETURN) continue;
            InsnList in = new InsnList();
            in.add(new VarInsnNode(ALOAD, 0));
            in.add(new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "afterUseProgram", "(" + PROGRAM + ")V", false));
            use.instructions.insertBefore(n, in);
        }
        // Translucent LODs go in once OptiFine is set up for water, after the pack's deferred passes.
        for (AbstractInsnNode n : water.instructions.toArray()) {
            if (n.getOpcode() == RETURN) {
                water.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "afterBeginWater", "()V", false));
            }
        }
        if (done != 4) {
            LOG.warn("OptiFine Shaders does not look as expected (" + done + "/4); Distant Horizons bridge skipped");
            return bytes;
        }
        LOG.info("Patched OptiFine Shaders (Distant Horizons bridge)");
        return write(cn);
    }

    /** Points every call of an OptiFine GL function in the method at the hook of the same shape. */
    private static int redirect(MethodNode m, String name, String desc, String hook) {
        int count = 0;
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n.getOpcode() == INVOKESTATIC && ((MethodInsnNode) n).name.equals(name) && ((MethodInsnNode) n).desc.equals(desc)) {
                m.instructions.set(n, new MethodInsnNode(INVOKESTATIC, HOOKS_SH, hook, desc, false));
                count++;
            }
        }
        return count;
    }

    private static byte[] patchShaderMacros(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode lines = method(cn, "getFixedMacroLines", "()Ljava/lang/String;");
        if (lines == null) return bytes;
        for (AbstractInsnNode n : lines.instructions.toArray()) {
            if (n.getOpcode() == ARETURN) {
                lines.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "macros", "(Ljava/lang/String;)Ljava/lang/String;", false));
            }
        }
        return write(cn);
    }

    /** Distant Horizons unbinding its program must not leave OptiFine's terrain without one. */
    private static byte[] patchDhGl(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode use = method(cn, "glUseProgram", "(I)V");
        if (use == null) return bytes;
        InsnList in = new InsnList();
        in.add(new VarInsnNode(ILOAD, 1));
        in.add(new MethodInsnNode(INVOKESTATIC, HOOKS_SH, "programForDh", "(I)I", false));
        in.add(new VarInsnNode(ISTORE, 1));
        use.instructions.insert(in);
        return write(cn);
    }

    // ---- Worldgen: no chunk generation from neighbour updates during population ---------------

    private static byte[] patchWorld(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode changed = method(cn, "func_190524_a", NEIGHBOR_DESC);   // neighborChanged
        MethodNode observed = method(cn, "func_190529_b", NEIGHBOR_DESC);  // observedNeighborChanged
        if (changed == null || observed == null || !headIsPlain(changed) || !headIsPlain(observed)) {
            LOG.warn("World does not look as expected; cascade guard skipped");
            return bytes;
        }
        for (MethodNode m : new MethodNode[] {changed, observed}) {
            // if (populateDepth > 0 && !isBlockLoaded(pos)) return;
            InsnList in = new InsnList();
            LabelNode orig = new LabelNode();
            in.add(new FieldInsnNode(GETSTATIC, HOOKS_WG, "populateDepth", "I"));
            in.add(new JumpInsnNode(IFLE, orig));
            in.add(new VarInsnNode(ALOAD, 0));
            in.add(new VarInsnNode(ALOAD, 1));
            in.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/World", "func_175667_e", "(" + BLOCK_POS + ")Z", false));
            in.add(new JumpInsnNode(IFNE, orig));
            in.add(new InsnNode(RETURN));
            in.add(orig);
            in.add(new FrameNode(F_SAME, 0, null, 0, null));
            m.instructions.insert(in);
        }
        LOG.info("Patched World neighbour updates (cascade guard)");
        return write(cn);
    }

    private static byte[] patchChunk(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode populate = method(cn, "func_186034_a", "(Lnet/minecraft/world/gen/IChunkGenerator;)V");
        if (populate == null) {
            LOG.warn("Chunk does not look as expected; cascade guard skipped");
            return bytes;
        }
        // populateDepth++; try { body } finally { populateDepth--; }
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        for (AbstractInsnNode n : populate.instructions.toArray()) {
            if (n.getOpcode() == RETURN) populate.instructions.insertBefore(n, depth(ISUB));
        }
        InsnList head = depth(IADD);
        head.add(start);
        populate.instructions.insert(head);
        populate.instructions.add(end);
        populate.instructions.add(handler);
        populate.instructions.add(new FrameNode(F_FULL, 2,
                new Object[] {"net/minecraft/world/chunk/Chunk", "net/minecraft/world/gen/IChunkGenerator"},
                1, new Object[] {"java/lang/Throwable"}));
        populate.instructions.add(depth(ISUB));
        populate.instructions.add(new InsnNode(ATHROW));
        populate.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        LOG.info("Patched Chunk.populate (cascade guard)");
        return write(cn);
    }

    // ---- Server tick: chunk load pacing and the slow tick log ------------------------------------

    private static boolean slowTickLog() {
        return "true".equalsIgnoreCase(setting("slowTickLog", "false"));
    }

    /**
     * PlayerChunkMap.tick loads and generates chunks for players until 50 ms have passed. Replace that
     * constant with the paced budget, and let the tick skip the loop when the server is behind.
     */
    private static byte[] patchPlayerChunkMap(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode tick = method(cn, "func_72693_b", "()V");
        if (tick != null) {
            for (AbstractInsnNode n : tick.instructions.toArray()) {
                if (!(n instanceof LdcInsnNode) || !Long.valueOf(50000000L).equals(((LdcInsnNode) n).cst)) continue;
                // if (!list.isEmpty()) { long deadline = System.nanoTime() + 50000000L; ...
                AbstractInsnNode back = n;
                for (int i = 0; i < 6 && back != null && back.getOpcode() != IFNE; i++) back = back.getPrevious();
                if (back != null && back.getOpcode() == IFNE) {
                    InsnList gate = new InsnList();
                    gate.add(new MethodInsnNode(INVOKESTATIC, "rlperf/hooks/TickWatch", "mayLoadChunks", "()Z", false));
                    gate.add(new JumpInsnNode(IFEQ, ((JumpInsnNode) back).label));
                    tick.instructions.insert(back, gate);
                }
                tick.instructions.set(n, new MethodInsnNode(INVOKESTATIC, "rlperf/hooks/TickWatch", "chunkBudgetNanos", "()J", false));
                LOG.info("Patched PlayerChunkMap (chunk load pacing)");
                return write(cn);
            }
        }
        LOG.warn("PlayerChunkMap does not look as expected; chunk load pacing skipped");
        return bytes;
    }

    private static byte[] patchServer(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode run = method(cn, "run", "()V");
        int wrapped = 0;
        if (run != null) {
            for (AbstractInsnNode n : run.instructions.toArray()) {
                if (n.getOpcode() == INVOKEVIRTUAL && ((MethodInsnNode) n).name.equals("func_71217_p")   // tick()
                        && ((MethodInsnNode) n).desc.equals("()V")) {
                    run.instructions.insertBefore(n, new MethodInsnNode(INVOKESTATIC, "rlperf/hooks/TickWatch", "tickStart", "()V", false));
                    run.instructions.insert(n, new MethodInsnNode(INVOKESTATIC, "rlperf/hooks/TickWatch", "tickEnd", "()V", false));
                    wrapped++;
                }
            }
        }
        if (wrapped == 0) {
            LOG.warn("MinecraftServer does not look as expected; tick hooks skipped");
            return bytes;
        }
        if (enabled("worldgenPreload")) {
            run.instructions.insert(new MethodInsnNode(INVOKESTATIC, "rlperf/hooks/TickWatch", "serverStarting", "()V", false));
        }
        if (slowTickLog()) {
            rlperf.hooks.TickWatch.configure(Launch.minecraftHome != null ? Launch.minecraftHome : new File("."),
                    Long.parseLong(setting("slowTickThresholdMs", "100")));
            LOG.info("Slow tick log enabled: logs/rlperf-slow-ticks.log");
        }
        return write(cn);
    }

    private static InsnList depth(int op) {
        InsnList in = new InsnList();
        in.add(new FieldInsnNode(GETSTATIC, HOOKS_WG, "populateDepth", "I"));
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(op));
        in.add(new FieldInsnNode(PUTSTATIC, HOOKS_WG, "populateDepth", "I"));
        return in;
    }

    // ---- helpers ------------------------------------------------------------------------------

    static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static MethodNode method(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    /** True if no stack map frame sits before the first real instruction, so one can be added there. */
    private static boolean headIsPlain(MethodNode m) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof FrameNode) return false;
            if (n.getOpcode() >= 0) return true;
        }
        return false;
    }

    /** SHA-1 of a class file as it sits in its jar, before any transformer touched it. */
    static String rawSha1(String className) throws Exception {
        byte[] raw = Launch.classLoader.getClassBytes(className);
        return raw == null ? null : sha1(raw);
    }

    static String sha1(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static byte[] resource(String path) throws Exception {
        try (InputStream in = RLPerfTransformer.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
