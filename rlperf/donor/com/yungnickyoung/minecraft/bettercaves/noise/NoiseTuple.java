package com.yungnickyoung.minecraft.bettercaves.noise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Array-backed stand-in for Better Caves' NoiseTuple. The carvers no longer go through it. */
public class NoiseTuple {
    private double[] values;
    private int length;

    public NoiseTuple(double... vals) {
        this.values = vals.length == 0 ? new double[2] : vals.clone();
        this.length = vals.length;
    }

    public void put(double val) {
        if (this.length == this.values.length) this.values = Arrays.copyOf(this.values, Math.max(2, this.length * 2));
        this.values[this.length++] = val;
    }

    public double get(int index) throws IndexOutOfBoundsException {
        if (index < 0 || index >= this.length) {
            throw new IndexOutOfBoundsException("No corresponding noise value in Noise Tuple for index: " + index);
        }
        return this.values[index];
    }

    public void set(int index, double newValue) throws IndexOutOfBoundsException {
        if (index < 0 || index >= this.length) {
            throw new IndexOutOfBoundsException("No corresponding noise value in Noise Tuple for index: " + index);
        }
        this.values[index] = newValue;
    }

    public NoiseTuple times(float magnitude) {
        NoiseTuple result = new NoiseTuple();
        for (int i = 0; i < this.length; ++i) result.put(this.values[i] * (double) magnitude);
        return result;
    }

    public NoiseTuple plus(NoiseTuple other) {
        NoiseTuple result = new NoiseTuple();
        for (int i = 0; i < this.length; ++i) result.put(this.values[i] + other.get(i));
        return result;
    }

    public List<Double> getNoiseValues() {
        List<Double> list = new ArrayList<Double>(this.length);
        for (int i = 0; i < this.length; ++i) list.add(this.values[i]);
        return list;
    }

    public int size() {
        return this.length;
    }
}
