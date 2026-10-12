package com.jdmmc.kurumamod.physics;

import java.util.List;

/**
 * 車体の当たり判定。車の向き・ピッチ・ロールに合わせて回した箱（OBB）。
 *
 * <p><b>バニラの当たり判定の箱は回らない。</b>車が傾いても箱は水平のまま、車の向きにも
 * 回らないので、段々の地形で傾いた車の箱の角が段に刺さって止まっていた。そこで地形と
 * 他の車との衝突はこの箱で自分で解き、バニラの箱は「この箱の内側に収まる水平な箱」
 * （{@link #inscribedBox}）にしてある——サーバーの {@code handleMoveVehicle} は
 * バニラの箱で「動いた先が塞がっていないか」を見るので、こちらが通した位置を
 * 弾かないためには内側に収めておく必要がある。</p>
 *
 * <p><b>箱の下端はタイヤが乗り越えられる高さ（{@link CarSpec#maxClimbStep()}）。</b>
 * それより低いものはタイヤの接地（サスペンション）が受け持ち、乗り越えるか壁として
 * 止まるかを決めている。箱が低いところまで覆うと、タイヤが登れる 1 ブロックの段差に
 * 先に車体が当たって止まる。箱は「タイヤが登れない高さのもの」だけを止める。</p>
 *
 * <p><b>車が立っている間、押すのは壁（箱の中心の高さをまたぐもの）を横へと、天井を下へだけ。</b>
 * 上へも押せると、壁の天面に車体の角が掛かった瞬間に押し上げられて壁をよじ登る。
 * <b>腹の下のもの（天面が箱の中心より下）は押さない</b>——地面に乗るのはタイヤの仕事。
 * 横へ押すと向きがティックごとに入れ替わって震え、上へ押すと車を平行に持ち上げるだけで
 * 傾きを止める力にならず、1 輪を穴へ落としたときに穴の縁に掛かった角を支点に車が回って
 * 立ち上がった。
 * 大きく傾いた（横転した）車は向きを問わず押し出す——横倒しや裏返しの車を
 * 支えるのはこの箱しかない。</p>
 *
 * <p><b>車同士は別の箱（{@link #body}）で当てる。</b>地形用の箱は屋根の高さにしか無く
 * （下はタイヤの領分）前後も短いので、そのまま車同士に使うと、箱が触れた時点で
 * バンパーどうしはもう深くめり込んでいた。車同士の箱は見た目の車体に合わせて、
 * 地面の少し上から屋根まで、前後もタイヤの外周から {@link #BODY_OVERHANG} まで覆う。
 * 2 つの箱は一緒に動く（{@link #move}）。</p>
 *
 * <p>Minecraft に依存しない（ブロックの形は {@code double[6]} の箱で受け取る）ので、
 * {@code javac} に通してゲーム抜きで検算できる。</p>
 */
public final class CarHull {

    /** タイヤの外周から車体の端までの張り出し [m]。前後とも。 */
    public static final double OVERHANG = 0.30;
    /** 車同士の箱の、タイヤの外周から車体の端までの張り出し [m]。見た目の車体に合わせる。 */
    public static final double BODY_OVERHANG = 0.60;
    /** 車体の幅のトレッドに対する比。 */
    public static final double WIDTH_RATIO = 0.90;
    /** 箱の厚みの下限 [m]。 */
    public static final double MIN_THICKNESS = 0.40;
    /** 箱の上端の、停車時のシャシー基準面からの高さの下限 [m]。 */
    public static final double MIN_TOP = 0.80;

    /** これより深く食い込んでいるものは押し出さない [m]。埋まった車を遠くへ飛ばさないため。 */
    public static final double MAX_PUSH = 0.6;
    /** 1 回に動かす量の上限 [m]。これより大きい動きは刻んで解く（薄い壁のすり抜け防止）。 */
    private static final double SUBSTEP = 0.25;
    private static final int MAX_SUBSTEPS = 16;
    /** 1 刻みで押し出しを繰り返す回数。角に挟まったときに収まるまで。 */
    private static final int ITERATIONS = 4;
    /**
     * 箱の上向きの Y 成分がこれより小さければ「横転している」とみなし、上下にも押し出す。
     * 0.5 は 60 度。
     */
    private static final double UPRIGHT_LIMIT = 0.5;
    private static final double EPSILON = 1.0e-9;

