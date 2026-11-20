package com.yungnickyoung.minecraft.bettercaves.noise;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One block column of noise values in a flat array: data[(y - minY) * n + generator].
 * The carvers read and write {@link #data} directly. put/get keep the old NoiseTuple interface
 * working for anything else that might call it.
 */
public class NoiseColumn {
    public double[] data;
    public int minY;
    public int n;
    private int rows;
    private int min = Integer.MAX_VALUE;
    private int max = Integer.MIN_VALUE;

    public NoiseColumn() {
        this.data = new double[0];
    }

    public NoiseColumn(int minY, int maxY, int n) {
        this.minY = minY;
        this.n = n;
        this.rows = Math.max(0, maxY - minY + 1);
        this.data = new double[this.rows * n];
        if (this.rows > 0) {
            this.min = minY;
            this.max = maxY;
        }
    }

    public void put(int y, NoiseTuple noiseTuple) {
        if (this.n == 0) this.n = noiseTuple.size();
        if (this.rows == 0) {
            this.minY = y;
            this.rows = 1;
            this.data = new double[this.n];
        } else if (y < this.minY) {
            double[] grown = new double[(this.minY + this.rows - y) * this.n];
            System.arraycopy(this.data, 0, grown, (this.minY - y) * this.n, this.rows * this.n);
            this.rows = this.minY + this.rows - y;
            this.minY = y;
            this.data = grown;
        } else if (y >= this.minY + this.rows) {
            this.rows = y - this.minY + 1;
            this.data = Arrays.copyOf(this.data, this.rows * this.n);
        }
        int offset = (y - this.minY) * this.n;
        for (int i = 0; i < this.n; ++i) this.data[offset + i] = noiseTuple.get(i);
        if (y < this.min) this.min = y;
        if (y > this.max) this.max = y;
    }

    public NoiseTuple get(int y) throws IndexOutOfBoundsException {
        if (y < this.min || y > this.max) {
            throw new IndexOutOfBoundsException("No corresponding noise value in NoiseColumn for y-value: " + y);
        }
        int offset = (y - this.minY) * this.n;
        return new NoiseTuple(Arrays.copyOfRange(this.data, offset, offset + this.n));
    }

    public Map<Integer, NoiseTuple> getColumnValues() {
        Map<Integer, NoiseTuple> map = new HashMap<Integer, NoiseTuple>();
        for (int y = this.min; y <= this.max; ++y) map.put(y, this.get(y));
        return map;
    }
}
