package net.minecraft.block;
import net.minecraft.block.state.*;
public class Block { private final IBlockState state; public Block(int id) { state = new TestState(id); } public final IBlockState func_176223_P() { return state; } }