    /** 中心（ワールド座標）。 */
    private final double[] center = new double[3];
    /** 作ったときのエンティティの位置（重心のシャシー基準面）。{@link #worldPoint} の原点。 */
    private final double[] origin = new double[3];
    /** 軸。0 前、1 上、2 右（ワールド座標の単位ベクトル）。 */
    private final double[][] axes = new double[3][3];
    /** 半分の大きさ。前後・上下・左右。 */
    private final double[] half = new double[3];

    private CarHull() {
    }

    /**
     * 車の姿勢から箱を作る。
     *
     * @param x     エンティティの位置（重心のシャシー基準面）
     * @param yaw   ヨー [rad]（{@link CarState#yaw} と同じ向き）
     * @param pitch ピッチ [rad]。正で鼻上げ
     * @param roll  ロール [rad]。正で右が下がる
     */
    public static CarHull of(CarSpec spec, double x, double y, double z,
                             double yaw, double pitch, double roll) {
        // 車体基準の範囲。前後は前後輪の外周から張り出しぶん、上下は登れる高さから上
        double front = spec.wheelForwardOffset(Wheel.FRONT_LEFT) + spec.wheelRadius() + OVERHANG;
        double rear = spec.wheelForwardOffset(Wheel.REAR_LEFT) - spec.wheelRadius() - OVERHANG;
        double bottom = bottom(spec);
        double top = Math.max(bottom + MIN_THICKNESS, MIN_TOP);
        return build(x, y, z, yaw, pitch, roll, front, rear, bottom, top, halfWidth(spec));
    }

    /** 地形用の箱の底の、シャシー基準面からの高さ [m]。停車時の接地面からタイヤが登れる高さ。 */
    public static double bottom(CarSpec spec) {
        return spec.maxClimbStep() - spec.staticRideHeight();
    }

    /** 車体基準の点（前後・上下・左右）のワールド座標。 */
    public double[] worldPoint(double forward, double up, double right) {
        double[] p = new double[3];
        for (int k = 0; k < 3; k++) {
            p[k] = origin[k] + axes[0][k] * forward + axes[1][k] * up + axes[2][k] * right;
        }
        return p;
    }

    /**
     * 車同士の当たりに使う箱。上下は車体（{@link CarBody}）と同じで、地上高（調整画面）から屋根まで。
     * 前後はタイヤの外周から {@link #BODY_OVERHANG} まで。
     */
    public static CarHull body(CarSpec spec, double x, double y, double z,
                               double yaw, double pitch, double roll) {
        return body(spec, spec, x, y, z, yaw, pitch, roll);
    }

    /**
     * 車同士の箱。大きさは車種の元の諸元（{@code shape}）で決め、前後の位置だけいまの諸元に合わせる
     * （{@link CarBody} と同じ。調整画面でタイヤ半径などを動かしても見た目の車体は変わらないため）。
     *
     * <p><b>下端は地上高に合わせて上げること。</b>バニラの箱はこの箱の内側に作り、サーバーの
     * {@code handleMoveVehicle} はそれで「動いた先が塞がっていないか」を見る。以前は下端を
     * 25cm に固定していたので、地上高を 120cm にして車体が 1 ブロックの段を越えられても、
     * バニラの箱が段に入ってサーバーに引き戻され、一瞬止まっていた。</p>
     */
    public static CarHull body(CarSpec spec, CarSpec shape, double x, double y, double z,
                               double yaw, double pitch, double roll) {
        double middle = spec.axleMidpointOffset();
        double half = shape.wheelBase() / 2.0 + shape.wheelRadius() + BODY_OVERHANG;
        double bottom = CarBody.bottomOf(spec, shape);
        double top = CarBody.topOf(shape);
        return build(x, y, z, yaw, pitch, roll, middle + half, middle - half, bottom, top, halfWidth(shape));
    }

    private static double halfWidth(CarSpec spec) {
        return spec.trackWidth() * WIDTH_RATIO / 2.0;
    }

