import com.lycanitesmobs.core.spawner.location.BlockSpawnLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.state.TestState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Runs Lycanites' block spawner scan over random little worlds with random spawner settings and
 * returns a hash of every position list it produces, in order. Original and patched must agree.
 */
public class SpawnScanScenario implements Supplier<String> {
    /** A modded fluid: how full it is depends on the state. */
    static class FluidBlock extends Block implements IFluidBlock {
        FluidBlock(int id, String name) { super(id, name); }
        @Override public float getFilledPercentage(World world, BlockPos pos) {
            return new float[] {1.0f, -1.0f, 0.5f, 0.0f}[((TestState) world.func_180495_p(pos)).meta];
        }
    }

    @Override
    public String get() {
        Random r = new Random(4711);
        Block[] palette = {new Block(10, "minecraft:stone"), new Block(11, "minecraft:dirt"), new Block(12, "minecraft:pumpkin"),
                new Block(13, "minecraft:lit_pumpkin"), new BlockLiquid(14, "minecraft:lava"), new FluidBlock(15, "test:ooze"),
                new Block(16, "minecraft:red_flower")};
        String[] ids = {"minecraft:stone", "minecraft:pumpkin", "minecraft:lit_pumpkin", "minecraft:lava", "test:ooze", "minecraft:air", "test:missing"};
        String[] listTypes = {"whitelist", "blacklist", "BlackList"};
        long hash = 7, positions = 0;
        for (int t = 0; t < 300; t++) {
            World world = new World();
            world.height = r.nextBoolean() ? 256 : 30 + r.nextInt(60);
            for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) {
                if (r.nextBoolean()) world.skyHeight.put(((long) (x & 0xFFFFF) << 28) | ((long) (z & 0xFFFFF) << 8), r.nextInt(70));
                for (int y = 0; y < 70; y++) {
                    if (r.nextInt(10) < 6) continue;
                    Block b = palette[r.nextInt(palette.length)];
                    world.set(x, y, z, new TestState(100, b, b instanceof BlockLiquid ? r.nextInt(3) : b instanceof FluidBlock ? r.nextInt(4) : 0));
                }
            }
            BlockSpawnLocation loc = new BlockSpawnLocation();
            loc.blockIds = new ArrayList<String>();
            for (int k = r.nextInt(4); k > 0; k--) loc.blockIds.add(ids[r.nextInt(ids.length)]);
            loc.listType = listTypes[r.nextInt(listTypes.length)];
            loc.surface = r.nextBoolean();
            loc.underground = r.nextBoolean();
            loc.blockCost = r.nextBoolean() ? -1 : r.nextInt(60);
            loc.requiredBlockTypes = r.nextInt(3) == 0 ? r.nextInt(4) : 0;
            loc.offset = r.nextBoolean() ? new Vec3i(0, 0, 0) : new Vec3i(r.nextInt(3) - 1, r.nextInt(3), r.nextInt(3) - 1);
            loc.rangeMax = new Vec3i(r.nextInt(11), r.nextInt(11), r.nextInt(11));
            loc.yMin = r.nextInt(3) == 0 ? r.nextInt(40) : -1;
            loc.yMax = r.nextInt(3) == 0 ? r.nextInt(80) : -1;
            loc.sorting = "none";   // the default sort shuffles; the scan order is what is under test
            for (int k = 0; k < 3; k++) {   // repeated calls on one spawner, as in the game
                BlockPos trigger = new BlockPos(r.nextInt(13) - 6, r.nextInt(84) - 6, r.nextInt(13) - 6);
                List<BlockPos> found = loc.getSpawnPositions(world, null, trigger);
                hash = hash * 31 + found.size();
                for (BlockPos p : found) hash = hash * 31 + p.hashCode();
                positions += found.size();
            }
        }
        return Long.toHexString(hash) + " over " + positions + " positions";
    }
}
