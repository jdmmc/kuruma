package com.jdmmc.kurumamod.client;

import java.util.HashSet;
import java.util.Set;

/**
 * ハンドルのメッシュから、回す中心と軸を読む。Minecraft に依存しない（両方の版で共有）。
 *
 * <p><b>OBJ には原点（ピボット）が残らない。</b>Blender でオブジェクトの原点をハンドルの中心に
 * 置いても、書き出されるのは頂点の座標だけなので、回す軸はどこかで与えるしかない。JSON に座標を
 * 書かせるとずれても気づけない（軸から外れて振れながら回る）ので、形から決める。
 * 形から決められないときだけ、コラムの角度を JSON に書いてもらう（{@link #around}。中心はそれでも形から読む）。</p>
 *
 * <ul>
 *   <li><b>軸</b> … 頂点のばらつきが<b>いちばん小さい方向</b>（主成分分析）。ハンドルは平たい板なので
 *       これが面に垂直な向き＝ステアリングコラムの向きになり、コラムの傾きもそのまま入る。
 *       <b>平均法線を使ってはいけない</b>——表と裏の法線が打ち消し合う（鏡で踏んだのと同じ）</li>
 *   <li><b>中心</b> … 面内で<b>外形の幅の中点</b>。重心は使わない。スポークが 3 本だと重心が
 *       スポークの側へずれ、回すと振れる</li>
 * </ul>
 *
 * <p>平たいと言えない形（棒・球に近い・コラムごとの塊）なら null を返す。黙って変な軸で回すより、回さない方がよい。</p>
 *
 * <p><b>丸さは問わない。</b>以前は面内の縦横比も見ていたが、F1 のような横長のハンドルが弾かれて回らなかった。
 * 軸を決めるのは薄さだけで、板であれば横長でも面に垂直な向きははっきり決まる。中心も外形の幅の中点なので
 * 形に依らない。棒は面内の短い方が厚みと変わらないので、薄さの判定で今までどおり弾かれる。</p>
 */
public final class SteeringWheelAxis {

    /**
     * 平たい板と見なす薄さ。面に垂直な方向のばらつきが、面内の小さい方のこれ以下。
     *
     * <p>半径 r のリムは面内のばらつきが r²/2、厚みはその数分の 1 以下なので、
     * ボスやコラムカバーが付いていても十分に下回る。</p>
     */
    private static final double MAX_THICKNESS_RATIO = 0.35;

    /**
     * @param center 回す中心（モデルの座標）
     * @param axis   回す軸の単位ベクトル。<b>運転者の側（+Z＝後ろ）へ向けてある</b>。
     *               この軸まわりに負の角度で回すと、運転者から見て時計回り（右へ切る）
     * @param diameter 外形の幅（面内の大きい方）[m]。手を置く半径に使う
     */
    public record Result(double[] center, double[] axis, double diameter) {

        /**
         * 中心を面に沿って上へずらしたもの（{@code steering.center}）。外形の幅の中点が本当の中心と
         * ずれる形（下が平らな D 型・上にだけ画面が付いた形）を、揺れが止まるところまで合わせるため。
         *
         * @param up 上へずらす量（モデルの座標、m）。負で下
         */
        public Result raised(double up) {
            if (up == 0.0) {
                return this;
            }
            // 面内の上 = 世界の上を面へ落としたもの。軸が真上を向いた（面が水平の）ときは前（-Z）を上とする
            double ux = -axis[0] * axis[1];
            double uy = 1.0 - axis[1] * axis[1];
            double uz = -axis[2] * axis[1];
            double length = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (length < 1.0e-6) {
                ux = 0.0;
                uy = 0.0;
                uz = -1.0;
                length = 1.0;
            }
            double k = up / length;
            return new Result(new double[]{center[0] + ux * k, center[1] + uy * k, center[2] + uz * k},
                    axis, diameter);
        }
    }

    private SteeringWheelAxis() {
    }

