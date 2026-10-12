package com.jdmmc.kurumamod.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 車の周りのブロックの当たり判定の箱（ワールド座標の直方体）。
 *
 * <p>物理コアは Minecraft を知らないので、{@code CarEntity} がブロックの形を箱にして渡す。
 * ゲーム外の実験では好きな地形を箱で組めばよい。箱は置いたブロックの単位で登録し、
 * どの 1m のマスに掛かっているかで引けるようにしてある（点がどの箱の中にあるかを速く調べるため）。</p>
 */
public final class BlockField {

    /** 箱。{minX, minY, minZ, maxX, maxY, maxZ}。 */
    private final List<double[]> boxes = new ArrayList<>();
    /** マス（x, y, z の整数）→ そのマスに掛かっている箱の番号。 */
    private final Map<Long, List<Integer>> cells = new HashMap<>();

    public void clear() {
        boxes.clear();
        cells.clear();
    }

    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxX - minX <= 1.0e-6 || maxY - minY <= 1.0e-6 || maxZ - minZ <= 1.0e-6) {
            return;
        }
        int index = boxes.size();
        boxes.add(new double[]{minX, minY, minZ, maxX, maxY, maxZ});
        for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX - 1.0e-9); x++) {
            for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY - 1.0e-9); y++) {
                for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ - 1.0e-9); z++) {
                    cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>(2)).add(index);
                }
            }
        }
    }

    public List<double[]> boxes() {
        return boxes;
    }

    /** 点を含む箱。無ければ null。 */
    public double[] boxAt(double x, double y, double z) {
        List<Integer> list = cells.get(key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        if (list == null) {
            return null;
        }
        for (int index : list) {
            double[] b = boxes.get(index);
            if (x > b[0] && x < b[3] && y > b[1] && y < b[4] && z > b[2] && z < b[5]) {
                return b;
            }
        }
        return null;
    }

    public boolean solidAt(double x, double y, double z) {
        return boxAt(x, y, z) != null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
