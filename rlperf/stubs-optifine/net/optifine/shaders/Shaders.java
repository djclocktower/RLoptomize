package net.optifine.shaders;
/** Compile-only stand-in for OptiFine. */
public class Shaders {
    public static final Program ProgramNone = null;
    public static Program activeProgram;
    public static boolean isShadowPass, shaderPackLoaded, isRenderingWorld;
    public static int renderWidth, renderHeight;
    public static void useProgram(Program program) {}
}