    /** 車体基準の範囲（前後・上下・左右の半分）から、姿勢を掛けた箱を作る。 */
    private static CarHull build(double x, double y, double z, double yaw, double pitch, double roll,
                                 double front, double rear, double bottom, double top, double halfWidth) {
        CarHull hull = new CarHull();
        double[] f0 = {-Math.sin(yaw), 0.0, Math.cos(yaw)};
        double[] r0 = {-Math.cos(yaw), 0.0, -Math.sin(yaw)};
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        // ピッチ（右軸まわり）→ ロール（前軸まわり）。CarState#hardpointHeight と同じ向き
        double[] f = {f0[0] * cp, sp, f0[2] * cp};
        double[] u1 = {-f0[0] * sp, cp, -f0[2] * sp};
        double[] r = {r0[0] * cr - u1[0] * sr, -u1[1] * sr, r0[2] * cr - u1[2] * sr};
        double[] u = {u1[0] * cr + r0[0] * sr, u1[1] * cr, u1[2] * cr + r0[2] * sr};
        hull.axes[0] = f;
        hull.axes[1] = u;
        hull.axes[2] = r;
        hull.half[0] = (front - rear) / 2.0;
        hull.half[1] = (top - bottom) / 2.0;
        hull.half[2] = halfWidth;
        double cf = (front + rear) / 2.0;
        double cu = (top + bottom) / 2.0;
        hull.origin[0] = x;
        hull.origin[1] = y;
        hull.origin[2] = z;
        hull.center[0] = x + f[0] * cf + u[0] * cu;
        hull.center[1] = y + f[1] * cf + u[1] * cu;
        hull.center[2] = z + f[2] * cf + u[2] * cu;
        return hull;
    }

    public double centerX() {
        return center[0];
    }

    public double centerY() {
        return center[1];
    }

    public double centerZ() {
        return center[2];
    }

    /** 箱を包むワールド軸の箱。{minX, minY, minZ, maxX, maxY, maxZ}。 */
    public double[] bounds() {
        double[] box = new double[6];
        for (int k = 0; k < 3; k++) {
            double extent = 0.0;
            for (int i = 0; i < 3; i++) {
                extent += half[i] * Math.abs(axes[i][k]);
            }
            box[k] = center[k] - extent;
            box[k + 3] = center[k] + extent;
        }
        return box;
    }

