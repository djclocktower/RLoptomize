package net.minecraft.world;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

/** A small block grid standing in for a world. */
public class World {
    public long seed;
    public int seaLevel = 63;
    public int height = 256;
    public final Map<Long, IBlockState> blocks = new HashMap<Long, IBlockState>();
    public final Map<Long, Integer> skyHeight = new HashMap<Long, Integer>();
    public long blockReads;

    private static long key(int x, int y, int z) { return ((long) (x & 0xFFFFF) << 28) | ((long) (z & 0xFFFFF) << 8) | (y & 0xFF); }
    public void set(int x, int y, int z, IBlockState s) { blocks.put(key(x, y, z), s); }
    public IBlockState get(int x, int y, int z) {
        blockReads++;
        IBlockState s = y < 0 || y >= 256 ? null : blocks.get(key(x, y, z));
        return s != null ? s : Blocks.field_150350_a.func_176223_P();
    }

    public long func_72905_C() { return seed; }                                       // getSeed
    public int func_181545_F() { return seaLevel; }                                   // getSeaLevel
    public int func_72940_L() { return height; }                                      // getActualHeight
    public IBlockState func_180495_p(BlockPos p) { return get(p.func_177958_n(), p.func_177956_o(), p.func_177952_p()); }
    public boolean func_175678_i(BlockPos p) {                                        // canSeeSky
        Integer h = skyHeight.get(key(p.func_177958_n(), 0, p.func_177952_p()));
        return h == null || p.func_177956_o() >= h;
    }
    public Chunk func_72964_e(int cx, int cz) { return new Chunk(this); }             // getChunk
}
