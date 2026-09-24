package com.jdmmc.kurumamod.physics;

/**
 * 車両の刻々と変わる状態。{@link CarPhysics#step} がこれを書き換える。
 *
 * <p>走行中に変わらない値は {@link CarSpec} 側。</p>
 *
 * <p>Minecraft のワールドは X/Z が水平・Y が上、車体基準では前後・左右・上下と、
 * 2 つの座標系が混ざる。{@code vx} のような軸の文字で名前を付けるとどちらの話か
 * 分からなくなるので、**意味で名前を付けてある**（{@code forwardSpeed} /
 * {@code verticalSpeed} など）。</p>
 *
 * <p>角度の取り方:</p>
 * <ul>
 *   <li>{@code yaw} … Minecraft のヨー角に合わせる。0 のとき車体前方は +Z、増える向きが右旋回</li>
 *   <li>{@code pitch} … 正で鼻上げ（前が上がる）</li>
 *   <li>{@code roll} … 正で右下がり（右側が沈む）</li>
 * </ul>
 *
 * <p>単位はメートル・秒・ラジアン・ニュートン。Minecraft の 1 ブロックを 1m とみなす。</p>
 */
public final class CarState {

    // ---- 水平面の運動 ----

    /** 車体前方向の速度 [m/s]。負なら後退している。 */
    public double forwardSpeed;
    /** 車体右方向の速度 [m/s]。正なら右へ横滑りしている。 */
    public double lateralSpeed;
    /** 車体の向き [rad] */
    public double yaw;
    /** ヨー角速度 [rad/s]。正で右旋回。 */
    public double yawRate;
    /** 前輪の切れ角 [rad]。正で右。キーは 0/100 でも、ここは時間をかけて動く。 */
    public double steerAngle;
    /** アクセルの踏み込み量。0〜1。 */
    public double throttle;
    /** ブレーキの踏み込み量。0〜1。 */
    public double brake;

    // ---- 上下方向と姿勢（サスペンション） ----

    /** シャシー基準面（サスペンションのハードポイントが並ぶ面）のワールド高さ [m] */
    public double height;
    /** 上下方向の速度 [m/s]。正で上。 */
    public double verticalSpeed;
    /** ピッチ角 [rad]。正で鼻上げ。 */
    public double pitch;
    /** ピッチ角速度 [rad/s] */
    public double pitchRate;
    /** ロール角 [rad]。正で右下がり。 */
    public double roll;
    /** ロール角速度 [rad/s] */
    public double rollRate;
    /**
     * 実際に生じている車体右方向の加速度 [m/s^2]。いわゆる横 G。
     *
     * <p>荷重移動のロールモーメントはタイヤの横力ではなくこちらから求める。
     * 低速補正が速度を書き換えるため、タイヤ力と実際の加速度は低速域で一致しない。</p>
     */
    public double lateralAcceleration;
    /** 実際に生じている車体前方向の加速度 [m/s^2]。負なら減速。切れ角の上限を決めるのに使う。 */
    public double longitudinalAcceleration;
    /**
     * 実際に生じているヨー角加速度 [rad/s^2]。横加速度と合わせて、前後の軸がそれぞれ
     * どれだけ横力を受け持っているかを解くのに使う（ロールセンターの荷重移動）。
     */
    public double yawAcceleration;

    // ---- 駆動系 ----

    /** エンジン回転数 [rpm] */
    public double engineRpm;
    /** エンジンが出しているトルク [N*m]。負ならエンジンブレーキ。 */
    public double engineTorque;
    /** 現在の段。1 以上で前進、-1 で後退、0 でニュートラル。 */
    public int gear;
    /** 変速の残り時間 [s]。0 より大きい間は駆動力が抜ける。 */
    public double shiftTimer;
    /**
     * クラッチの繋がり具合。1 で直結、0 で完全に切れている。
     *
     * <p>プレイヤーは操作しない（マニュアルでも）。サイドブレーキを引いている間だけ
     * 自動で切れる。切らないと、ギア比で直結しているエンジンが後輪と一緒に
     * 引きずり下ろされるうえ、<b>エンジン側の慣性がサイドブレーキに勝って後輪がロックしない</b>。</p>
     */
    public double clutch = 1.0;

    /**
     * クラッチが直結しているか。滑っていれば false。
     *
     * <p>{@code CarPhysics#updateDrivetrain} が<b>ティックに 1 回だけ</b>決める。
     * タイヤの計算はこれを読むが、そこで判定し直してはいけない——
     * <b>輪を 1 つ処理するたびに車輪速が更新されるので、輪ごとに違う答えになる</b>
     * （実際にそうなって、制動距離が 21.4→12.1m、氷での車体スリップ角が 0.5→89.9 度になった）。</p>
     */
    public boolean clutchLocked = true;
    /** 駆動輪へ伝わる合計トルク [N*m]。 */
    public double driveTorque;
    /** 各輪へ実際に掛かる駆動トルク [N*m]。駆動配分と差動制限を経た値。 */
    public final double[] wheelDriveTorque = new double[Wheel.COUNT];
    /**
     * トラクションコントロールが通しているアクセル開度。1 で全開、0 で全カット。
     *
     * <p>1 未満なら介入中。表示に使う。</p>
     */
    public double tractionControlThrottle = 1.0;
    /**
     * 各輪の ABS の効き。1 でブレーキそのまま、0 で完全解放。
     *
     * <p>1 未満なら介入中。ブレーキ配分は固定なので、荷重が抜けた輪だけが先にロックする。</p>
     */
    public final double[] brakeRelease = new double[Wheel.COUNT];

