package com.yungnickyoung.minecraft.bettercaves.world.carver.cave;

import com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

/**
 * Donor for Better Caves' CaveCarver. Only the three methods below are copied into the real class;
 * the fields are declared so this compiles and must match the real ones.
 */
public class CaveCarver {
    private CarverSettings settings;
    private World world;
    private int surfaceCutoff;
    private int bottomY;
    private boolean enableYAdjust;
    private float yAdjustF1;
    private float yAdjustF2;

    public void carveColumn(ChunkPrimer primer, BlockPos colPos, int topY, NoiseColumn noises, IBlockState liquidBlock, boolean flooded) {
        int localX = BetterCavesUtils.getLocal(colPos.func_177958_n());
        int localZ = BetterCavesUtils.getLocal(colPos.func_177952_p());
        if (localX < 0 || localX > 15) return;
        if (localZ < 0 || localZ > 15) return;
        if (this.bottomY < 0 || this.bottomY > 255) return;
        if (topY < 0 || topY > 255) return;
        int transitionBoundary = topY - this.surfaceCutoff;
        if (transitionBoundary < 1) transitionBoundary = 1;
        float[] thresholds = this.rlperf$thresholds(topY, this.bottomY, transitionBoundary);
        if (this.enableYAdjust) {
            this.rlperf$preprocess(noises, topY, this.bottomY, thresholds, this.settings.getNumGens());
        }
        double[] data = noises.data;
        int n = noises.n;
        int minY = noises.minY;
        for (int y = topY; y >= this.bottomY && (y > this.settings.getLiquidAltitude() || liquidBlock != null); --y) {
            double threshold = (double) thresholds[y - this.bottomY];
            int o = (y - minY) * n;
            boolean digBlock = true;
            for (int i = 0; i < n; ++i) {
                if (!(data[o + i] < threshold)) continue;
                digBlock = false;
                break;
            }
            IBlockState airBlockState = flooded && y < this.world.func_181545_F() ? Blocks.field_150355_j.func_176223_P() : Blocks.field_150350_a.func_176223_P();
            BlockPos blockPos = new BlockPos(localX, y, localZ);
            if (this.settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlock(primer, blockPos, this.settings.getDebugBlock(), digBlock);
                continue;
            }
            if (!digBlock) continue;
            CarverUtils.digBlock(this.settings.getWorld(), primer, blockPos, airBlockState, liquidBlock, this.settings.getLiquidAltitude(), this.settings.isReplaceFloatingGravel());
        }
    }

    /** Noise threshold per y, indexed by y - bottomY. */
    private float[] rlperf$thresholds(int topY, int bottomY, int transitionBoundary) {
        float[] thresholds = new float[Math.max(0, topY - bottomY + 1)];
        for (int realY = bottomY; realY <= topY; ++realY) {
            float noiseThreshold = this.settings.getNoiseThreshold();
            if (realY >= transitionBoundary) {
                noiseThreshold *= 1.0f + 0.3f * ((float) (realY - transitionBoundary) / (float) (topY - transitionBoundary));
            }
            thresholds[realY - bottomY] = noiseThreshold;
        }
        return thresholds;
    }

    private void rlperf$preprocess(NoiseColumn noises, int topY, int bottomY, float[] thresholds, int numGens) {
        double[] data = noises.data;
        int n = noises.n;
        int minY = noises.minY;
        for (int realY = topY; realY >= bottomY; --realY) {
            int o = (realY - minY) * n;
            float threshold = thresholds[realY - bottomY];
            boolean valid = true;
            for (int i = 0; i < n; ++i) {
                if (!(data[o + i] < (double) threshold)) continue;
                valid = false;
                break;
            }
            if (!valid) continue;
            float f1 = this.yAdjustF1;
            float f2 = this.yAdjustF2;
            if (realY < topY) {
                int above = o + n;
                for (int i = 0; i < numGens; ++i) {
                    data[above + i] = (double) (1.0f - f1) * data[above + i] + (double) f1 * data[o + i];
                }
            }
            if (realY >= topY - 1) continue;
            int twoAbove = o + 2 * n;
            for (int i = 0; i < numGens; ++i) {
                data[twoAbove + i] = (double) (1.0f - f2) * data[twoAbove + i] + (double) f2 * data[o + i];
            }
        }
    }
}
