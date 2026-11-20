package com.yungnickyoung.minecraft.bettercaves.noise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A square of noise columns, stored as a plain 2D array. */
public class NoiseCube {
    private final NoiseColumn[][] columns;
    private final List<List<NoiseColumn>> cubeValues = new ArrayList<List<NoiseColumn>>();
    private final int length;

    public NoiseCube(int edgeLength) {
        this(edgeLength, 0, -1, 0);
    }

    public NoiseCube(int edgeLength, int minY, int maxY, int n) {
        this.length = edgeLength;
        this.columns = new NoiseColumn[edgeLength][edgeLength];
        for (int x = 0; x < edgeLength; ++x) {
            for (int z = 0; z < edgeLength; ++z) {
                this.columns[x][z] = n == 0 ? new NoiseColumn() : new NoiseColumn(minY, maxY, n);
            }
            this.cubeValues.add(Arrays.asList(this.columns[x]));
        }
    }

    public List<NoiseColumn> get(int index) throws IndexOutOfBoundsException {
        if (index < 0 || index >= this.length) {
            throw new IndexOutOfBoundsException("No corresponding noise value in Noise Tuple for index: " + index);
        }
        return this.cubeValues.get(index);
    }

    NoiseColumn column(int x, int z) {
        return this.columns[x][z];
    }
}