    public CarState() {
        java.util.Arrays.fill(brakeRelease, 1.0);
    }

    // ---- 各輪 ----

    /** 各輪の現在のサスペンション長 [m]。描画でタイヤを吊る位置に使う。 */
    public final double[] suspensionLength = new double[Wheel.COUNT];
    /** 各輪の接地荷重 [N]。タイヤのグリップ上限を決める。 */
    public final double[] wheelLoad = new double[Wheel.COUNT];
    /** 各輪の回転角速度 [rad/s]。正で前進方向。 */
    public final double[] wheelAngularVelocity = new double[Wheel.COUNT];
    /**
     * 各輪の滑り率。0 でグリップ、正で空転、-1 でロック。
     *
     * <p>(車輪の周速 - 路面に対する速度) / 速度。空転もロックもここに出る。</p>
     */
    public final double[] wheelSlipRatio = new double[Wheel.COUNT];
    /**
     * 各輪が接地面で捨てている摩擦の仕事率 [W]。タイヤの力 × 滑り速度。
     *
     * <p>タイヤが煙を上げるのはゴムが熱を持つからで、その熱の出どころがこれ。
     * <b>滑っているだけでも、荷重が掛かっているだけでも大きくならない</b>のがこの量の要点で、
     * 空転している浮いた輪（荷重 0）も、荷重は掛かるが滑っていない転がっている輪も 0 になる。</p>
     */
    public final double[] wheelFrictionPower = new double[Wheel.COUNT];
    /**
     * 各輪の実際の切れ角 [rad]。正で右。
     *
     * <p>{@link #steerAngle} はハンドルの角度で、タイヤはそこからアッカーマン・トー・
     * ロールステア・コンプライアンスステアのぶんずれる（後輪も 0 とは限らない）。
     * タイヤの力はこちらで解く。</p>
     */
    public final double[] wheelSteerAngle = new double[Wheel.COUNT];

    /** 1 輪でも接地していれば true。空中では駆動も旋回もできない。 */
    public boolean isGrounded() {
        for (double load : wheelLoad) {
            if (load > 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 車体基準の位置にあるハードポイントのワールド高さ [m]。
     *
     * @param forwardOffset 前後位置 [m]。前が正
     * @param rightOffset   左右位置 [m]。右が正
     */
    public double hardpointHeight(double forwardOffset, double rightOffset) {
        return height + forwardOffset * Math.sin(pitch) - rightOffset * Math.sin(roll);
    }

    /** 同じ位置のハードポイントの上下速度 [m/s]。正で上。 */
    public double hardpointVerticalSpeed(double forwardOffset, double rightOffset) {
        return verticalSpeed + forwardOffset * pitchRate - rightOffset * rollRate;
    }

    /** 車体前方向の X 成分（単位ベクトル） */
    public double forwardX() {
        return -Math.sin(yaw);
    }

    /** 車体前方向の Z 成分（単位ベクトル） */
    public double forwardZ() {
        return Math.cos(yaw);
    }

    /** 車体右方向の X 成分（単位ベクトル）。前方向をヨー角で +90 度回したもの。 */
    public double rightX() {
        return -Math.cos(yaw);
    }

    /** 車体右方向の Z 成分（単位ベクトル） */
    public double rightZ() {
        return -Math.sin(yaw);
    }

    /** ワールド座標での速度の X 成分 [m/s]。車体基準の前後・左右の速度を合成する。 */
    public double velocityX() {
        return forwardX() * forwardSpeed + rightX() * lateralSpeed;
    }

    /** ワールド座標での速度の Z 成分 [m/s] */
    public double velocityZ() {
        return forwardZ() * forwardSpeed + rightZ() * lateralSpeed;
    }

    /**
     * ワールド座標の速度差を足し込む [m/s]。
     *
     * <p>車同士がぶつかったときの弾かれぶんを入れるのに使う。速度は車体基準
     * （前後・左右）で持っているので、ここで向きに合わせて分解する。</p>
     */
    public void addWorldVelocity(double dx, double dz) {
        forwardSpeed += dx * forwardX() + dz * forwardZ();
        lateralSpeed += dx * rightX() + dz * rightZ();
    }

    /**
     * ドリフト角を出すのに要る最低速度 [m/s]。これ以下では進行方向が定まらない。
     *
     * <p>合成速度で見ること。前後速度だけで見ると、大きく横へ滑っている車
     * （前後は遅いのに横は速い）を「止まっている」と誤判定する。</p>
     */
    private static final double SLIP_ANGLE_MIN_SPEED = 0.5;

    /**
     * 車体の向きと実際の進行方向のずれ [rad]。正なら右へ滑っている。
     *
     * <p>ドリフト角。挙動を観察するときの目安として使う。</p>
     *
     * <p><b>止まっているときは 0 を返す。</b>{@code atan2(横速度, |前後速度|)} は分母が
     * ほぼ 0 になると、わずかな横速度でも角度が 90 度へ飛ぶ。停車中に「ドリフト角 ±90 度」と
     * 出るのはこれが理由で、進行方向が定まっていない以上どんな値も意味を持たない。</p>
     */
    public double slipAngle() {
        if (Math.hypot(forwardSpeed, lateralSpeed) < SLIP_ANGLE_MIN_SPEED) {
            return 0.0;
        }
        return Math.atan2(lateralSpeed, Math.abs(forwardSpeed));
    }
}
