package com.jdmmc.kurumamod.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 車体。<b>ブロックと交わらない、硬い箱</b>。前後の下の角は斜めに落としてある。
 *
 * <p><b>ブロックとの衝突は車体が受ける。</b>タイヤ（サスペンション）は車体を支えて押すだけで、
 * 段差に当たったり、腹が乗ったり（亀になる）、転がって屋根の角が当たったりするのは車体の仕事。
 * 当たった点に力積を掛けるので、前後・左右・上下の速度とヨー・ピッチ・ロールの回転がまとめて
 * 変わる——当たったところを支点に車が回る。</p>
 *
 * <p><b>前後の下の角を斜めに落とす</b>（オフロード車の進入角）。段差の縁がこの斜面に当たると、
 * 押し返しが後ろ上向きになって鼻先が持ち上がり、低い段なら乗り越えられる。地上高そのものは
 * 実車どおり低い（{@link #CLEARANCE}）ので、腹を擦るのは見た目どおりのところ。</p>
 *
 * <p>当たりは 2 通りで拾う:</p>
 * <ul>
 *   <li><b>車体の表面の点がブロックの中にある</b>（表面に {@link #SAMPLE} 間隔で点を置く）。
 *       押し出す向きは、そのブロックの面のうち<b>外に出ている面</b>（隣が空いている面）だけから選ぶ。
 *       ブロックの継ぎ目の面で押すと横へ跳ぶ（平らな地面や壁はブロックを並べたものなので）</li>
 *   <li><b>ブロックの角や辺の点が車体の中にある</b>（腹が段の縁に乗った、細い柱に当たった）。
 *       押し出す向きは、車体の面のうちいちばん浅いもの</li>
 * </ul>
 *
 * <p>解き方は逐次の力積（接点ごとに、その点で近づく速度を止める力積を何周か繰り返して足す）。
 * 重なったぶんは、離れる向きの速度を少し上乗せして数ステップかけて押し戻す（位置を直接
 * ずらすと、回らずに平行に動くだけなので、それ自体が跳びになる）。Minecraft に依存しない。</p>
 */
public final class CarBody {

    /** タイヤの外周から車体の端までの張り出し [m]。 */
    public static final double OVERHANG = 0.60;
    /** 既定の地上高 [m]。諸元の {@link CarSpec#bodyClearance()} の既定と同じ。屋根の高さはここから決める。 */
    public static final double CLEARANCE = 0.25;
    /** 地上高が既定のときの車体の高さ（下端から屋根まで） [m]。地上高を上げても屋根は動かない。 */
    public static final double HEIGHT = 1.20;
    /** 地上高を上げたときに残す車体の厚みの下限 [m]。 */
    private static final double MIN_THICKNESS = 0.20;
    /** 前後の下の角を落とす高さ [m]。 */
    public static final double CHAMFER_RISE = 0.50;
    /** 前後の下の角を落とす長さ [m]。 */
    public static final double CHAMFER_RUN = 0.60;
    /** 車幅のトレッドに対する比。 */
    public static final double WIDTH_RATIO = 0.90;
    /** 表面に置く点の間隔 [m]。 */
    public static final double SAMPLE = 0.30;

    /** 車体と地形の摩擦係数。 */
    private static final double FRICTION = 0.25;
    /** 力積を何周繰り返して足すか。 */
    private static final int ITERATIONS = 8;
    /** 重なりを押し戻す強さ（1 ステップで重なりの何割を戻す速度を上乗せするか）。 */
    private static final double BAUMGARTE = 0.25;
    /** これだけの重なりは許す [m]（ぴったり接したまま震えないため）。 */
    private static final double SLOP = 0.01;
    /** 押し戻しの速度の上限 [m/s]。埋まった車が飛び出さないよう。 */
    private static final double MAX_BIAS = 3.0;
    /** これより深い重なりは押し出さない [m]（埋まっている）。 */
    private static final double MAX_DEPTH = 0.8;

    // 車体基準（前・上・右）の範囲。上下の原点は重心のシャシー基準面
    private final double front;
    private final double rear;
    private final double bottom;
    private final double top;
    private final double side;
    /** 前後の下の角を落とす高さと長さ [m]。 */
    private final double rise;
    private final double run;
    /** 面（外向きの法線 {前, 上, 右} と、法線方向の位置）。点 p は n·p ≤ d なら内側。 */
    private final double[][] planes;
    /** 表面の点（車体基準 {前, 上, 右}）。 */
    private final double[][] samples;

    private CarBody(CarSpec spec, CarSpec shape) {
        // 大きさは車種の元の諸元（shape）で決める。調整画面でタイヤ半径やホイールベース・トレッドを
        // 動かしても、見た目の車体は伸び縮みしないので、当たり判定も変えない。前後の位置だけは
        // いまの諸元の前後輪の中点に合わせる（車体のメッシュの原点がそこにあるため）
        double middle = spec.axleMidpointOffset();
        double half = shape.wheelBase() / 2.0 + shape.wheelRadius() + OVERHANG;
        front = middle + half;
        rear = middle - half;
        // 地上高（調整画面）は元の諸元の車高での値。車高を上げれば（サスとタイヤ）シャシー基準面と
        // 一緒に上がる。屋根は既定の地上高から決めるので、地上高を上げると下端だけが上がる
        top = CLEARANCE + HEIGHT - shape.staticRideHeight();
        bottom = Math.min(spec.bodyClearance() - shape.staticRideHeight(), top - MIN_THICKNESS);
        side = shape.trackWidth() * WIDTH_RATIO / 2.0;
        // 斜めは車体の厚みの 4 割まで（地上高を上げて薄くなった車体で、斜めが屋根を越えないよう）
        double scale = Math.min(1.0, (top - bottom) * 0.4 / CHAMFER_RISE);
        rise = CHAMFER_RISE * scale;
        run = CHAMFER_RUN * scale;
        double slope = Math.hypot(rise, run);
        planes = new double[][]{
                {0, 1, 0, top},
                {0, -1, 0, -bottom},
                {0, 0, 1, side},
                {0, 0, -1, side},
                {1, 0, 0, front},
                {-1, 0, 0, -rear},
                // 前の斜面: (front - run, bottom) と (front, bottom + rise) を通る。外向きは前下
                {rise / slope, -run / slope, 0, ((front - run) * rise - bottom * run) / slope},
                // 後ろの斜面: (rear + run, bottom) と (rear, bottom + rise) を通る。外向きは後ろ下
                {-rise / slope, -run / slope, 0, (-(rear + run) * rise - bottom * run) / slope},
        };
        samples = buildSamples();
    }

    /**
     * @param spec  いまの諸元（調整画面の値を含む）。前後の位置にだけ使う
     * @param shape 車種の元の諸元。大きさを決める
     */
    public static CarBody of(CarSpec spec, CarSpec shape) {
        return new CarBody(spec, shape);
    }

    /** 元の諸元といまの諸元が同じとき（実験用）。 */
    public static CarBody of(CarSpec spec) {
        return new CarBody(spec, spec);
    }

    public double front() {
        return front;
    }

    public double rear() {
        return rear;
    }

    public double bottom() {
        return bottom;
    }

    public double top() {
        return top;
    }

    public double side() {
        return side;
    }

    /** 角（車体基準 {前, 上, 右}）。描画用。0〜5 が左、6〜11 が右で、同じ順に一周する。 */
    public double[][] corners() {
        double[][] outline = outline();
        double[][] out = new double[outline.length * 2][];
        for (int i = 0; i < outline.length; i++) {
            out[i] = new double[]{outline[i][0], outline[i][1], -side};
            out[i + outline.length] = new double[]{outline[i][0], outline[i][1], side};
        }
        return out;
    }

    /** 横から見た輪郭（{前, 上}）。下の後ろから前へ、前の面、屋根、後ろの面と一周する。 */
    private double[][] outline() {
        return new double[][]{
                {rear + run, bottom}, {front - run, bottom}, {front, bottom + rise},
                {front, top}, {rear, top}, {rear, bottom + rise}};
    }

    private double[][] buildSamples() {
        List<double[]> out = new ArrayList<>();
        double[][] outline = outline();
        // 両側面: 輪郭の内側を格子で、輪郭の辺を細かく
        for (double r : new double[]{-side, side}) {
            for (double f = rear; f <= front + 1.0e-9; f += SAMPLE) {
                for (double u = bottom; u <= top + 1.0e-9; u += SAMPLE) {
                    if (insideOutline(f, u)) {
                        out.add(new double[]{f, u, r});
                    }
                }
            }
            for (int i = 0; i < outline.length; i++) {
                double[] a = outline[i];
                double[] b = outline[(i + 1) % outline.length];
                addLine(out, a[0], a[1], r, b[0], b[1], r);
            }
        }
        // 輪郭の各辺を左右に渡した面（底・斜面・前・屋根・後ろ）
        for (int i = 0; i < outline.length; i++) {
            double[] a = outline[i];
            double[] b = outline[(i + 1) % outline.length];
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                double f = a[0] + (b[0] - a[0]) * t;
                double u = a[1] + (b[1] - a[1]) * t;
                addLine(out, f, u, -side, f, u, side);
            }
        }
        return out.toArray(new double[0][]);
    }

    private boolean insideOutline(double f, double u) {
        for (double[] p : planes) {
            if (p[2] != 0) {
                continue;
            }
            if (p[0] * f + p[1] * u > p[3] + 1.0e-9) {
                return false;
            }
        }
        return true;
    }

    private static void addLine(List<double[]> out, double f0, double u0, double r0, double f1, double u1, double r1) {
        double length = Math.sqrt((f1 - f0) * (f1 - f0) + (u1 - u0) * (u1 - u0) + (r1 - r0) * (r1 - r0));
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
        for (int k = 0; k <= steps; k++) {
            double t = (double) k / steps;
            out.add(new double[]{f0 + (f1 - f0) * t, u0 + (u1 - u0) * t, r0 + (r1 - r0) * t});
        }
    }

    // ------------------------------------------------------------------
    // 姿勢
    // ------------------------------------------------------------------

    /** 車体の向き。前・上・右の単位ベクトル（ワールド）。CarHull と同じ回し方（ヨー → ピッチ → ロール）。 */
    public static double[][] axes(double yaw, double pitch, double roll) {
        double[] f0 = {-Math.sin(yaw), 0.0, Math.cos(yaw)};
        double[] r0 = {-Math.cos(yaw), 0.0, -Math.sin(yaw)};
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        double[] f = {f0[0] * cp, sp, f0[2] * cp};
        double[] u1 = {-f0[0] * sp, cp, -f0[2] * sp};
        double[] r = {r0[0] * cr - u1[0] * sr, -u1[1] * sr, r0[2] * cr - u1[2] * sr};
        double[] u = {u1[0] * cr + r0[0] * sr, u1[1] * cr, u1[2] * cr + r0[2] * sr};
        return new double[][]{f, u, r};
    }

    /** 車体を包むワールド軸の箱 {minX, minY, minZ, maxX, maxY, maxZ}。 */
    public double[] bounds(double x, double y, double z, double[][] ax) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] c : corners()) {
            for (int k = 0; k < 3; k++) {
                double v = (k == 0 ? x : k == 1 ? y : z) + ax[0][k] * c[0] + ax[1][k] * c[1] + ax[2][k] * c[2];
                box[k] = Math.min(box[k], v);
                box[k + 3] = Math.max(box[k + 3], v);
            }
        }
        return box;
    }

    // ------------------------------------------------------------------
    // 当たり
    // ------------------------------------------------------------------

    /** 当たり 1 つ。点（ワールド）、車体を押す向き（単位）、重なりの深さ。 */
    private record Contact(double px, double py, double pz, double nx, double ny, double nz, double depth) {
    }

    /** {@link #solve} の結果。 */
    public static final class Result {
        /** 当たったか。 */
        public boolean touching;
        /** 当たった点で近づいていた速さの最大 [m/s]。衝撃の大きさ。 */
        public double impact;
        /** 当たりの数。 */
        public int contacts;
        /** 押されたうち、横向きの成分があったか（壁）。 */
        public boolean horizontal;
        /** 押されたうち、上下の成分があったか（床・天井）。 */
        public boolean vertical;
    }

    /**
     * 地形との当たりを解き、{@code state} の速度と回転を書き換える。位置は動かさない
     * （次のステップの積分で動く）。物理の 1 ステップごとに呼ぶ。
     *
     * @param x 重心のシャシー基準面の位置（ワールド）。高さは {@code state.height}
     */
    public Result solve(CarSpec spec, CarState state, double x, double z, BlockField field, double dt) {
        Result result = new Result();
        double y = state.height;
        double[][] ax = axes(state.yaw, state.pitch, state.roll);
        List<Contact> contacts = findContacts(x, y, z, ax, field);
        result.contacts = contacts.size();
        if (contacts.isEmpty()) {
            return result;
        }
        result.touching = true;

        // 速度（ワールド）と回転（鉛直・車体の右・車体の前の 3 軸まわり）
        double[] fh = {-Math.sin(state.yaw), 0.0, Math.cos(state.yaw)};
        double[] rh = {-Math.cos(state.yaw), 0.0, -Math.sin(state.yaw)};
        double[] v = {
                fh[0] * state.forwardSpeed + rh[0] * state.lateralSpeed,
                state.verticalSpeed,
                fh[2] * state.forwardSpeed + rh[2] * state.lateralSpeed};
        // 回転の軸は、物理が角度を積む順（ヨー → ピッチ → ロール）に合わせる。ヨーは鉛直軸まわり
        // （「前が右へ倒れる」が正なので符号が逆）、ピッチは<b>ロールする前の水平な右向き</b>の軸まわり、
        // ロールは車体の前向きの軸まわり。ピッチを傾いた車体の右向きで取ると、裏返し（ロール 180 度）で
        // 向きが逆になり、当たった反動が逆に回して車が鼻で逆立ちした
        double[] w = {-state.yawRate, state.pitchRate, state.rollRate};
        double[][] rotAxes = {{0, 1, 0}, rh, ax[0]};
        double[] inertia = {spec.yawInertia(), spec.pitchInertia(), spec.rollInertia()};
        double mass = spec.mass();

        int n = contacts.size();
        double[] normalImpulse = new double[n];
        double[] frictionImpulse = new double[n];
        double[][] arms = new double[n][];
        double[] normalMass = new double[n];
        double[] bias = new double[n];
        for (int i = 0; i < n; i++) {
            Contact c = contacts.get(i);
            arms[i] = new double[]{c.px - x, c.py - y, c.pz - z};
            double[] dir = {c.nx, c.ny, c.nz};
            normalMass[i] = effectiveMass(arms[i], dir, rotAxes, inertia, mass);
            bias[i] = Math.min(MAX_BIAS, BAUMGARTE * Math.max(0.0, c.depth - SLOP) / dt);
            double approach = -dot(pointVelocity(v, w, rotAxes, arms[i]), dir);
            result.impact = Math.max(result.impact, approach);
            if (Math.abs(c.ny) > 0.5) {
                result.vertical = true;
            } else {
                result.horizontal = true;
            }
        }

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int i = 0; i < n; i++) {
                Contact c = contacts.get(i);
                double[] dir = {c.nx, c.ny, c.nz};
                double[] pv = pointVelocity(v, w, rotAxes, arms[i]);
                double vn = dot(pv, dir);
                double delta = (bias[i] - vn) / normalMass[i];
                double next = Math.max(0.0, normalImpulse[i] + delta);
                delta = next - normalImpulse[i];
                normalImpulse[i] = next;
                applyImpulse(v, w, rotAxes, inertia, mass, arms[i], scale(dir, delta));

                // 摩擦。接点の滑る向きと逆に、押す力 × 係数まで
                pv = pointVelocity(v, w, rotAxes, arms[i]);
                vn = dot(pv, dir);
                double[] vt = {pv[0] - dir[0] * vn, pv[1] - dir[1] * vn, pv[2] - dir[2] * vn};
                double speed = Math.sqrt(dot(vt, vt));
                if (speed < 1.0e-6) {
                    continue;
                }
                double[] t = scale(vt, 1.0 / speed);
                double tangentMass = effectiveMass(arms[i], t, rotAxes, inertia, mass);
                double limit = FRICTION * normalImpulse[i];
                double want = Math.min(speed / tangentMass, Math.max(0.0, limit - frictionImpulse[i]));
                if (want <= 0.0) {
                    continue;
                }
                frictionImpulse[i] += want;
                applyImpulse(v, w, rotAxes, inertia, mass, arms[i], scale(t, -want));
            }
        }

        state.forwardSpeed = dot(v, fh);
        state.lateralSpeed = dot(v, rh);
        state.verticalSpeed = v[1];
        state.yawRate = -w[0];
        state.pitchRate = w[1];
        state.rollRate = w[2];
        return result;
    }

    /**
     * ブロックごとに、車体を押し出す向きを 1 つ決めてから当たりの点を集める。
     *
     * <p><b>向きを点ごとに決めてはいけない。</b>段の縁が車体の斜面に当たっているとき、車体の点は
     * 段の側面にも入っているので、点ごとに一番近い面を選ぶと水平に押し返されて斜面が意味を
     * 失い、乗り上げられなかった。45 度に並べたブロックの壁でも、歯の面で押されて引っかかった。</p>
     *
     * <p>候補は<b>車体の各面の向きと、ブロックの外に出ている面の向き</b>。それぞれの向きで
     * 離すのに要る量を測り、いちばん少ない向きを選ぶ（分離軸の考え方）。段の縁が斜面に
     * 当たれば斜面の向き（後ろ上）、歯が側面に当たれば側面の向きになる。ブロックの継ぎ目の面
     * （隣もブロック）は候補にしない——平らな地面や壁はブロックを並べたものなので、
     * 継ぎ目で押すと横へ跳ぶ。</p>
     */
    private List<Contact> findContacts(double x, double y, double z, double[][] ax, BlockField field) {
        List<Contact> out = new ArrayList<>();
        double[] reach = bounds(x, y, z, ax);
        double[][] corners = corners();
        double[][] bodyPoints = new double[corners.length][];
        for (int i = 0; i < corners.length; i++) {
            bodyPoints[i] = toWorld(x, y, z, ax, corners[i]);
        }
        double[][] samplePoints = new double[samples.length][];
        for (int i = 0; i < samples.length; i++) {
            samplePoints[i] = toWorld(x, y, z, ax, samples[i]);
        }
        // 車体の面の外向き（ワールド）
        double[][] faceNormals = new double[planes.length][];
        for (int i = 0; i < planes.length; i++) {
            double[] pl = planes[i];
            faceNormals[i] = new double[]{
                    ax[0][0] * pl[0] + ax[1][0] * pl[1] + ax[2][0] * pl[2],
                    ax[0][1] * pl[0] + ax[1][1] * pl[1] + ax[2][1] * pl[2],
                    ax[0][2] * pl[0] + ax[1][2] * pl[1] + ax[2][2] * pl[2]};
        }
        double[][] boxNormals = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (double[] box : field.boxes()) {
            if (box[3] < reach[0] || box[0] > reach[3] || box[4] < reach[1] || box[1] > reach[4]
                    || box[5] < reach[2] || box[2] > reach[5]) {
                continue;
            }
            double[][] boxPoints = boxCorners(box);
            // 押し出す向きを選ぶ。重なっていない向きが 1 つでもあれば当たっていない
            double bestDepth = Double.MAX_VALUE;
            double[] bestNormal = null;
            boolean separated = false;
            for (double[] m : faceNormals) {
                // 車体はこの面の外向きと逆へ押される
                double[] n = {-m[0], -m[1], -m[2]};
                double depth = maxProjection(boxPoints, n) - minProjection(bodyPoints, n);
                if (depth <= 0.0) {
                    separated = true;
                    break;
                }
                if (depth < bestDepth) {
                    bestDepth = depth;
                    bestNormal = n;
                }
            }
            if (separated) {
                continue;
            }
            for (double[] n : boxNormals) {
                double depth = maxProjection(boxPoints, n) - minProjection(bodyPoints, n);
                if (depth <= 0.0) {
                    separated = true;
                    break;
                }
                if (depth < bestDepth && faceIsOpen(box, n, field)) {
                    bestDepth = depth;
                    bestNormal = n;
                }
            }
            if (separated || bestNormal == null || bestDepth > MAX_DEPTH) {
                continue;
            }
            double[] n = bestNormal;
            double boxMax = maxProjection(boxPoints, n);
            double bodyMin = minProjection(bodyPoints, n);
            int before = out.size();
            // 車体の表面の点がこのブロックの中にある
            for (double[] p : samplePoints) {
                if (p[0] > box[0] && p[0] < box[3] && p[1] > box[1] && p[1] < box[4] && p[2] > box[2] && p[2] < box[5]) {
                    double depth = Math.min(bestDepth, boxMax - dot(n, p));
                    if (depth > 0.0) {
                        out.add(new Contact(p[0], p[1], p[2], n[0], n[1], n[2], depth));
                    }
                }
            }
            // ブロックの角や辺の点が車体の中にある
            for (double[] b : edgePoints(box)) {
                if (insideBody(b, x, y, z, ax)) {
                    double depth = Math.min(bestDepth, dot(n, b) - bodyMin);
                    if (depth > 0.0) {
                        out.add(new Contact(b[0], b[1], b[2], n[0], n[1], n[2], depth));
                    }
                }
            }
            if (out.size() == before) {
                // 点の間をすり抜けるほど薄い重なり。いちばん深い車体の角で受ける
                double[] deepest = bodyPoints[0];
                double lowest = Double.MAX_VALUE;
                for (double[] q : bodyPoints) {
                    double d = dot(n, q);
                    if (d < lowest) {
                        lowest = d;
                        deepest = q;
                    }
                }
                out.add(new Contact(deepest[0], deepest[1], deepest[2], n[0], n[1], n[2], bestDepth));
            }
        }
        return out;
    }

    /** ブロックの面（外向き n）の外が空いているか。面の中心のすぐ外で見る。 */
    private static boolean faceIsOpen(double[] box, double[] n, BlockField field) {
        double cx = (box[0] + box[3]) / 2.0;
        double cy = (box[1] + box[4]) / 2.0;
        double cz = (box[2] + box[5]) / 2.0;
        double hx = (box[3] - box[0]) / 2.0;
        double hy = (box[4] - box[1]) / 2.0;
        double hz = (box[5] - box[2]) / 2.0;
        return !field.solidAt(cx + n[0] * (hx + 0.02), cy + n[1] * (hy + 0.02), cz + n[2] * (hz + 0.02));
    }

    private boolean insideBody(double[] p, double x, double y, double z, double[][] ax) {
        double dx = p[0] - x;
        double dy = p[1] - y;
        double dz = p[2] - z;
        double f = dx * ax[0][0] + dy * ax[0][1] + dz * ax[0][2];
        double u = dx * ax[1][0] + dy * ax[1][1] + dz * ax[1][2];
        double r = dx * ax[2][0] + dy * ax[2][1] + dz * ax[2][2];
        for (double[] plane : planes) {
            if (plane[0] * f + plane[1] * u + plane[2] * r >= plane[3]) {
                return false;
            }
        }
        return true;
    }

    private static double[] toWorld(double x, double y, double z, double[][] ax, double[] p) {
        return new double[]{
                x + ax[0][0] * p[0] + ax[1][0] * p[1] + ax[2][0] * p[2],
                y + ax[0][1] * p[0] + ax[1][1] * p[1] + ax[2][1] * p[2],
                z + ax[0][2] * p[0] + ax[1][2] * p[1] + ax[2][2] * p[2]};
    }

    private static double[][] boxCorners(double[] b) {
        double[][] out = new double[8][];
        int i = 0;
        for (double xx : new double[]{b[0], b[3]}) {
            for (double yy : new double[]{b[1], b[4]}) {
                for (double zz : new double[]{b[2], b[5]}) {
                    out[i++] = new double[]{xx, yy, zz};
                }
            }
        }
        return out;
    }

    private static double maxProjection(double[][] points, double[] n) {
        double best = -Double.MAX_VALUE;
        for (double[] p : points) {
            best = Math.max(best, dot(n, p));
        }
        return best;
    }

    private static double minProjection(double[][] points, double[] n) {
        double best = Double.MAX_VALUE;
        for (double[] p : points) {
            best = Math.min(best, dot(n, p));
        }
        return best;
    }

    /** ブロックの辺の上の点（角を含む、{@link #SAMPLE} 間隔）。 */
    private static List<double[]> edgePoints(double[] b) {
        List<double[]> out = new ArrayList<>();
        double[] xs = {b[0], b[3]};
        double[] ys = {b[1], b[4]};
        double[] zs = {b[2], b[5]};
        for (double yy : ys) {
            for (double zz : zs) {
                addEdge(out, b[0], yy, zz, b[3], yy, zz);
            }
        }
        for (double xx : xs) {
            for (double zz : zs) {
                addEdge(out, xx, b[1], zz, xx, b[4], zz);
            }
        }
        for (double xx : xs) {
            for (double yy : ys) {
                addEdge(out, xx, yy, b[2], xx, yy, b[5]);
            }
        }
        return out;
    }

    private static void addEdge(List<double[]> out, double x0, double y0, double z0, double x1, double y1, double z1) {
        double length = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
        for (int k = 0; k <= steps; k++) {
            double t = (double) k / steps;
            out.add(new double[]{x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t});
        }
    }

    // ------------------------------------------------------------------
    // 剛体の計算
    // ------------------------------------------------------------------

    private static double[] pointVelocity(double[] v, double[] w, double[][] rotAxes, double[] arm) {
        double[] out = v.clone();
        for (int a = 0; a < 3; a++) {
            double[] c = cross(rotAxes[a], arm);
            out[0] += w[a] * c[0];
            out[1] += w[a] * c[1];
            out[2] += w[a] * c[2];
        }
        return out;
    }

    private static double effectiveMass(double[] arm, double[] dir, double[][] rotAxes, double[] inertia, double mass) {
        double[] rn = cross(arm, dir);
        double k = 1.0 / mass;
        for (int a = 0; a < 3; a++) {
            double t = dot(rn, rotAxes[a]);
            k += t * t / inertia[a];
        }
        return k;
    }

    private static void applyImpulse(double[] v, double[] w, double[][] rotAxes, double[] inertia, double mass,
                                     double[] arm, double[] impulse) {
        v[0] += impulse[0] / mass;
        v[1] += impulse[1] / mass;
        v[2] += impulse[2] / mass;
        double[] torque = cross(arm, impulse);
        for (int a = 0; a < 3; a++) {
            w[a] += dot(torque, rotAxes[a]) / inertia[a];
        }
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }
}
