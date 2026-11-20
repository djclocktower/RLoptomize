package com.yungnickyoung.minecraft.bettercaves.noise;

import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Same noise, same interpolation and the same arithmetic as Better Caves' NoiseGen, written against
 * flat double arrays. The original builds an ArrayList of boxed Doubles for every block and keys
 * them by boxed Integer in a HashMap per column.
 */
public class NoiseGen {
    private long seed;
    private int numGenerators;
    private NoiseSettings noiseSettings;
    private float yCompression;
    private float xzCompression;
    private List<INoiseLibrary> listNoiseGens = new ArrayList<INoiseLibrary>();
    private INoiseLibrary[] gens;

    public NoiseGen(World world, boolean isFastNoise, NoiseSettings noiseSettings, int numGenerators, float yComp, float xzComp) {
        this.seed = world.func_72905_C();
        this.noiseSettings = noiseSettings;
        this.numGenerators = numGenerators;
        this.yCompression = yComp;
        this.xzCompression = xzComp;
        this.initializeNoiseGens(isFastNoise);
        this.gens = this.listNoiseGens.toArray(new INoiseLibrary[0]);
    }

    /** Raw noise for every y of one column, written into {@code col}. */
    private void fill(NoiseColumn col, int x, int z, int minHeight, int maxHeight) {
        double[] data = col.data;
        int n = this.numGenerators;
        float fx = (float) x * this.xzCompression;
        float fz = (float) z * this.xzCompression;
        int o = 0;
        for (int y = minHeight; y <= maxHeight; ++y) {
            float fy = (float) y * this.yCompression;
            for (int i = 0; i < n; ++i) data[o++] = this.gens[i].GetNoise(fx, fy, fz);
        }
    }

    public NoiseColumn generateNoiseColumn(BlockPos blockPos, int minHeight, int maxHeight) {
        NoiseColumn col = new NoiseColumn(minHeight, maxHeight, this.numGenerators);
        this.fill(col, blockPos.func_177958_n(), blockPos.func_177952_p(), minHeight, maxHeight);
        return col;
    }

    public NoiseColumn interpolateNoiseColumn(BlockPos blockPos, int minHeight, int maxHeight, int subChunkSize) {
        int x = blockPos.func_177958_n();
        int z = blockPos.func_177952_p();
        int n = this.numGenerators;
        NoiseColumn col = new NoiseColumn(minHeight, maxHeight, n);
        double[] data = col.data;
        float fx = (float) x * this.xzCompression;
        float fz = (float) z * this.xzCompression;
        double[] start = new double[n];
        double[] end = new double[n];
        for (int startY = minHeight; startY <= maxHeight; startY += subChunkSize) {
            int endY = Math.min(startY + subChunkSize - 1, maxHeight);
            float fyStart = (float) startY * this.yCompression;
            float fyEnd = (float) endY * this.yCompression;
            for (int i = 0; i < n; ++i) {
                start[i] = this.gens[i].GetNoise(fx, fyStart, fz);
                end[i] = this.gens[i].GetNoise(fx, fyEnd, fz);
            }
            System.arraycopy(start, 0, data, (startY - minHeight) * n, n);
            System.arraycopy(end, 0, data, (endY - minHeight) * n, n);
            for (int y = startY + 1; y < endY; ++y) {
                float endCoeff;
                float startCoeff;
                if (endY == maxHeight) {
                    startCoeff = (float) (endY - startY - y - startY) / (float) (endY - startY);
                    endCoeff = (float) (y - startY) / (float) (endY - startY);
                } else {
                    startCoeff = BCSettings.START_COEFFS[y - startY];
                    endCoeff = BCSettings.END_COEFFS[y - startY];
                }
                int o = (y - minHeight) * n;
                for (int i = 0; i < n; ++i) data[o + i] = start[i] * (double) startCoeff + end[i] * (double) endCoeff;
            }
        }
        return col;
    }

    /** dst = a * startCoeff + b * endCoeff, element by element. */
    private static void lerp(double[] dst, double[] a, double[] b, float startCoeff, float endCoeff) {
        for (int i = 0; i < dst.length; ++i) dst[i] = a[i] * (double) startCoeff + b[i] * (double) endCoeff;
    }

    public NoiseCube interpolateNoiseCube(BlockPos startPos, BlockPos endPos, int minHeight, int maxHeight) {
        int startX = startPos.func_177958_n();
        int endX = endPos.func_177958_n();
        int startZ = startPos.func_177952_p();
        int endZ = endPos.func_177952_p();
        int subChunkSize = endX - startX + 1;
        int last = subChunkSize - 1;
        NoiseCube cube = new NoiseCube(subChunkSize, minHeight, maxHeight, this.numGenerators);
        // Real noise at the four corner columns, linear interpolation everywhere else.
        this.fill(cube.column(0, 0), startX, startZ, minHeight, maxHeight);
        this.fill(cube.column(0, last), startX, endZ, minHeight, maxHeight);
        this.fill(cube.column(last, 0), endX, startZ, minHeight, maxHeight);
        this.fill(cube.column(last, last), endX, endZ, minHeight, maxHeight);
        for (int x = 1; x < last; ++x) {
            float startCoeff = BCSettings.START_COEFFS[x];
            float endCoeff = BCSettings.END_COEFFS[x];
            lerp(cube.column(x, 0).data, cube.column(0, 0).data, cube.column(last, 0).data, startCoeff, endCoeff);
            lerp(cube.column(x, last).data, cube.column(0, last).data, cube.column(last, last).data, startCoeff, endCoeff);
        }
        for (int x = 0; x < subChunkSize; ++x) {
            for (int z = 1; z < last; ++z) {
                lerp(cube.column(x, z).data, cube.column(x, 0).data, cube.column(x, last).data,
                        BCSettings.START_COEFFS[z], BCSettings.END_COEFFS[z]);
            }
        }
        return cube;
    }

    public long getSeed() {
        return this.seed;
    }

    private void initializeNoiseGens(boolean isFastNoise) {
        if (isFastNoise) {
            for (int i = 0; i < this.numGenerators; ++i) {
                FastNoise noiseGen = new FastNoise();
                noiseGen.SetSeed((int) this.seed + 1111 * (i + 1));
                noiseGen.SetFractalType(this.noiseSettings.getFractalType());
                noiseGen.SetNoiseType(this.noiseSettings.getNoiseType());
                noiseGen.SetFractalOctaves(this.noiseSettings.getOctaves());
                noiseGen.SetFractalGain(this.noiseSettings.getGain());
                noiseGen.SetFrequency(this.noiseSettings.getFrequency());
                this.listNoiseGens.add(noiseGen);
            }
        } else {
            for (int i = 0; i < this.numGenerators; ++i) {
                OpenSimplex2S noiseGen = new OpenSimplex2S(this.seed + (long) (1111 * (i + 1)));
                noiseGen.setGain(this.noiseSettings.getGain());
                noiseGen.setOctaves(this.noiseSettings.getOctaves());
                noiseGen.setFrequency(this.noiseSettings.getFrequency());
                noiseGen.setLacunarity(2.0);
                this.listNoiseGens.add(noiseGen);
            }
        }
    }
}
