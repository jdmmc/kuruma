package com.jdmmc.kurumamod.client;

/**
 * 運転者の手をハンドルに置く計算（版の {@code SteeringHands} が使う）。握る位置（リムの上の点）、肩から握る位置へ
 * 腕を向ける角度、持ち替えの記録。<b>座標の変換（車体のメッシュから車体の座標へ）とモデルへ当てるのは版。</b>
 */
public final class SteeringArms {

    /** プレイヤーのモデルの拡大率（{@code PlayerRenderer#scale}）。 */
    public static final float PLAYER_SCALE = 0.9375F;
    /** モデルの原点を足元へ下げる量（{@code LivingEntityRenderer#render}）[ブロック]。 */
    public static final float MODEL_DROP = 1.501F;
    /** 腕の付け根から手のひらまで [ブロック]（腕の箱は付け根から 10px、握るのはその少し手前）。 */
    private static final float HAND_REACH = 9.5F / 16.0F;
    /** 腕を伸び縮みさせてでも手をリムに届かせる範囲。これを越える座席の位置では手が離れて見える。 */
    private static final float MIN_STRETCH = 0.75F;
    private static final float MAX_STRETCH = 1.35F;
    /** 握る位置の半径。メッシュの外形の直径に対する割合（リムの太さの真ん中あたり）。 */
    private static final double RIM_RATIO = 0.45;
    /** 持ち替えで手をリムから浮かせる高さ [m]（運転者の側へ）。 */
    private static final double LIFT = 0.07;

    private SteeringArms() {
    }

    /** 車 1 台ぶんの手の握り具合（{@link SteeringGrip}）と、それを進めるための前の値。 */
    public static final class Grip {
        private final SteeringGrip grip = new SteeringGrip();
        private long lastNanos;
        private int driverId = -1;
        /** 前のフレームのハンドルの角度 [度]。他人の車で「放している」を推し量るのに使う */
        private double lastWheel;

        /**
         * 1 フレーム進める。
         *
         * @param wheel ハンドルの角度 [度]
         * @param input 自分が運転している車なら舵の入力があるか。他人の車は null
         */
        public SteeringGrip update(int driver, double wheel, Boolean input) {
            long now = System.nanoTime();
            if (driverId != driver || now - lastNanos > 1_000_000_000L) {
                // 乗り換えた・しばらく見ていなかった。定位置で握らせ直す
                grip.reset(wheel);
                driverId = driver;
            } else {
                grip.update(wheel, (now - lastNanos) / 1.0e9, steeringHeld(input, wheel, lastWheel));
            }
            lastNanos = now;
            lastWheel = wheel;
            return grip;
        }
    }

    /**
     * 運転者がハンドルを握っているか。放している間は手を止める（{@link SteeringGrip}）。
     *
     * <p>自分が運転している車は入力（A/D・スティック）をそのまま見る。他人の車は入力が届かないので、
     * <b>ハンドルが中立へ戻っていく間を「放している」と見なす</b>（舵を放すと戻るため）。
     * 自己復元でカウンターへ切れていくのは見分けられず、握っている扱いになる。</p>
     */
    public static boolean steeringHeld(Boolean input, double wheel, double lastWheel) {
        if (input != null) {
            return input;
        }
        return !(Math.abs(wheel) < Math.abs(lastWheel) - 0.05);
    }

    /**
     * リムの上の、見た目の角度 {@code angle}（真上から時計回り）[度] の点を<b>車体のメッシュの座標</b>で。
     * ハンドルの描き方（版の {@code CarObjRenderer} の drawSteeringWheel）と同じ順で平行移動と傾きを掛ける。
     * ハンドルの回転ぶんは角度に入っているので掛けない。
     *
     * @param center   ハンドルの中心（メッシュの座標）
     * @param axis     運転者へ向く軸（単位ベクトル）
     * @param diameter メッシュの外形の直径
     * @param fit      車体のメッシュの拡大（諸元のホイールベース ÷ 設計のホイールベース）
     * @param scale    車体の拡大 {x, y, z}
     * @param lift     持ち替えで浮かせる割合 0..1
     * @param tilt     Ctrl+G で足した傾き [度]
     * @param offset   Ctrl+G で足した位置 [m] {x, y, z}
     */
    public static float[] rimPoint(double[] center, double[] axis, double diameter, double fit, double[] scale,
                                   double angle, double lift, double tilt, double[] offset) {
        double[] a = axis;
        // 面内の上（世界の上を面へ落としたもの）と右（上 × 運転者向きの軸）
        double ux = -a[0] * a[1];
        double uy = 1.0 - a[1] * a[1];
        double uz = -a[2] * a[1];
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double rx = uy * a[2] - uz * a[1];
        double ry = uz * a[0] - ux * a[2];
        double rz = ux * a[1] - uy * a[0];

        double radius = diameter * RIM_RATIO;
        double theta = Math.toRadians(angle);
        double cos = Math.cos(theta) * radius;
        double sin = Math.sin(theta) * radius;
        double up = lift * LIFT / (fit * scale[1]);
        float x = (float) (ux * cos + rx * sin + a[0] * up);
        float y = (float) (uy * cos + ry * sin + a[1] * up);
        float z = (float) (uz * cos + rz * sin + a[2] * up);

        double t = Math.toRadians(tilt);
        if (t != 0.0) {
            // X 軸まわりに −tilt 回す
            float c = (float) Math.cos(-t);
            float s = (float) Math.sin(-t);
            float ry2 = y * c - z * s;
            float rz2 = y * s + z * c;
            y = ry2;
            z = rz2;
        }
        return new float[]{
                x + (float) (center[0] + offset[0] / (fit * scale[0])),
                y + (float) (center[1] + offset[1] / (fit * scale[1])),
                z + (float) (center[2] + offset[2] / (fit * scale[2]))};
    }

    /**
     * 肩から握る位置へ腕を向ける。{xRot, yRot, 長さ方向の拡大率}。
     *
     * <p>腕は付け根から +Y（モデルの下）へ伸びていて、ModelPart は Rz·Ry·Rx の順に回す。zRot を 0 にすると
     * 向きは (sin y·sin x, cos x, cos y·sin x) なので、x = −acos(向きの y)、y = atan2(−向きの x, −向きの z)
     * （前へ出すとき sin x が負）。</p>
     *
     * @param feetX  足元（車体の座標）
     * @param pivotX 付け根の X [ブロック]（モデルの座標。右腕が負）
     * @param gripX  握る位置（車体の座標）
     */
    public static float[] arm(float feetX, float feetY, float feetZ, float pivotX, float pivotY,
                              float gripX, float gripY, float gripZ) {
        // 肩（車体の座標）
        float sx = feetX - PLAYER_SCALE * pivotX;
        float sy = feetY - PLAYER_SCALE * (pivotY - MODEL_DROP);
        float sz = feetZ;
        // 肩から手へ（モデルの座標。Rz(180) を戻して拡大で割る）
        float dx = -(gripX - sx) / PLAYER_SCALE;
        float dy = -(gripY - sy) / PLAYER_SCALE;
        float dz = (gripZ - sz) / PLAYER_SCALE;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-4F) {
            return new float[]{0.0F, 0.0F, 1.0F};
        }
        float x = (float) -Math.acos(Math.max(-1.0F, Math.min(1.0F, dy / length)));
        float y = (float) Math.atan2(-dx, -dz);
        float stretch = Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, length / HAND_REACH));
        return new float[]{x, y, stretch};
    }
}
