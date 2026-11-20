import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseCube;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseGen;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cave.CaveCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarver;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.state.TestState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

/**
 * Drives Better Caves' noise + carving the way its chunk controllers do, with randomised settings,
 * and returns a hash of every noise value and every dig decision. Run once against the original
 * classes and once against the patched ones; the two results must be equal.
 */
public class Scenario implements Supplier<String> {
    private static final sun.misc.Unsafe UNSAFE;
    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    /** -Dscenario.timing=true skips hashing the noise values, so the run time is the pipeline's own. */
    private static final boolean TIMING = Boolean.getBoolean("scenario.timing");

    private static void hashCube(NoiseCube cube, int minY, int maxY) {
        if (TIMING) return;
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = minY; y <= maxY; y++) {
            for (double d : cube.get(x).get(z).get(y).getNoiseValues()) CarverUtils.mix(Double.doubleToRawLongBits(d));
        }
    }

    @Override
    public String get() {
        try {
            Random r = new Random(20261120L);
            FastNoise.NoiseType[] types = {FastNoise.NoiseType.CubicFractal, FastNoise.NoiseType.PerlinFractal,
                    FastNoise.NoiseType.SimplexFractal, FastNoise.NoiseType.ValueFractal, FastNoise.NoiseType.Cubic};
            ChunkPrimer primer = new ChunkPrimer();
            for (int t = 0; t < 600; t++) {
                World world = new World();
                world.seed = r.nextLong();
                world.seaLevel = 40 + r.nextInt(40);
                NoiseSettings ns = new NoiseSettings().setNoiseType(types[r.nextInt(types.length)])
                        .setFractalType(FastNoise.FractalType.values()[r.nextInt(3)])
                        .setOctaves(1 + r.nextInt(3)).setGain(0.2f + r.nextFloat()).setFrequency(0.01f + r.nextFloat() * 0.06f);
                CarverSettings cs = new CarverSettings(world);
                cs.setNoiseSettings(ns);
                cs.setFastNoise(r.nextInt(4) != 0);
                cs.setNumGens(1 + r.nextInt(3));
                cs.setyCompression(0.5f + r.nextFloat() * 4);
                cs.setXzCompression(0.5f + r.nextFloat() * 2);
                cs.setNoiseThreshold(r.nextFloat() * 1.6f - 0.8f);
                cs.setLiquidAltitude(r.nextInt(30));
                cs.setReplaceFloatingGravel(r.nextBoolean());
                cs.setEnableDebugVisualizer(r.nextInt(12) == 0);
                cs.setDebugBlock(new TestState(4));
                NoiseGen gen = new NoiseGen(world, cs.isFastNoise(), ns, cs.getNumGens(), cs.getyCompression(), cs.getXzCompression());
                int bottomY = 1 + r.nextInt(20);

                CaveCarver cave = (CaveCarver) UNSAFE.allocateInstance(CaveCarver.class);
                set(cave, "settings", cs); set(cave, "noiseGen", gen); set(cave, "world", world);
                set(cave, "surfaceCutoff", r.nextInt(25)); set(cave, "bottomY", bottomY); set(cave, "topY", 200);
                set(cave, "enableYAdjust", r.nextInt(3) != 0);
                set(cave, "yAdjustF1", r.nextFloat()); set(cave, "yAdjustF2", r.nextFloat());
                CavernCarver cavern = (CavernCarver) UNSAFE.allocateInstance(CavernCarver.class);
                set(cavern, "settings", cs); set(cavern, "noiseGen", gen); set(cavern, "world", world);
                set(cavern, "cavernType", CavernType.values()[r.nextInt(2)]); set(cavern, "bottomY", bottomY); set(cavern, "topY", 200);

                for (int s = 0; s < 4; s++) {
                    int startX = (r.nextInt(2000) - 1000) * 4, startZ = (r.nextInt(2000) - 1000) * 4;
                    int maxHeight = bottomY + r.nextInt(90);
                    NoiseCube cube = gen.interpolateNoiseCube(new BlockPos(startX, 1, startZ), new BlockPos(startX + 3, 1, startZ + 3), bottomY, maxHeight);
                    hashCube(cube, bottomY, maxHeight);
                    for (int ox = 0; ox < 4; ox++) for (int oz = 0; oz < 4; oz++) {
                        NoiseColumn col = cube.get(ox).get(oz);
                        int topY = bottomY + r.nextInt(maxHeight - bottomY + 1);
                        IBlockState liquid = r.nextBoolean() ? null : new TestState(3);
                        boolean flooded = r.nextBoolean();
                        BlockPos colPos = new BlockPos(startX + ox, 1, startZ + oz);
                        if ((t & 1) == 0) cave.carveColumn(primer, colPos, topY, col, liquid, flooded);
                        else cavern.carveColumn(primer, colPos, topY, r.nextFloat() * 1.5f, col, liquid, flooded);
                    }
                    hashCube(cube, bottomY, maxHeight);
                }
            }
            return Long.toHexString(CarverUtils.hash) + " after " + CarverUtils.calls + " dig calls";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
