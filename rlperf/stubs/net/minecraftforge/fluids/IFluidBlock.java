package net.minecraftforge.fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
/** Only the one method the patches call. */
public interface IFluidBlock { float getFilledPercentage(World world, BlockPos pos); }