    /**
     * @param positions 頂点の座標 {x, y, z, x, y, z, ...}。同じ点が何度出てきてもよい（数えるのは 1 回）
     * @return 平たいと言えなければ null
     */
    public static Result measure(float[] positions) {
        double[] buffer = new double[positions.length];
        int count = unique(positions, buffer);
        if (count < 4) {
            return null;
        }
        double[] mean = mean(buffer, count);

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
        if (wide <= 0.0 || thin > MAX_THICKNESS_RATIO * mid) {
            return null;
        }

        double[] axis = vectors[order[0]].clone();
        if (axis[2] < 0.0) {
            for (int k = 0; k < 3; k++) {
                axis[k] = -axis[k];
            }
        }
        return centered(buffer, count, mean, axis, vectors[order[1]], vectors[order[2]]);
    }

    /**
     * コラムの角度を与えて、中心だけを形から読む。形からは軸が決まらないハンドル（ボタンや画面が厚い
     * F1 のハンドルなど）でも回せるように、{@code vehicles/<車>.json} の {@code steering.column} で書く。
     *
     * <p>角度は<b>水平から、運転者へ向かってコラムが上がる向きが正</b>。0 で面が垂直（F1）、
     * 90 で面が水平（バス）。左右には傾けない。S15 のハンドルを {@link #measure} で測ると 25 度になる。
     * 座標は {@code ObjModel} が読み込んだあと（X と Z を反転済み、+Z が後ろ）。OBJ ファイルの座標のままでは
     * 前後が逆になり、角度の符号も逆に出る。</p>
     *
     * @param columnDegrees コラムの角度 [度]
     * @return 頂点が無ければ null
     */
    public static Result around(float[] positions, double columnDegrees) {
        double[] buffer = new double[positions.length];
        int count = unique(positions, buffer);
        if (count < 1) {
            return null;
        }
        double e = Math.toRadians(columnDegrees);
        // measure と同じく運転者の側（+Z）へ向けた軸で返す
        double[] axis = {0.0, Math.sin(e), Math.cos(e)};
        // 面内は左右と、面に沿った上。左右対称のハンドルならこれで外形の幅の中点が中心に来る
        double[] side = {1.0, 0.0, 0.0};
        double[] up = {0.0, Math.cos(e), -Math.sin(e)};
        return centered(buffer, count, mean(buffer, count), axis, side, up);
    }

    /** 同じ点を 1 回だけ buffer へ詰め、点の数を返す。 */
    private static int unique(float[] positions, double[] buffer) {
        // 三角形を 4 頂点で持つ（縮退四角形）と 3 番目が 2 回出てくるうえ、
        // 隣り合う面が角を共有するので、そのまま数えると細かく割った所へ重みが寄る
        Set<Long> seen = new HashSet<>();
        int count = 0;
        for (int i = 0; i + 2 < positions.length; i += 3) {
            long key = ((long) Float.floatToIntBits(positions[i]) * 31L + Float.floatToIntBits(positions[i + 1])) * 31L
                    + Float.floatToIntBits(positions[i + 2]);
            if (!seen.add(key)) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                buffer[count * 3 + k] = positions[i + k];
            }
            count++;
        }
        return count;
    }

    private static double[] mean(double[] buffer, int count) {
        double[] sum = new double[3];
        for (int p = 0; p < count; p++) {
            for (int k = 0; k < 3; k++) {
                sum[k] += buffer[p * 3 + k];
            }
        }
        return new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    /**
     * 面内の 2 方向それぞれで外形の幅の中点を取り、中心にする。軸の方向は重心のまま
     * （軸上をずらしても回り方は同じ）。
     */
    private static Result centered(double[] buffer, int count, double[] mean, double[] axis,
                                   double[] u1, double[] u2) {
        double[] center = mean.clone();
        double diameter = 0.0;
        for (double[] u : new double[][]{u1, u2}) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < count; p++) {
                double d = 0.0;
                for (int k = 0; k < 3; k++) {
                    d += (buffer[p * 3 + k] - mean[k]) * u[k];
                }
                min = Math.min(min, d);
                max = Math.max(max, d);
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
