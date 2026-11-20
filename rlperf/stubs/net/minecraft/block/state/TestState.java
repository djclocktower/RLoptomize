package net.minecraft.block.state;
import net.minecraft.block.Block;
public class TestState implements IBlockState { public final int id; public final Block block; public final int meta;
    public TestState(int id) { this(id, null, 0); }
    public TestState(int id, Block block, int meta) { this.id = id; this.block = block; this.meta = meta; }
    @Override public Block func_177230_c() { return block; } }
