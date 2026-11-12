package com.jdmmc.kurumamod.ground;

import java.util.Arrays;

/**
 * ブロックの列（x, z）ごとの地面の高さを覚えておく表。{@link GroundSensor} が輪を 1 つ調べる間だけ使い、
 * 輪ごとに {@link #clear} する。知らない列は NaN を返す（地面が無い列は負の無限大で覚える）。
 *
 * <p>開番地法の小さな表。1 輪で調べる列は多くても数十なので、増えたら広げるだけで足りる。</p>
 */
final class ColumnTable {

    private long[] keys = new long[64];
    private double[] values = new double[64];
    private boolean[] used = new boolean[64];
    private int size;

    double get(long key) {
        int mask = keys.length - 1;
        for (int i = slot(key, mask); used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) {
                return values[i];
            }
        }
        return Double.NaN;
    }

    void put(long key, double value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
    }

    void clear() {
        if (size > 0) {
            Arrays.fill(used, false);
            size = 0;
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        double[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[oldKeys.length * 2];
        values = new double[oldKeys.length * 2];
        used = new boolean[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
