package com.yungnickyoung.minecraft.bettercaves.world.carver.cavern;

import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverSettings;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

/** Donor for Better Caves' CavernCarver; only carveColumn is copied into the real class. */
public class CavernCarver {
    private CarverSettings settings;
    private World world;
    private CavernType cavernType;
    private int bottomY;

    public void carveColumn(ChunkPrimer primer, BlockPos colPos, int topY, float smoothAmp, NoiseColumn noises, IBlockState liquidBlock, boolean flooded) {
        int localX = BetterCavesUtils.getLocal(colPos.func_177958_n());
        int localZ = BetterCavesUtils.getLocal(colPos.func_177952_p());
        if (localX < 0 || localX > 15) return;
        if (localZ < 0 || localZ > 15) return;
        if (this.bottomY < 0 || this.bottomY > 255) return;
        if (topY > 255) return;
        int topTransitionBoundary = (topY -= 2) - 6;
        int bottomTransitionBoundary = this.bottomY + 3;
        if (this.cavernType == CavernType.FLOORED) {
            bottomTransitionBoundary = this.bottomY < this.settings.getLiquidAltitude() ? this.settings.getLiquidAltitude() + 8 : this.bottomY + 7;
        }
        topTransitionBoundary = Math.max(topTransitionBoundary, 1);
        bottomTransitionBoundary = Math.min(bottomTransitionBoundary, 255);
        double[] data = noises.data;
        int n = noises.n;
        int minY = noises.minY;
        for (int y = topY; y >= this.bottomY && (y > this.settings.getLiquidAltitude() || liquidBlock != null); --y) {
            boolean digBlock = false;
            float noise = 1.0f;
            int o = (y - minY) * n;
            for (int i = 0; i < n; ++i) {
                noise = (float) ((double) noise * data[o + i]);
            }
            float noiseThreshold = this.settings.getNoiseThreshold();
            if (y >= topTransitionBoundary) {
                noiseThreshold *= (float) (y - topY) / (float) (topTransitionBoundary - topY);
            }
            if (y < bottomTransitionBoundary) {
                noiseThreshold *= (float) (y - this.bottomY) / (float) (bottomTransitionBoundary - this.bottomY);
            }
            if (smoothAmp < 1.0f) {
                noiseThreshold *= smoothAmp;
            }
            if (noise < noiseThreshold) {
                digBlock = true;
            }
            BlockPos blockPos = new BlockPos(localX, y, localZ);
            IBlockState airBlockState = flooded && y < this.world.func_181545_F() ? Blocks.field_150355_j.func_176223_P() : Blocks.field_150350_a.func_176223_P();
            if (this.settings.isEnableDebugVisualizer()) {
                CarverUtils.debugDigBlock(primer, blockPos, this.settings.getDebugBlock(), digBlock);
                continue;
            }
            if (!digBlock) continue;
            CarverUtils.digBlock(this.settings.getWorld(), primer, blockPos, airBlockState, liquidBlock, this.settings.getLiquidAltitude(), this.settings.isReplaceFloatingGravel());
        }
    }
}
