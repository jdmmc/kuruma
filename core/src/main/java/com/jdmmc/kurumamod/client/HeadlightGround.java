package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.util.Mth;

import java.util.Arrays;

/**
 * ヘッドライトの路面の照り返しの、<b>どこを照らすか</b>（車の前の扇形のブロック列を調べる・手前から遮蔽を積む・
 * 照らす面を選ぶ）。画素ごとに解く光（{@code HeadlightVolume}）が使えないとき（シェーダーパックなど）の代わり。
 *
 * <p><b>ブロックを読むのと、面をそのブロックのテクスチャで描くのは版の {@code CarLights}</b>（{@link Blocks}・{@link Faces}）。
 * 明るさ（配光）は {@link HeadlightBeam}。</p>
 */
public final class HeadlightGround {

    /** ブロックを読む口。 */
    public interface Blocks {
        /**
         * そのブロックの上面の高さ。当たり判定も液体も無ければ NaN。液体も面として扱い、草や花は素通りする。
         * 見た目の形が 1 ブロックの床いっぱいに広がっているもの（雪の層・道路 MOD の薄い層）は面として数える。
         */
        double surfaceTop(int x, int y, int z);

        /** 壁の当たり判定の水平の範囲 {minX, maxX, minZ, maxZ}（ブロックの中の 0..1）。形が無ければ 1 ブロックいっぱい。 */
        double[] wallBox(int x, int y, int z);
    }

    /** 照らす面。 */
    public enum Side {
        UP, WEST, EAST, NORTH, SOUTH;

        /** 水平の向き (sx, sz)（どちらかが ±1）の面。 */
        static Side of(int sx, int sz) {
            return sx < 0 ? WEST : sx > 0 ? EAST : sz < 0 ? NORTH : SOUTH;
        }
    }

    /** 選んだ面を出す口。 */
    public interface Faces {
        /** ブロックのモデルの面として描く。面が無ければ（水面など）false。 */
        boolean surface(int x, int y, int z, Side side);

