package rlperf.hooks;

/** State shared with the bytecode injected into World and Chunk. */
public final class WorldgenHooks {
    private WorldgenHooks() {}

    /** How many Chunk.populate calls are on the stack. Only the server thread populates. */
    public static int populateDepth;
}
