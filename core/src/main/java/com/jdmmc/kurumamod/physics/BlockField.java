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
    /** 箱を集めた範囲 {minX, minY, minZ, maxX, maxY, maxZ}。null なら範囲を問わない（すべて集めてある）。 */
    private double[] area;
    /** 集めた範囲の外を調べる口。null なら外は空いていることにする。 */
    private Probe outside;
    /**
     * 箱から組み立てたもの（{@code CarBody} の見えない壁）。箱を足したり消したりすると捨てる。
     * 物理の 1 ティックの間（何刻みも）同じ箱を使うので、組み立ては 1 回で済む。
     */
    private Object derived;

    /**
     * 集めた範囲の外の点がブロックの中か調べる口。物理コアは Minecraft を知らないので、
     * {@code CarEntity} がワールドを引いて答える。
     */
    @FunctionalInterface
    public interface Probe {
        boolean solidAt(double x, double y, double z);
    }

    public void clear() {
        boxes.clear();
        cells.clear();
        area = null;
        outside = null;
        derived = null;
    }

    /**
     * 箱を集めた範囲と、その外を調べる口を渡す。<b>{@link #solidAt} だけが範囲の外を口に聞く。</b>
     *
     * <p>階段状の壁で「手前の段が何マス続くか」を数えるとき（{@code CarBody#staircaseNormal}）、
     * 範囲の端で数え終えると、進むたびに範囲がずれて同じ段が 5・4・3 マスと数え直され、
     * ならした壁の傾きが動いた。</p>
     */
    public void setOutside(double[] area, Probe outside) {
        this.area = area;
        this.outside = outside;
        derived = null;
    }

    /** 箱から組み立てて覚えておいたもの。無ければ null。 */
    public Object derived() {
        return derived;
    }

    public void setDerived(Object derived) {
        this.derived = derived;
    }

    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxX - minX <= 1.0e-6 || maxY - minY <= 1.0e-6 || maxZ - minZ <= 1.0e-6) {
            return;
        }
        derived = null;
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
        if (outside != null && area != null && (x < area[0] || y < area[1] || z < area[2]
                || x > area[3] || y > area[4] || z > area[5])) {
            return outside.solidAt(x, y, z);
        }
        return boxAt(x, y, z) != null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