    /**
     * この箱の内側に収まる水平な箱。{minX, minY, minZ, maxX, maxY, maxZ}。
     *
     * <p>バニラの当たり判定の箱に使う。<b>内側に収まっていれば、この箱が地形に
     * 当たっていない限りバニラの箱も当たらない</b>ので、サーバーの移動の検査に弾かれない。</p>
     *
     * <p>水平な箱の角が 8 つとも内側にあれば箱ごと内側にある（凸なので）。角が内側にある
     * 条件は軸ごとに {@code a|ax| + b|ay| + d|az| ≤ 半分の大きさ} という一次式になるので、
     * 形（横・縦・横の比）を決めて倍率だけ解く。傾くほど小さくなる。</p>
     *
     * @param margin 内側へ余らせる量 [m]。サーバーが知っている姿勢は少し遅れるため
     */
    public double[] inscribedBox(double margin) {
        double[] shape = {1.0, half[1] / half[2], 1.0};
        double scale = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double need = shape[0] * Math.abs(axes[i][0]) + shape[1] * Math.abs(axes[i][1])
                    + shape[2] * Math.abs(axes[i][2]);
            double room = Math.max(half[i] - margin, 0.01);
            scale = Math.min(scale, room / need);
        }
        double[] box = new double[6];
        for (int k = 0; k < 3; k++) {
            double h = Math.max(shape[k] * scale, 0.02);
            box[k] = center[k] - h;
            box[k + 3] = center[k] + h;
        }
        return box;
    }

    /** 横転している（上下にも押し出す）か。 */
    public boolean tumbled() {
        return axes[1][1] < UPRIGHT_LIMIT;
    }

    private void translate(double dx, double dy, double dz) {
        center[0] += dx;
        center[1] += dy;
        center[2] += dz;
    }

    /** この箱を軸 a へ投影したときの半分の長さ。 */
    private double radius(double[] a) {
        return half[0] * Math.abs(dot(axes[0], a)) + half[1] * Math.abs(dot(axes[1], a))
                + half[2] * Math.abs(dot(axes[2], a));
    }

    // ------------------------------------------------------------------
    // 押し出し
    // ------------------------------------------------------------------

    /**
     * 押し出し 1 つ。{@code (x, y, z)} だけ動かせば離れる。{@code nx, ny, nz} は押す向き
     * （相手からこちらへ）の単位ベクトル。
     */
    public record Push(double x, double y, double z, double nx, double ny, double nz, double depth) {
    }

    /**
     * ワールド軸の箱との押し出し。離れていれば null。
     *
     * @param box {minX, minY, minZ, maxX, maxY, maxZ}
     */
    public Push against(double[] box) {
        double[] otherCenter = {
                (box[0] + box[3]) / 2.0, (box[1] + box[4]) / 2.0, (box[2] + box[5]) / 2.0};
        double[] otherHalf = {
                (box[3] - box[0]) / 2.0, (box[4] - box[1]) / 2.0, (box[5] - box[2]) / 2.0};
        double[][] otherAxes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        boolean tumbled = tumbled();
        if (!tumbled && box[4] < center[1]) {
            // 腹の下のもの（天面が箱の中心より下）は、立っている間は押さない。地面に乗るのは
            // タイヤの仕事。押し上げても車を平行に持ち上げるだけで傾きを止める力にならず、
            // 1 輪を穴へ落としたとき、穴の縁に掛かった箱の角を支点に車が回って立ち上がった。
            // 横へ押すと、向きがティックごとに入れ替わって震える
            return null;
        }
        // 天井（箱の中心より上にあるもの）は下へ押す。中心の高さをまたぐもの（壁）は横だけ
        int lift = box[1] > center[1] ? -1 : 0;
        return push(otherCenter, otherAxes, otherHalf, tumbled, lift);
    }

    /** 他の車の箱との押し出し。水平方向だけ。離れていれば null。 */
    public Push against(CarHull other) {
        return push(other.center, other.axes, other.half, false, 0);
    }

    /**
     * 分離軸の定理で重なりを調べ、重なっていれば押し出す向きと量を選ぶ。
     *
     * <p><b>重なっているかは 15 本の軸すべてで見るが、押し出す向きは限る</b>
     * （{@code vertical} が偽なら水平と、{@code lift} の向きの上下だけ）。どの向きでも「その軸への投影が重ならなく
     * なるまで」動かせば離れるので、限った中でいちばん浅いものを選べばよい。</p>
     */
    private Push push(double[] otherCenter, double[][] otherAxes, double[] otherHalf,
                      boolean vertical, int lift) {
        double[] d = {center[0] - otherCenter[0], center[1] - otherCenter[1], center[2] - otherCenter[2]};

        // 離れているか（15 本）
        for (int i = 0; i < 3; i++) {
            if (separated(axes[i], d, otherAxes, otherHalf) || separated(otherAxes[i], d, otherAxes, otherHalf)) {
                return null;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double[] a = cross(axes[i], otherAxes[j]);
                double length = Math.sqrt(dot(a, a));
                if (length < 1.0e-6) {
                    continue;
                }
                a[0] /= length;
                a[1] /= length;
                a[2] /= length;
                if (separated(a, d, otherAxes, otherHalf)) {
                    return null;
                }
            }
        }

        // 押し出す向きの候補
        double[][] candidates = new double[10][];
        int count = 0;
        candidates[count++] = new double[]{1, 0, 0};
        candidates[count++] = new double[]{0, 0, 1};
        count = addHorizontal(candidates, count, axes[0]);
        count = addHorizontal(candidates, count, axes[2]);
        count = addHorizontal(candidates, count, otherAxes[0]);
        count = addHorizontal(candidates, count, otherAxes[2]);
        if (vertical) {
            candidates[count++] = new double[]{0, 1, 0};
            candidates[count++] = axes[1];
        } else if (lift != 0) {
            candidates[count++] = new double[]{0, 1, 0};
        }

        double[] best = null;
        double bestDepth = Double.MAX_VALUE;
        double bestSign = 1.0;
        for (int n = 0; n < count; n++) {
            double[] a = candidates[n];
            double distance = dot(d, a);
            double depth = radius(a) + otherRadius(a, otherAxes, otherHalf) - Math.abs(distance);
            double sign = distance >= 0.0 ? 1.0 : -1.0;
            if (lift != 0 && !vertical && a[1] > 0.5) {
                // 天井は下へ、腹の下の出っ張りは上へしか押さない
                sign = lift;
                depth = lift < 0
                        ? center[1] + radius(a) - (otherCenter[1] - otherRadius(a, otherAxes, otherHalf))
                        : otherCenter[1] + otherRadius(a, otherAxes, otherHalf) - (center[1] - radius(a));
            }
            if (depth < bestDepth) {
                bestDepth = depth;
                best = a;
                bestSign = sign;
            }
        }
        if (best == null || bestDepth <= 0.0) {
            return null;
        }
        double nx = best[0] * bestSign;
        double ny = best[1] * bestSign;
        double nz = best[2] * bestSign;
        return new Push(nx * bestDepth, ny * bestDepth, nz * bestDepth, nx, ny, nz, bestDepth);
    }

    private boolean separated(double[] a, double[] d, double[][] otherAxes, double[] otherHalf) {
        return Math.abs(dot(d, a)) >= radius(a) + otherRadius(a, otherAxes, otherHalf) - EPSILON;
    }

    private static double otherRadius(double[] a, double[][] otherAxes, double[] otherHalf) {
        return otherHalf[0] * Math.abs(dot(otherAxes[0], a)) + otherHalf[1] * Math.abs(dot(otherAxes[1], a))
                + otherHalf[2] * Math.abs(dot(otherAxes[2], a));
    }

    /** 軸を水平へ倒して候補に足す。ほとんど縦を向いている軸は足さない。 */
    private static int addHorizontal(double[][] candidates, int count, double[] axis) {
        double length = Math.hypot(axis[0], axis[2]);
        if (length < 0.2) {
            return count;
        }
        candidates[count] = new double[]{axis[0] / length, 0.0, axis[2] / length};
        return count + 1;
    }

    // ------------------------------------------------------------------
    // 動かす
    // ------------------------------------------------------------------

    /** 動かした結果。 */
    public static final class Motion {
        /** 動いた量（ワールド座標）。 */
        public double x;
        public double y;
        public double z;
        /** 横から当たったか（壁・他の車）。 */
        public boolean horizontal;
        /** 上下で当たったか（横転して地面に着いた・天井）。 */
        public boolean vertical;
        /**
         * 地形から押し出された向き（水平の単位ベクトル、壁からこちらへ）。押し出しの合計の向き。
         * 地形に横から当たっていなければ 0。
         */
        public double wallNormalX;
        public double wallNormalZ;
        /** 地形に横から当たった点（水平、ワールド座標）。押し出しの量で重み付けした平均。 */
        public double wallContactX;
        public double wallContactZ;
        private double wallWeight;
        /** 当たった車の番号（{@code cars} の）。当たっていなければ -1。いちばん深く当たったもの。 */
        public int car = -1;
        /** 当たった車へ向かう水平の単位ベクトル。 */
        public double carNormalX;
        public double carNormalZ;
        /** 当たった車との接触点（ワールド座標）。 */
        public double contactX;
        public double contactZ;
        private double carDepth;
    }

    /**
     * 箱を {@code (dx, dy, dz)} だけ動かし、地形と他の車から押し出す。
     *
     * <p>大きな動きは {@link #SUBSTEP} ずつに刻む（1 ティックで 2.75m 進むので、
     * まとめて動かすと薄い壁の向こうへ抜けた位置から押し出してしまう）。</p>
     *
     * <p>この箱（地形用）と {@code body}（車同士用、{@link #body}）を一緒に動かす。
     * どちらかが押し出されたら、もう一方も同じだけ動く。</p>
     *
     * @param blocks 地形の箱（{minX, minY, minZ, maxX, maxY, maxZ}）。動く範囲を覆っていること
     * @param body   自分の車同士用の箱
     * @param cars   当たる相手の車同士用の箱
     */
    public Motion move(double dx, double dy, double dz, List<double[]> blocks, CarHull body, List<CarHull> cars) {
        Motion motion = new Motion();
        double length = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        int steps = Math.max(1, Math.min(MAX_SUBSTEPS, (int) Math.ceil(length / SUBSTEP)));
        double startX = center[0];
        double startY = center[1];
        double startZ = center[2];
        for (int step = 0; step < steps; step++) {
            translate(dx / steps, dy / steps, dz / steps);
            body.translate(dx / steps, dy / steps, dz / steps);
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                boolean pushed = false;
                for (double[] box : blocks) {
                    Push push = against(box);
                    if (push == null || push.depth() > MAX_PUSH) {
                        continue;
                    }
                    translate(push.x(), push.y(), push.z());
                    body.translate(push.x(), push.y(), push.z());
                    pushed = true;
                    if (Math.abs(push.ny()) > 0.5) {
                        motion.vertical = true;
                    } else {
                        motion.horizontal = true;
                        motion.wallNormalX += push.x();
                        motion.wallNormalZ += push.z();
                        double[] contact = contact(box);
                        motion.wallContactX += contact[0] * push.depth();
                        motion.wallContactZ += contact[1] * push.depth();
                        motion.wallWeight += push.depth();
                    }
                }
                for (int n = 0; n < cars.size(); n++) {
                    CarHull other = cars.get(n);
                    Push push = body.against(other);
                    if (push == null || push.depth() > MAX_PUSH) {
                        continue;
                    }
                    if (push.depth() > motion.carDepth) {
                        double[] contact = body.contact(other);
                        motion.car = n;
                        motion.carDepth = push.depth();
                        motion.carNormalX = -push.nx();
                        motion.carNormalZ = -push.nz();
                        motion.contactX = contact[0];
                        motion.contactZ = contact[1];
                    }
                    translate(push.x(), push.y(), push.z());
                    body.translate(push.x(), push.y(), push.z());
                    pushed = true;
                    motion.horizontal = true;
                }
                if (!pushed) {
                    break;
                }
            }
        }
        if (motion.wallWeight > 0.0) {
            motion.wallContactX /= motion.wallWeight;
            motion.wallContactZ /= motion.wallWeight;
        }
        double wall = Math.hypot(motion.wallNormalX, motion.wallNormalZ);
        if (wall > EPSILON) {
            motion.wallNormalX /= wall;
            motion.wallNormalZ /= wall;
        } else {
            motion.wallNormalX = 0.0;
            motion.wallNormalZ = 0.0;
        }
        motion.x = center[0] - startX;
        motion.y = center[1] - startY;
        motion.z = center[2] - startZ;
        return motion;
    }

    /**
     * 他の車との接触点（水平）。互いの角のうち相手の内側に入っているものの平均。
     * 角が入っていない（辺どうしで当たった）ときは中心の中点。
     */
    private double[] contact(CarHull other) {
        double sumX = 0.0;
        double sumZ = 0.0;
        int count = 0;
        for (double[] corner : corners()) {
            if (other.contains(corner)) {
                sumX += corner[0];
                sumZ += corner[2];
                count++;
            }
        }
        for (double[] corner : other.corners()) {
            if (contains(corner)) {
                sumX += corner[0];
                sumZ += corner[2];
                count++;
            }
        }
        if (count == 0) {
            return new double[]{(center[0] + other.center[0]) / 2.0, (center[2] + other.center[2]) / 2.0};
        }
        return new double[]{sumX / count, sumZ / count};
    }

    /**
     * ブロックとの接触点（水平）。この箱の角のうちブロックに入っているものと、ブロックの角のうち
     * この箱に入っているものの平均。どれも入っていなければ、ブロックの中でこの箱の中心に最も近い点。
     */
    private double[] contact(double[] box) {
        double sumX = 0.0;
        double sumZ = 0.0;
        int count = 0;
        for (double[] corner : corners()) {
            if (corner[0] >= box[0] && corner[0] <= box[3] && corner[1] >= box[1] && corner[1] <= box[4]
                    && corner[2] >= box[2] && corner[2] <= box[5]) {
                sumX += corner[0];
                sumZ += corner[2];
                count++;
            }
        }
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                for (int c = 0; c < 2; c++) {
                    double[] corner = {a == 0 ? box[0] : box[3], b == 0 ? box[1] : box[4], c == 0 ? box[2] : box[5]};
                    if (contains(corner)) {
                        sumX += corner[0];
                        sumZ += corner[2];
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return new double[]{Math.max(box[0], Math.min(box[3], center[0])), Math.max(box[2], Math.min(box[5], center[2]))};
        }
        return new double[]{sumX / count, sumZ / count};
    }

    /** 8 つの角（ワールド座標）。番号は前・上・右の正負をそれぞれ 4・2・1 の位に持つ。 */
    public double[][] corners() {
        double[][] corners = new double[8][];
        int n = 0;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                for (int c = -1; c <= 1; c += 2) {
                    double[] p = new double[3];
                    for (int k = 0; k < 3; k++) {
                        p[k] = center[k] + axes[0][k] * half[0] * a + axes[1][k] * half[1] * b
                                + axes[2][k] * half[2] * c;
                    }
                    corners[n++] = p;
                }
            }
        }
        return corners;
    }

    private boolean contains(double[] p) {
        double[] d = {p[0] - center[0], p[1] - center[1], p[2] - center[2]};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dot(d, axes[i])) > half[i] + 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
