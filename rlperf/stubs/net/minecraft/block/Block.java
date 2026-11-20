package net.minecraft.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.block.state.TestState;
import net.minecraft.util.ResourceLocation;

public class Block {
    private final IBlockState state;
    private final ResourceLocation name;
    public Block(int id) { this(id, "test:block" + id); }
    public Block(int id, String name) { this.state = new TestState(id, this, 0); this.name = new ResourceLocation(name); }
    public final IBlockState func_176223_P() { return state; }                       // getDefaultState
    public int func_176201_c(IBlockState s) { return ((TestState) s).meta; }         // getMetaFromState
    public final ResourceLocation getRegistryName() { return name; }
}
