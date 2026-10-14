package com.jdmmc.kurumamod.client;

import java.util.HashSet;
import java.util.Set;

/**
 * ハンドルのメッシュから、回す中心と軸を読む。Minecraft に依存しない（両方の版で共有）。
 *
 * <p><b>OBJ には原点（ピボット）が残らない。</b>Blender でオブジェクトの原点をハンドルの中心に
 * 置いても、書き出されるのは頂点の座標だけなので、回す軸はどこかで与えるしかない。JSON に座標を
 * 書かせるとずれても気づけない（軸から外れて振れながら回る）ので、形から決める。</p>
 *
 * <ul>
 *   <li><b>軸</b> … 頂点のばらつきが<b>いちばん小さい方向</b>（主成分分析）。ハンドルは円盤なので
 *       これが面に垂直な向き＝ステアリングコラムの向きになり、コラムの傾きもそのまま入る。
 *       <b>平均法線を使ってはいけない</b>——表と裏の法線が打ち消し合う（鏡で踏んだのと同じ）</li>
 *   <li><b>中心</b> … 面内で<b>外形の幅の中点</b>。重心は使わない。スポークが 3 本だと重心が
 *       スポークの側へずれ、回すと振れる</li>
 * </ul>
 *
 * <p>円盤と言えない形（細長い・球に近い）なら null を返す。黙って変な軸で回すより、回さない方がよい。</p>
 */
public final class SteeringWheelAxis {

    /**
     * 円盤と見なす薄さ。面に垂直な方向のばらつきが、面内の小さい方のこれ以下。
     *
     * <p>半径 r のリムは面内のばらつきが r²/2、厚みはその数分の 1 以下なので、
     * ボスやコラムカバーが付いていても十分に下回る。</p>
     */
    private static final double MAX_THICKNESS_RATIO = 0.35;

    /** 丸いと見なす縦横比。面内の 2 方向のばらつきの比がこれ以上（D 型のハンドルも通る）。 */
    private static final double MIN_ROUNDNESS = 0.4;

    /**
     * @param center 回す中心（モデルの座標）
     * @param axis   回す軸の単位ベクトル。<b>運転者の側（+Z＝後ろ）へ向けてある</b>。
     *               この軸まわりに負の角度で回すと、運転者から見て時計回り（右へ切る）
     * @param diameter 外形の直径（面内の大きい方）[m]。ログ用
     */
    public record Result(double[] center, double[] axis, double diameter) {
    }

    private SteeringWheelAxis() {
    }

    /**
     * @param positions 頂点の座標 {x, y, z, x, y, z, ...}。同じ点が何度出てきてもよい（数えるのは 1 回）
     * @return 円盤と言えなければ null
     */
    public static Result measure(float[] positions) {
        // 同じ点を 1 回だけ数える。三角形を 4 頂点で持つ（縮退四角形）と 3 番目が 2 回出てくるうえ、
        // 隣り合う面が角を共有するので、そのまま数えると細かく割った所へ重みが寄る
        Set<Long> seen = new HashSet<>();
        double[] sum = new double[3];
        double[] buffer = new double[positions.length];
        int count = 0;
        for (int i = 0; i + 2 < positions.length; i += 3) {
            long key = ((long) Float.floatToIntBits(positions[i]) * 31L + Float.floatToIntBits(positions[i + 1])) * 31L
                    + Float.floatToIntBits(positions[i + 2]);
            if (!seen.add(key)) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                buffer[count * 3 + k] = positions[i + k];
                sum[k] += positions[i + k];
            }
            count++;
        }
        if (count < 4) {
            return null;
        }
        double[] mean = {sum[0] / count, sum[1] / count, sum[2] / count};

        double[][] cov = new double[3][3];
        for (int p = 0; p < count; p++) {
            for (int a = 0; a < 3; a++) {
                double da = buffer[p * 3 + a] - mean[a];
                for (int b = 0; b < 3; b++) {
                    cov[a][b] += da * (buffer[p * 3 + b] - mean[b]);
                }
            }
        }

        double[] values = new double[3];
        double[][] vectors = new double[3][];
        eigen(cov, values, vectors);
        // 小さい順に並べる: 0 が軸、1・2 が面内
        int[] order = {0, 1, 2};
        for (int i = 0; i < 2; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (values[order[j]] < values[order[i]]) {
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                }
            }
        }
        double thin = values[order[0]];
        double mid = values[order[1]];
        double wide = values[order[2]];
        if (wide <= 0.0 || thin > MAX_THICKNESS_RATIO * mid || mid < MIN_ROUNDNESS * wide) {
            return null;
        }

        double[] axis = vectors[order[0]].clone();
        if (axis[2] < 0.0) {
            for (int k = 0; k < 3; k++) {
                axis[k] = -axis[k];
            }
        }

        // 面内の 2 方向それぞれで外形の幅の中点を取る。軸の方向は重心のまま（軸上をずらしても回り方は同じ）
        double[] center = mean.clone();
        double diameter = 0.0;
        for (int n = 1; n <= 2; n++) {
            double[] u = vectors[order[n]];
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < count; p++) {
                double s = 0.0;
                for (int k = 0; k < 3; k++) {
                    s += (buffer[p * 3 + k] - mean[k]) * u[k];
                }
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
            double shift = (min + max) * 0.5;
            for (int k = 0; k < 3; k++) {
                center[k] += u[k] * shift;
            }
            diameter = Math.max(diameter, max - min);
        }
        return new Result(center, axis, diameter);
    }

    /** 対称な 3×3 行列の固有値と固有ベクトル（ヤコビ法）。 */
    private static void eigen(double[][] matrix, double[] values, double[][] vectors) {
        double[][] a = {matrix[0].clone(), matrix[1].clone(), matrix[2].clone()};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) {
                        t = 1.0;
                    }
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            values[i] = a[i][i];
            vectors[i] = new double[]{v[0][i], v[1][i], v[2][i]};
        }
    }
}
