package com.yungnickyoung.minecraft.bettercaves.world.carver;

import net.minecraft.block.state.IBlockState;
import net.minecraft.block.state.TestState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

/** Test double: records every dig decision instead of touching a chunk. */
public class CarverUtils {
    public static long hash = 1125899906842597L;
    public static long calls;

    public static void mix(long v) { hash = hash * 31 + v; }
    private static int id(IBlockState s) { return s == null ? 0 : ((TestState) s).id; }

    public static void digBlock(World world, ChunkPrimer primer, BlockPos pos, IBlockState air, IBlockState liquid, int liquidAltitude, boolean replaceGravel) {
        calls++;
        mix(1); mix(pos.func_177958_n()); mix(pos.func_177956_o()); mix(pos.func_177952_p()); mix(id(air)); mix(id(liquid)); mix(liquidAltitude); mix(replaceGravel ? 1 : 0);
    }

    public static void debugDigBlock(ChunkPrimer primer, BlockPos pos, IBlockState state, boolean digBlock) {
        calls++;
        mix(2); mix(pos.func_177958_n()); mix(pos.func_177956_o()); mix(pos.func_177952_p()); mix(id(state)); mix(digBlock ? 1 : 0);
    }
}
