package com.jdmmc.kurumamod.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 多角形を三角形へ割る（耳切り法）。<b>Minecraft に依存しないので、ゲーム抜きで検算できる。</b>
 *
 * <h2>なぜ扇状分割ではいけないか</h2>
 *
 * <p>最初の頂点から扇状に割る（0-1-2, 0-2-3, …）のは<b>凸多角形でしか正しくない</b>。
 * 凹んだ多角形では、多角形の外側にはみ出す三角形ができる。実際に Blender から
 * 書き出した車体は 52 個の n-gon のうち <b>44 個が凹んで</b>いた。</p>
 *
 * <p>「Blender 側で三角形化して書き出す」で回避もできるが、<b>書き出し設定の
 * チェックボックス 1 つに正しさを預けることになる</b>ので、読み込み側で解く。</p>
 *
 * <h2>やり方</h2>
 *
 * <ol>
 *   <li>面の法線を求める（ニューウェル法。頂点が完全な平面上に無くても妥当な向きが出る）</li>
 *   <li>法線の絶対値が最大の軸を捨てて 2 次元へ落とす（面積がいちばん残る向きへ射影される）</li>
 *   <li>反時計回りに揃えてから、耳（他の頂点を含まない三角形）を切り落としていく</li>
 * </ol>
 */
public final class PolygonTriangulator {

    private PolygonTriangulator() {
    }

    /**
     * 多角形を三角形へ割る。
     *
     * @param polygon 頂点の位置。3 個以上
     * @return 三角形ごとの、{@code polygon} に対する添字が 3 つずつ並んだもの
     */
    public static List<int[]> triangulate(List<float[]> polygon) {
        int count = polygon.size();
        List<int[]> triangles = new ArrayList<>(Math.max(0, count - 2));
        if (count < 3) {
            return triangles;
        }
        if (count == 3) {
            triangles.add(new int[]{0, 1, 2});
            return triangles;
        }

        double[][] flat = project(polygon);

        // 添字の輪。耳を切るたびに 1 つ減る
        List<Integer> remaining = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            remaining.add(i);
        }
        // 反時計回りに揃える。時計回りのままだと凸凹の判定が逆になる
        if (signedArea(flat, remaining) < 0.0) {
            java.util.Collections.reverse(remaining);
        }

        // 耳が 1 つも見つからない場合に無限に回らないよう、1 周ぶんで打ち切る
        int guard = 0;
        while (remaining.size() > 3 && guard <= remaining.size()) {
            boolean clipped = false;
            for (int i = 0; i < remaining.size(); i++) {
                int previous = remaining.get((i + remaining.size() - 1) % remaining.size());
                int current = remaining.get(i);
                int next = remaining.get((i + 1) % remaining.size());

                if (!isEar(flat, remaining, previous, current, next)) {
                    continue;
                }
                triangles.add(new int[]{previous, current, next});
                remaining.remove(i);
                clipped = true;
                guard = 0;
                break;
            }
            if (!clipped) {
                guard++;
            }
        }

        // 自己交差などで耳が尽きたら、残りは扇状に割る。
        // 正しくはないが、面が消えるよりは形が残る方がまし
        for (int i = 1; i + 1 < remaining.size(); i++) {
            triangles.add(new int[]{remaining.get(0), remaining.get(i), remaining.get(i + 1)});
        }
        return triangles;
    }

    /** 法線の絶対値が最大の軸を捨てて 2 次元へ落とす。 */
    private static double[][] project(List<float[]> polygon) {
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get((i + 1) % polygon.size());
            nx += (a[1] - b[1]) * (a[2] + b[2]);
            ny += (a[2] - b[2]) * (a[0] + b[0]);
            nz += (a[0] - b[0]) * (a[1] + b[1]);
        }

        double ax = Math.abs(nx);
        double ay = Math.abs(ny);
        double az = Math.abs(nz);

        double[][] flat = new double[polygon.size()][2];
        for (int i = 0; i < polygon.size(); i++) {
            float[] p = polygon.get(i);
            if (ax >= ay && ax >= az) {
                flat[i][0] = p[1];
                flat[i][1] = p[2];
            } else if (ay >= az) {
                flat[i][0] = p[2];
                flat[i][1] = p[0];
            } else {
                flat[i][0] = p[0];
                flat[i][1] = p[1];
            }
        }
        return flat;
    }

    private static double signedArea(double[][] flat, List<Integer> ring) {
        double area = 0.0;
        for (int i = 0; i < ring.size(); i++) {
            double[] a = flat[ring.get(i)];
            double[] b = flat[ring.get((i + 1) % ring.size())];
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2.0;
    }

    /** 出っ張っていて、かつ内側に他の頂点を含まない三角形か。 */
    private static boolean isEar(double[][] flat, List<Integer> ring, int previous, int current, int next) {
        if (cross(flat[previous], flat[current], flat[next]) <= 0.0) {
            return false; // 凹んでいる（反時計回りに揃えてあるので、負なら内側へ食い込む）
        }
        for (int index : ring) {
            if (index == previous || index == current || index == next) {
                continue;
            }
            if (contains(flat[previous], flat[current], flat[next], flat[index])) {
                return false;
            }
        }
        return true;
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /** 点が三角形の内側（辺の上を含む）にあるか。 */
    private static boolean contains(double[] a, double[] b, double[] c, double[] p) {
        double d1 = cross(a, b, p);
        double d2 = cross(b, c, p);
        double d3 = cross(c, a, p);
        boolean negative = d1 < 0.0 || d2 < 0.0 || d3 < 0.0;
        boolean positive = d1 > 0.0 || d2 > 0.0 || d3 > 0.0;
        return !(negative && positive);
    }
}