        /** テクスチャの無い光だけの四角形を置く（世界の座標）。法線は (nx, ny, nz)。 */
        void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                  float x2, float y2, float z2, float x3, float y3, float z3,
                  double nx, double ny, double nz);
    }

    /** 路面を照らす奥行き [m]。{@link #GROUND_FADE_START} から先で消えていく。 */
    public static final double GROUND_RANGE = 44.0;
    public static final double GROUND_FADE_START = 30.0;
    /** 下を何ブロックまで探すか。これより深い谷底は照らさない。 */
    public static final int GROUND_SCAN_DEPTH = 6;
    /** 壁を上へ何ブロックまで見るか。光はカットオフより上へ行かないので高くは要らない。 */
    public static final int WALL_SCAN_HEIGHT = 2;
    /** 面から浮かせる量 [m]。z ファイティングよけ。 */
    public static final float SURFACE_LIFT = 0.012F;
    /** 壁の面を縦に刻む幅 [m]。カットオフの線が壁にくっきり出るように。 */
    public static final double WALL_SLICE = 0.25;
    /** 遮蔽を調べる角度の刻み（光軸の左右 90 度を何分割するか）。 */
    public static final int HORIZON_BINS = 360;

    private HeadlightGround() {
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * 車の前に広がる扇形の範囲で、ブロックごとの路面の高さを調べたもの。
     *
     * <p><b>1 ティックに 1 度だけ作り直す。</b>世界の形はそうそう変わらないのに対し、
     * 明るさは毎フレーム計算し直す（ライトの位置は補間されて動くので、20Hz で計算すると
     * 照らされた範囲が車からカクカク遅れる）。</p>
     */
    public static final class Field {
        static final byte NONE = 0;
        static final byte FLOOR = 1;
        /** ライトの高さにブロックがある。天面は照らさず、手前の面だけが照らされる。 */
        static final byte WALL = 2;

        /** 最後に作り直したティック。1 ティックに 1 度だけ作り直す。 */
        public int tick = Integer.MIN_VALUE;
        int minX;
        int minZ;
        int sizeX;
        int sizeZ;
        double lampY;
        float[] top = new float[0];
        byte[] kind = new byte[0];
        /** 壁の当たり判定の水平の範囲（フェンスや塀は 1 ブロックいっぱいではない）。 */
        float[] wall = new float[0];
        /** 床のブロックの高さ（Y 座標）。照らした面をそのブロックのテクスチャで描くのに使う。 */
        int[] floorY = new int[0];
        /** 光源に近い順に並べたセルの番号。遮蔽を手前から積むのに使う。 */
        int[] order = new int[0];
        int orderCount;

        int index(int bx, int bz) {
            int ix = bx - minX;
            int iz = bz - minZ;
            if (ix < 0 || iz < 0 || ix >= sizeX || iz >= sizeZ) {
                return -1;
            }
            return iz * sizeX + ix;
        }

        /** その位置の床の高さ。調べていないか床でなければ NaN。 */
        public double floorAt(double x, double z) {
            int i = index(floor(x), floor(z));
            return i >= 0 && kind[i] == FLOOR ? top[i] : Double.NaN;
        }

        /**
         * 作り直す。
         *
         * @param ox    2 灯の真ん中（世界）
         * @param lampY 2 灯の真ん中の高さ
         * @param fx    光軸（世界の向き）の水平成分
         */
        public void rebuild(Blocks blocks, double ox, double oz, double lampY, double fx, double fz) {
            // 範囲は光軸の水平の向きで決める。調べるのはブロックの座標なので世界の向き
            double length = Math.hypot(fx, fz);
            if (length < 1.0E-4) {
                sizeX = sizeZ = 0;
                orderCount = 0;
                return;
            }
            fx /= length;
            fz /= length;
            double rx = -fz;
            double rz = fx;
            this.lampY = lampY;

            // 扇形を囲む箱
            double far = GROUND_RANGE + 2.0;
            double farHalf = halfWidth(GROUND_RANGE) + 2.0;
            double[][] corners = {
                    {ox - fx * 2.0 + rx * 3.0, oz - fz * 2.0 + rz * 3.0},
                    {ox - fx * 2.0 - rx * 3.0, oz - fz * 2.0 - rz * 3.0},
                    {ox + fx * far + rx * farHalf, oz + fz * far + rz * farHalf},
                    {ox + fx * far - rx * farHalf, oz + fz * far - rz * farHalf},
            };
            double lowX = Double.MAX_VALUE;
            double lowZ = Double.MAX_VALUE;
            double highX = -Double.MAX_VALUE;
            double highZ = -Double.MAX_VALUE;
            for (double[] c : corners) {
                lowX = Math.min(lowX, c[0]);
                highX = Math.max(highX, c[0]);
                lowZ = Math.min(lowZ, c[1]);
                highZ = Math.max(highZ, c[1]);
            }
            minX = floor(lowX);
            minZ = floor(lowZ);
            sizeX = floor(highX) - minX + 1;
            sizeZ = floor(highZ) - minZ + 1;
            int cells = sizeX * sizeZ;
            if (top.length < cells) {
                top = new float[cells];
                kind = new byte[cells];
                wall = new float[cells * 4];
                floorY = new int[cells];
                order = new int[cells];
            }
            Arrays.fill(kind, 0, cells, NONE);

            long[] sortable = new long[cells];
            int count = 0;
            int lampBlock = floor(lampY);
            for (int iz = 0; iz < sizeZ; iz++) {
                for (int ix = 0; ix < sizeX; ix++) {
                    int bx = minX + ix;
                    int bz = minZ + iz;
                    double dx = bx + 0.5 - ox;
                    double dz = bz + 0.5 - oz;
                    double f = dx * fx + dz * fz;
                    double l = dx * rx + dz * rz;
                    if (f < -1.5 || f > GROUND_RANGE + 1.0 || Math.abs(l) > halfWidth(Math.max(0.0, f)) + 1.5) {
                        continue;
                    }
                    int i = iz * sizeX + ix;
                    if (!scan(blocks, bx, bz, lampBlock, i)) {
                        continue;
                    }
                    // 距離を上位、番号を下位に詰めて並べ替える（1mm 単位）
                    sortable[count++] = ((long) (Math.sqrt(dx * dx + dz * dz) * 1000.0) << 24) | i;
                }
            }
            Arrays.sort(sortable, 0, count);
            for (int k = 0; k < count; k++) {
                order[k] = (int) (sortable[k] & 0xFFFFFF);
            }
            orderCount = count;
        }

        /**
         * 1 列ぶんの路面を調べる。
         *
         * <p><b>ライトの高さから始める。</b>上から下へ舐めると、トンネルの天井や
         * 高架を「路面」と読んで、その下の道路が照らされなくなる。ライトの高さに
         * ブロックがあれば壁、無ければそこから下へ床を探す。</p>
         */
        private boolean scan(Blocks blocks, int bx, int bz, int lampBlock, int i) {
            double t = blocks.surfaceTop(bx, lampBlock, bz);
            if (!Double.isNaN(t) && t >= lampY - 0.05) {
                // 壁。水平の範囲は当たり判定の箱から取る
                double[] box = blocks.wallBox(bx, lampBlock, bz);
                wall[i * 4] = (float) (bx + box[0]);
                wall[i * 4 + 1] = (float) (bx + box[1]);
                wall[i * 4 + 2] = (float) (bz + box[2]);
                wall[i * 4 + 3] = (float) (bz + box[3]);
                double height = t;
                for (int y = lampBlock + 1; y <= lampBlock + WALL_SCAN_HEIGHT; y++) {
                    double above = blocks.surfaceTop(bx, y, bz);
                    if (Double.isNaN(above)) {
                        break;
                    }
                    height = above;
                }
                top[i] = (float) height;
                kind[i] = WALL;
                return true;
            }
            if (!Double.isNaN(t)) {
                top[i] = (float) t;
                kind[i] = FLOOR;
                floorY[i] = lampBlock;
                return true;
            }
            for (int y = lampBlock - 1; y >= lampBlock - GROUND_SCAN_DEPTH; y--) {
                double below = blocks.surfaceTop(bx, y, bz);
                if (!Double.isNaN(below)) {
                    top[i] = (float) below;
                    kind[i] = FLOOR;
                    floorY[i] = y;
                    return true;
                }
            }
            return false;
        }
    }

    /** 光軸から横へどこまで調べるか [m]。手前は広く、奥は配光の芯のぶんだけ。 */
    public static double halfWidth(double forward) {
        return Math.min(forward * Math.tan(Math.toRadians(60.0)) + 1.5,
                3.5 + forward * Math.tan(Math.toRadians(22.0)));
    }

    /**
     * 路面の照り返しで照らす面を選んで {@code faces} へ出す。何も無ければ false。
     *
     * <p>ブロックごとに<b>上面と、光源を向いている側面</b>を、<b>そのブロックのモデルの面</b>として
     * 明るさ最大で描き直し、頂点ごとに配光から求めた濃さで重ねる（版の {@code LIT_SURFACE}）。
     * 照らされた路面は模様も色もそのまま明るくなる。頂点はブロックの角にあるので、
     * 隣どうしで明るさが途切れない。</p>
     *
     * <h3>遮蔽</h3>
     *
     * <p>光源に近いセルから順に、光軸の左右の角度ごとに「これまでに見えた最も高い仰角」を
     * 積んでいく（地平線）。自分の仰角がそれより低ければ、手前の何かの陰にいる。
     * 平らな地面は奥ほど仰角が上がるので自分の陰にはならず、段差や塀の向こうだけが暗くなる。
     * 光線を 1 本ずつ飛ばすより桁違いに安い。</p>
     */
    public static boolean light(Field field, Faces faces, double mx, double mz, double lampY, double fx, double fz) {
        if (field.orderCount == 0) {
            return false;
        }
        double length = Math.hypot(fx, fz);
        if (length < 1.0E-4) {
            return false;
        }
        fx /= length;
        fz /= length;
        double rx = -fz;
        double rz = fx;

        float[] horizon = new float[HORIZON_BINS];
        Arrays.fill(horizon, Float.NEGATIVE_INFINITY);
        double binScale = HORIZON_BINS / Math.PI;

        for (int k = 0; k < field.orderCount; k++) {
            int i = field.order[k];
            int bx = field.minX + i % field.sizeX;
            int bz = field.minZ + i / field.sizeX;
            double dx = bx + 0.5 - mx;
            double dz = bz + 0.5 - mz;
            double f = dx * fx + dz * fz;
            double l = dx * rx + dz * rz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.4 || f <= 0.0) {
                continue;
            }
            double angle = Math.atan2(l, f);
            int bin = Mth.clamp((int) ((angle + Math.PI / 2.0) * binScale), 0, HORIZON_BINS - 1);
            int spread = Math.max(0, (int) Math.ceil(Math.atan(0.75 / dist) * binScale));
            float seen = horizon[bin];
            double top = field.top[i];

            if (field.kind[i] == Field.FLOOR) {
                if ((top - lampY) / dist >= seen - 1.0E-4
                        && !faces.surface(bx, field.floorY[i], bz, Side.UP)) {
                    // モデルの面が無い（水面など）。テクスチャ無しの光だけ置く
                    float y = (float) (top + SURFACE_LIFT);
                    faces.quad(bx, y, bz, bx + 1, y, bz, bx + 1, y, bz + 1, bx, y, bz + 1,
                            0.0, 1.0, 0.0);
                }
            }

            // 光源を向いている側面。手前（光源側）の隣が低ければ、そのぶんだけ面が見えている
            double faceTop = Math.min(top, lampY + WALL_SCAN_HEIGHT);
            wallFace(field, faces, i, bx, bz, -1, 0, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, faces, i, bx, bz, 1, 0, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, faces, i, bx, bz, 0, -1, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, faces, i, bx, bz, 0, 1, mx, mz, lampY, dist, seen, faceTop);

            float elevation = (float) ((top - lampY) / dist);
            for (int b = Math.max(0, bin - spread); b <= Math.min(HORIZON_BINS - 1, bin + spread); b++) {
                if (elevation > horizon[b]) {
                    horizon[b] = elevation;
                }
            }
        }
        return true;
    }

    /** セル i の、(sx, sz) 側の側面。見えていて照らされていれば縦に刻んで出す。 */
    private static void wallFace(Field field, Faces faces, int i,
                                 int bx, int bz, int sx, int sz, double mx, double mz, double lampY,
                                 double dist, float seen, double faceTop) {
        // 光源を向いていない面は照らされない
        double toLampX = mx - (bx + 0.5);
        double toLampZ = mz - (bz + 0.5);
        if (sx * toLampX + sz * toLampZ <= 0.0) {
            return;
        }
        int neighbour = field.index(bx + sx, bz + sz);
        if (neighbour < 0 || field.kind[neighbour] == Field.NONE
                || field.kind[neighbour] == Field.WALL) {
            return;
        }
        double bottom = field.top[neighbour];
        if (faceTop - bottom < 0.1) {
            return;
        }
        // 面の下端が手前の何かに隠れていれば、面全体を隠れたものとする
        if ((bottom - lampY) / dist < seen - 1.0E-4) {
            return;
        }
        double x0;
        double x1;
        double z0;
        double z1;
        if (field.kind[i] == Field.WALL) {
            x0 = field.wall[i * 4];
            x1 = field.wall[i * 4 + 1];
            z0 = field.wall[i * 4 + 2];
            z1 = field.wall[i * 4 + 3];
        } else {
            x0 = bx;
            x1 = bx + 1;
            z0 = bz;
            z1 = bz + 1;
        }
        // まずはブロックのモデルの面として描く。1 ブロックずつ積む
        Side side = Side.of(sx, sz);
        boolean textured = false;
        for (int y = floor(bottom + 1.0E-3); y <= floor(faceTop - 1.0E-3); y++) {
            textured |= faces.surface(bx, y, bz, side);
        }
        if (textured) {
            return;
        }
        float lift = SURFACE_LIFT;
        int slices = Mth.clamp((int) Math.ceil((faceTop - bottom) / WALL_SLICE), 1, 16);
        for (int s = 0; s < slices; s++) {
            float ya = (float) (bottom + (faceTop - bottom) * s / slices);
            float yb = (float) (bottom + (faceTop - bottom) * (s + 1) / slices);
            if (sx != 0) {
                float x = (float) (sx < 0 ? x0 - lift : x1 + lift);
                faces.quad(x, ya, (float) z0, x, ya, (float) z1, x, yb, (float) z1, x, yb, (float) z0,
                        sx, 0.0, 0.0);
            } else {
                float z = (float) (sz < 0 ? z0 - lift : z1 + lift);
                faces.quad((float) x0, ya, z, (float) x1, ya, z, (float) x1, yb, z, (float) x0, yb, z,
                        0.0, 0.0, sz);
            }
        }
    }
}
