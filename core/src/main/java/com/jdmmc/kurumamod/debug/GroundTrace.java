package com.jdmmc.kurumamod.debug;

import com.jdmmc.kurumamod.physics.Wheel;

/**
 * 各輪へ渡した地面を、少し前のぶんまで覚えておく。F3+B（当たり判定の表示）で線として描き、
 * 地面をどう解釈しているか（真下の地面か、階段の線か、補間で縁の高さを決めた線か）を目で見るためのもの。
 *
 * <p>物理を解いたクライアントの車だけが書く。物理の刻みごとに 1 点で、{@link #LENGTH} 点ぶん（2 秒）を輪にして持つ。</p>
 */
public final class GroundTrace {

    /** 覚えておく点の数。1 秒に 80 刻みなので 2 秒ぶん。 */
    public static final int LENGTH = 160;

    private final double[][] x = new double[Wheel.COUNT][LENGTH];
    private final double[][] z = new double[Wheel.COUNT][LENGTH];
    private final double[][] raw = new double[Wheel.COUNT][LENGTH];
    private final double[][] ground = new double[Wheel.COUNT][LENGTH];
    private final GroundLog.Source[][] source = new GroundLog.Source[Wheel.COUNT][LENGTH];
    private final boolean[][] smoothed = new boolean[Wheel.COUNT][LENGTH];
    private final int[] next = new int[Wheel.COUNT];
    private final int[] count = new int[Wheel.COUNT];
    /** いま使っている階段の線。{x1, y1, z1, x2, y2, z2}。無ければ NaN。 */
    private final double[][] stair = new double[Wheel.COUNT][6];
    private final boolean[] stairSmoothed = new boolean[Wheel.COUNT];

    public GroundTrace() {
        for (double[] line : stair) {
            java.util.Arrays.fill(line, Double.NaN);
        }
    }

    /**
     * 1 刻みぶんを足す。
     *
     * @param rawHeight  補間する前の真下の地面。無ければ NaN
     * @param height     渡した地面。無ければ NaN（線がそこで途切れる）
     * @param isSmoothed 縁の高さを補間で決めた階段の線だったか
     */
    public void add(int wheel, double px, double pz, double rawHeight, double height,
                    GroundLog.Source from, boolean isSmoothed) {
        int i = next[wheel];
        x[wheel][i] = px;
        z[wheel][i] = pz;
        raw[wheel][i] = rawHeight;
        ground[wheel][i] = height;
        source[wheel][i] = from;
        smoothed[wheel][i] = isSmoothed;
        next[wheel] = (i + 1) % LENGTH;
        count[wheel] = Math.min(LENGTH, count[wheel] + 1);
    }

    public void setStair(int wheel, double x1, double y1, double z1, double x2, double y2, double z2,
                         boolean isSmoothed) {
        double[] line = stair[wheel];
        line[0] = x1;
        line[1] = y1;
        line[2] = z1;
        line[3] = x2;
        line[4] = y2;
        line[5] = z2;
        stairSmoothed[wheel] = isSmoothed;
    }

    public void clearStair(int wheel) {
        java.util.Arrays.fill(stair[wheel], Double.NaN);
    }

    /** 覚えている点の数。 */
    public int count(int wheel) {
        return count[wheel];
    }

    /** 古いほうから {@code k} 番目の点の添字。 */
    private int index(int wheel, int k) {
        return (next[wheel] - count[wheel] + k + LENGTH) % LENGTH;
    }

    public double x(int wheel, int k) {
        return x[wheel][index(wheel, k)];
    }

    public double z(int wheel, int k) {
        return z[wheel][index(wheel, k)];
    }

    public double raw(int wheel, int k) {
        return raw[wheel][index(wheel, k)];
    }

    public double ground(int wheel, int k) {
        return ground[wheel][index(wheel, k)];
    }

    public GroundLog.Source source(int wheel, int k) {
        return source[wheel][index(wheel, k)];
    }

    public boolean smoothed(int wheel, int k) {
        return smoothed[wheel][index(wheel, k)];
    }

    /** いま使っている階段の線。無ければ先頭が NaN。 */
    public double[] stair(int wheel) {
        return stair[wheel];
    }

    public boolean stairSmoothed(int wheel) {
        return stairSmoothed[wheel];
    }
}
