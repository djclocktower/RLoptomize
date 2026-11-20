package net.minecraft.world.chunk;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
public class Chunk { private final World world; public Chunk(World world) { this.world = world; }
    public IBlockState func_186032_a(int x, int y, int z) { return world.get(x, y, z); } }
