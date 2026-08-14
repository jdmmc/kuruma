package com.jdmmc.kurumamod.physics;

/**
 * 車両の運動を解く。
 *
 * <p>Minecraft のクラスには一切依存しない。状態を持たない静的メソッドなので、
 * ゲームを起動せずに {@link #step} を回して挙動を観察できる。地形の情報は
 * {@link GroundContact} を通して外から与える。</p>
 *
 * <p>1 ステップの流れ:</p>
 * <ol>
 *   <li>{@link #updateSteering} … 入力から前輪の切れ角を決める</li>
 *   <li>{@link #updateDrivetrain} … エンジン回転数・変速・駆動トルクを決める</li>
 *   <li>{@link #computeTireForces} … 各輪のスリップ角と接地荷重からタイヤが出す力を求める</li>
 *   <li>{@link #updateSuspension} … バネとダンパー、それにタイヤの力による荷重移動を解く</li>
 *   <li>{@link #updateChassisMotion} … 車体の速度と向きを進める</li>
 * </ol>
 *
 * <p>タイヤの力は 1 ステップ前の接地荷重を使う。荷重とタイヤ力は互いに依存しているので
 * どこかで 1 ステップぶんずらす必要があり、刻みが 12.5ms なら実用上問題にならない。</p>
 *
 * <p>今後分解していく余地:</p>
 * <ul>
 *   <li>クラッチもトルクコンバータも無く、エンジンと駆動輪はギア比で直結。
 *       発進時はエンジン回転数をアイドルで下支えすることで代用している</li>
 *   <li>ブレーキはまだ減速度指定（{@code brakeDecel}）</li>
 * </ul>
 */
public final class CarPhysics {

    /** 重力加速度 [m/s^2]。Minecraft の落下加速度ではなく現実の値を使う。 */
    public static final double GRAVITY = 9.81;

    /**
     * 1 輪が受け持つ荷重の上限（停車時荷重の何倍か）。
     *
     * <p>高所からの落下でバネが一気に縮むと、1 ステップぶんの力が現実離れした大きさになり
     * 数値が発散する。物理的な意味はない安全弁。</p>
     */
    private static final double MAX_LOAD_FACTOR = 8.0;

    /** 滑り率の上限。空転もロックもこの範囲に抑えて、数値が飛ぶのを防ぐ。 */
    private static final double MAX_SLIP_RATIO = 3.0;

    /**
     * 荷重移動に使う横加速度の上限 [G]。
     *
     * <p>タイヤが出せるのはせいぜい摩擦係数ぶんなので、これを超える値が出たら
     * 衝突などで速度が飛んだということ。物理的な意味はない安全弁で、
     * 入れておかないとその 1 ティックのモーメントで車体が横転する。</p>
     */
    private static final double MAX_LATERAL_G = 2.5;

    /** 低速で運動学モデルへ寄せる速さ [1/s]。大きいほど強く引き戻す。 */
    private static final double KINEMATIC_RELAXATION_RATE = 20.0;

    /** トラクションコントロールの効きの強さ。滑り率の超過ぶんに掛けてアクセルを絞る。 */
    private static final double TRACTION_CONTROL_GAIN = 3.0;
    /** トラクションコントロールの応答の速さ [1/s]。速すぎると振動する。 */
    private static final double TRACTION_CONTROL_RATE = 30.0;

    /** ABS の効きの強さ。滑り率の超過ぶんに掛けてブレーキを緩める。 */
    private static final double ABS_GAIN = 3.0;
    /** ABS の応答の速さ [1/s]。 */
    private static final double ABS_RATE = 30.0;

    /**
     * 縦にグリップを使いきっていても、横へ回せるぶんとして残す割合。
     *
     * <p>0 にすると全制動中に舵が完全に死ぬ。実車の ABS が目指すのは
     * 「ロックさせずに操舵性を残すこと」なので、それに倣って確保しておく。</p>
     */
    private static final double MIN_STEERING_GRIP = 0.45;

    private CarPhysics() {
    }

    /** タイヤが路面へ出す力の合計。車体基準で、縦は前が正・横は右が正。 */
    private record TireForces(double longitudinal, double lateral, double yawMoment) {

        static final TireForces NONE = new TireForces(0.0, 0.0, 0.0);
    }

    /**
     * 状態を dt 秒だけ進める。
     *
     * @param spec    車両諸元
     * @param state   書き換えられる状態
     * @param contact 各輪の直下にある接地面の高さ
     * @param dt      刻み幅 [s]
     * @param input   運転操作
     */
    public static void step(CarSpec spec, CarState state, GroundContact contact, double dt, CarInput input) {
        updateSteering(spec, state, contact, dt, input);
        updatePedals(spec, state, dt, input);
        updateDrivetrain(spec, state, dt, input);
        TireForces forces = computeTireForces(spec, state, contact, input, dt);
        updateSuspension(spec, state, contact, forces, dt);
        dampTumbling(spec, state, dt);

        double lateralSpeedBefore = state.lateralSpeed;
        double forwardSpeedBefore = state.forwardSpeed;
        updateVelocities(spec, state, contact, forces, dt, input);
        // 向きを進めるのはヨー角速度が確定した後。低速補正の前に積分すると、
        // 停止中にハンドルを切っただけで車が回ってしまう
        blendToKinematicAtLowSpeed(spec, state, dt);
        // 低速補正まで済んだ「実際に生じた」横加速度を残す。次のステップの荷重移動が使う。
        // 衝突などで速度が飛んだときに車体が横転しないよう、タイヤが出しうる範囲に収める
        double lateralAcceleration =
                (state.lateralSpeed - lateralSpeedBefore) / dt + state.forwardSpeed * state.yawRate;
        double limit = MAX_LATERAL_G * GRAVITY;
        state.lateralAcceleration = Math.max(-limit, Math.min(limit, lateralAcceleration));

        double longitudinalAcceleration = (state.forwardSpeed - forwardSpeedBefore) / dt;
        state.longitudinalAcceleration =
                Math.max(-limit, Math.min(limit, longitudinalAcceleration));

        state.yaw = wrapAngle(state.yaw + state.yawRate * dt);
    }

    // ------------------------------------------------------------------
    // ステアリング
    // ------------------------------------------------------------------

    /**
     * 前輪の切れ角。入力が 0/100 なので即座に上限まで振り切る。
     *
     * <p>上限は最大切れ角そのものではなく、<b>グリップを使いきる旋回に必要な切れ角</b>で
     * 頭打ちにする。100km/h で 35 度も切れば必要な横 G がグリップの 20 倍を超え、
     * 即スピンして走れたものではないため。実際のドライバーがグリップを超えて
     * ハンドルを切らないのと同じ意味の運転補助で、物理のごまかしではない。
     * アナログ入力を入れる段になったら外してよい。</p>
     */
    private static void updateSteering(CarSpec spec, CarState state, GroundContact contact,
                                       double dt, CarInput input) {
        double dir = input.analog()
                ? input.steerAxis()
                : (input.right() ? 1 : 0) - (input.left() ? 1 : 0);

        double limit = spec.maxSteerAngle();
        double speed = Math.abs(state.forwardSpeed);
        if (speed > 1.0) {
            double required = steerForMaxCornering(spec, state, contact, speed);
            if (Double.isFinite(required)) {
                limit = Math.min(limit, required * spec.steerGripMargin());
            }
        }

        // 舵を放している間は、前輪が自分から進行方向を向こうとする（セルフアライニングトルク）。
        // 直進していれば中立へ戻るだけだが、滑っているときはこれがそのままカウンターになる
        double target = dir != 0.0 ? limit * dir : selfAligningAngle(spec, state) * spec.selfAligning();
        target = Math.max(-limit, Math.min(limit, target));

        // 変化率の制限はアナログでもそのまま掛ける。<b>外すと横Gの立ち上がりが 41 G/s に達し、
        // 0/100 を直結していた頃と同じ「入れた瞬間にダートする」挙動に戻る</b>。
        // アナログで変わるのは上の target が棒の位置に比例することだけで、
        // 途中の角度を直接指示できるので、同じ制限でも操作は思いどおりになる
        boolean returning = Math.abs(target) < Math.abs(state.steerAngle)
                || target * state.steerAngle < 0.0;
        double seconds = returning ? spec.steerReturnSeconds() : spec.steerRateSeconds();
        state.steerAngle = approach(state.steerAngle, target, spec.maxSteerAngle(), seconds, dt);

        // 速度が上がって上限が下がったときは、そちらには即座に従う
        state.steerAngle = Math.max(-limit, Math.min(limit, state.steerAngle));
    }

    /**
     * 前輪のスリップ角が 0 になる切れ角 [rad]。舵を放したときに前輪が向こうとする向き。
     *
     * <p>タイヤの接地面は、路面に引きずられて<b>自分が進んでいる方向へ向こうとする</b>。
     * これが自己復元（セルフアライニング）トルクで、直進していれば中立へ戻る力になり、
     * 滑っているときはカウンターステアそのものになる。実車のドライバーが滑った瞬間に
     * ハンドルを緩めるだけである程度立て直せるのはこの働きによる。</p>
     */
    private static double selfAligningAngle(CarSpec spec, CarState state) {
        // 前軸の位置での横方向の速度。車体が回っているぶんが乗る
        // （腕は重心から前軸まで。前が重いほど短くなる）
        double frontLateral = state.lateralSpeed
                + spec.wheelForwardOffset(Wheel.FRONT_LEFT) * state.yawRate;
        double reference = Math.max(Math.abs(state.forwardSpeed), spec.slipReferenceSpeed());
        double angle = Math.atan2(frontLateral, reference);
        // 後退中は前輪が引きずられる向きが逆になる
        return state.forwardSpeed < 0.0 ? -angle : angle;
    }

    /** ペダルの踏み込み量を進める。 */
    /**
     * ペダルの踏み込み量。
     *
     * <p><b>アナログ入力には変化率の制限を掛けない。</b>制限はキーボードの 0/100 を
     * アナログに近づけるための細工なので、最初からアナログな入力に掛けると
     * <b>踏んだぶんだけ遅れて効く</b>ことになり、かえって操作しづらくなる。</p>
     */
    private static void updatePedals(CarSpec spec, CarState state, double dt, CarInput input) {
        if (input.analog()) {
            state.throttle = input.throttleAxis();
            state.brake = input.brakeAxis();
            return;
        }
        state.throttle = approach(state.throttle, input.throttle() ? 1.0 : 0.0, 1.0,
                input.throttle() ? spec.pedalPressSeconds() : spec.pedalReleaseSeconds(), dt);
        state.brake = approach(state.brake, input.brake() ? 1.0 : 0.0, 1.0,
                input.brake() ? spec.pedalPressSeconds() : spec.pedalReleaseSeconds(), dt);
    }

    /**
     * 現在値を目標へ近づける。全域を動くのに {@code seconds} かかる速さで、行き過ぎない。
     *
     * <p>キーが 0/100 しかなくても、ここを有限の速さにしておけば
     * <b>押している長さでアナログに操作できる</b>。短く叩けば少しだけ、長く押せば深く入る。</p>
     */
    private static double approach(double current, double target, double span, double seconds, double dt) {
        if (seconds <= 0.0) {
            return target;
        }
        double step = span / seconds * dt;
        double delta = target - current;
        return current + Math.max(-step, Math.min(step, delta));
    }

    /**
     * グリップを使いきって定常円旋回するのに必要な前輪の切れ角 [rad]。
     *
     * <p>線形 2 輪モデルの定常旋回では、必要な切れ角は</p>
     * <pre>  δ = L/R + (前輪スリップ角 - 後輪スリップ角)</pre>
     * <p>になる。第 1 項はタイヤが滑らない理想の幾何、第 2 項が
     * <b>タイヤは力を出すために滑る必要がある</b>ぶんの上乗せ。第 1 項だけで上限を決めると、
     * 高速域では実際の半径が幾何値の 2 倍以上にふくらんで、ほとんど曲がらない車になる。</p>
     *
     * <p>接地荷重が抜けている（ジャンプ中など）と値が定まらないので、
     * 有限でなければ呼び出し側で最大切れ角に戻す。</p>
     */
    private static double steerForMaxCornering(CarSpec spec, CarState state, GroundContact contact,
                                               double speed) {
        double frontLoad = state.wheelLoad[Wheel.FRONT_LEFT.ordinal()]
                + state.wheelLoad[Wheel.FRONT_RIGHT.ordinal()];
        double rearLoad = state.wheelLoad[Wheel.REAR_LEFT.ordinal()]
                + state.wheelLoad[Wheel.REAR_RIGHT.ordinal()];

        // 摩擦円。縦に使っているぶんを引いた残りが、横に回せるグリップ。
        // これを見ないと、フルブレーキ中でも「グリップを全部横に使える」前提で
        // 切れ角を許してしまい、後輪が耐えられずに尻から出る。
        // ただし残りが 0 になるまで絞ると制動中に一切曲がれなくなるので、
        // 横へ回すぶんは必ず一定割合を確保する（ABS の目的は制動しながら操舵できること）
        // 路面のグリップを織り込む。舗装路のμのまま上限を決めると、
        // 砂の上でも舗装前提の角度まで切れてしまい、切った瞬間にスピンする
        double gripAccel = spec.tireFriction() * contact.frontGripScale() * GRAVITY;
        double used = Math.min(gripAccel, Math.abs(state.longitudinalAcceleration));
        double remaining = Math.sqrt(Math.max(0.0, gripAccel * gripAccel - used * used));
        double targetAccel = Math.max(gripAccel * MIN_STEERING_GRIP, remaining);
        // ヨーモーメントが釣り合う配分。重心が前後中央なので前後で等分になる
        double axleForce = spec.mass() * targetAccel / 2.0;

        double frontSlip = axleForce / (spec.corneringStiffness() * frontLoad);
        double rearSlip = axleForce / (spec.corneringStiffness() * spec.rearCorneringBias() * rearLoad);

        // タイヤが滑らない理想の幾何。ここを下回らせてはいけない
        double geometric = spec.wheelBase() * targetAccel / (speed * speed);
        // タイヤが力を出すのに要るスリップ角ぶんの上乗せ。制動中は後軸の荷重が抜けて
        // オーバーステア傾向になり、この差が負になる。そのまま足すと上限が負に転じ、
        // 押した向きと逆にタイヤが向いてしまう（ブレーキ中に舵が効かない症状）
        return geometric + Math.max(0.0, frontSlip - rearSlip);
    }

    // ------------------------------------------------------------------
    // エンジンと変速機
    // ------------------------------------------------------------------

    /**
     * エンジン回転数・変速・駆動トルクを決める。
     *
     * <p>クラッチもトルクコンバータも持たず、<b>エンジンと駆動輪はギア比で直結</b>している。
     * そのままだと停止時にエンジンも止まってしまうので、回転数をアイドルで下支えして
     * 発進を成立させている（実際のクラッチ滑りやトルコンの増幅は再現していない）。</p>
     *
     * <p>エンジンと変速機の慣性は {@link #effectiveWheelInertia} で総減速比の 2 乗を掛けて
     * 車輪側へ換算する。<b>低いギアほど回転部分が重く感じられる</b>のはこの項の効果で、
     * ギアごとに効き方が変わるため車輪の慣性に定数として混ぜてはいけない。</p>
     */
    private static void updateDrivetrain(CarSpec spec, CarState state, double dt, CarInput input) {
        state.gear = selectGear(spec, state, dt, input);
        java.util.Arrays.fill(state.wheelDriveTorque, 0.0);

        // 駆動輪の回転からエンジン回転数を逆算する。停止時はアイドルで下支えする
        double ratio = spec.totalRatio(state.gear);
        double drivelineSpeed = drivenWheelSpeed(spec, state);
        double rpm = Math.abs(drivelineSpeed * ratio) * 60.0 / (2.0 * Math.PI);
        state.engineRpm = Math.max(spec.idleRpm(), rpm);

        if (state.shiftTimer > 0.0 || state.gear == 0) {
            // 変速中は駆動系が切れている（差動制限のイニシャルトルクだけは残る）
            state.engineTorque = 0.0;
            state.driveTorque = 0.0;
            updateDifferential(spec, state);
            return;
        }

        updateTractionControl(spec, state, dt);

        // マニュアルでは段を自分で入れるので、バックでも W がアクセル。
        // オートマは「停止して S でバック」なので、S がアクセルを兼ねる
        boolean accelerating = spec.isManual()
                ? input.throttle() && state.gear != 0
                : (input.throttle() && state.gear >= 1) || (input.brake() && state.gear == -1);

        double torque;
        if (accelerating) {
            torque = state.engineRpm >= spec.redlineRpm() ? 0.0 : spec.engineTorque(state.engineRpm);
        } else {
            // アクセルを戻すとエンジンブレーキ。回転が高いほど強い
            torque = -spec.engineBrakeTorque() * state.engineRpm / spec.redlineRpm();
        }
        // 駆動している間だけ絞る。エンジンブレーキまで弱めては意味が変わってしまう。
        // 後退中は S が「アクセル」なので、そちらの踏み込み量を使う
        if (torque > 0.0) {
            double pedal = !spec.isManual() && state.gear == -1 ? state.brake : state.throttle;
            torque *= state.tractionControlThrottle * pedal;
        }
        state.engineTorque = torque;

        // 後退は同じトルクを逆向きに掛ける
        double direction = state.gear == -1 ? -1.0 : 1.0;
        state.driveTorque = torque * ratio * spec.drivetrainEfficiency() * direction;

        for (Wheel wheel : Wheel.VALUES) {
            state.wheelDriveTorque[wheel.ordinal()] = state.driveTorque * spec.driveShare(wheel);
        }
        updateDifferential(spec, state);
    }

    /**
     * 機械式の差動制限（LSD）。左右の駆動輪のトルクをやり取りする。
     *
     * <p>そのままだと左右へ等トルクに配れるだけで、これは<b>オープンデフ</b>。コーナーで
     * 内輪の荷重が抜けると、その輪が空転してしまい外輪へ駆動力を渡せない。クラッチ式の
     * LSD は、左右の回転差に応じて<b>速い側から遅い側へトルクを移す</b>ことでこれを防ぐ。</p>
     *
     * <p>移せる量の上限は、イニシャルトルク（無入力でも効く分）＋駆動トルクに比例する分。
     * アクセルを戻したときの効きは {@code diffCoastRatio} で変えられる
     * （0 で 1way、0.5 で 1.5way、1 で 2way）。すべて 0 にすればオープンデフに戻る。</p>
     *
     * <p>移すだけなので軸の合計トルクは変わらない。</p>
     */
    private static void updateDifferential(CarSpec spec, CarState state) {
        lockAxle(spec, state, Wheel.FRONT_LEFT, Wheel.FRONT_RIGHT);
        lockAxle(spec, state, Wheel.REAR_LEFT, Wheel.REAR_RIGHT);
    }

    private static void lockAxle(CarSpec spec, CarState state, Wheel left, Wheel right) {
        // 駆動していない軸にはデフが無い。左右の車輪は完全に独立
        if (spec.driveShare(left) <= 0.0) {
            return;
        }
        int leftIndex = left.ordinal();
        int rightIndex = right.ordinal();

        double axleTorque = state.wheelDriveTorque[leftIndex] + state.wheelDriveTorque[rightIndex];
        double ratio = axleTorque < 0.0
                ? spec.diffLockRatio() * spec.diffCoastRatio()
                : spec.diffLockRatio();
        double capacity = spec.diffPreload() + ratio * Math.abs(axleTorque);
        if (capacity <= 0.0) {
            return;
        }

        double difference = state.wheelAngularVelocity[leftIndex] - state.wheelAngularVelocity[rightIndex];
        double transfer = spec.diffLockingRate() * difference;
        transfer = Math.max(-capacity, Math.min(capacity, transfer));

        state.wheelDriveTorque[leftIndex] -= transfer;
        state.wheelDriveTorque[rightIndex] += transfer;
    }

    /**
     * オートマチックの段選び。
     *
     * <p>前後どちらへ進むかは今までどおり車速の符号で決める。前進中は回転数を見て
     * 上げ下げし、変速の間は駆動力を抜く。</p>
     */
    private static int selectGear(CarSpec spec, CarState state, double dt, CarInput input) {
        if (state.shiftTimer > 0.0) {
            state.shiftTimer = Math.max(0.0, state.shiftTimer - dt);
            return state.gear;
        }
        if (spec.isManual()) {
            return selectGearManually(spec, state, input);
        }

        // 後退中に W を踏んだ、あるいは前進中に S を踏んだらブレーキ扱い。駆動はニュートラル
        if (input.throttle() && state.forwardSpeed < -spec.stopThreshold()) {
            return 0;
        }
        if (input.brake() && state.forwardSpeed > spec.stopThreshold()) {
            return 0;
        }
        if (input.brake()) {
            return -1; // 停止していれば S でバックに入る
        }
        if (state.gear <= 0) {
            return 1;
        }

        int gears = spec.forwardGears();
        if (state.engineRpm > spec.upshiftRpm() && state.gear < gears) {
            state.shiftTimer = spec.shiftSeconds();
            return state.gear + 1;
        }
        if (state.engineRpm < spec.downshiftRpm() && state.gear > 1) {
            state.shiftTimer = spec.shiftSeconds();
            return state.gear - 1;
        }
        return state.gear;
    }

    /**
     * マニュアルの段選び。R と F の要求で 1 段ずつ動かすだけ。
     *
     * <p>並びは バック(-1) → ニュートラル(0) → 1 速 → … 最高段。オートマのような
     * 「停止して S でバックに入る」挙動は無く、<b>バックも自分で入れる</b>。</p>
     *
     * <p><b>回りすぎる側への落とし込みは弾く。</b>高速で低い段へ放り込むと、駆動輪と
     * ギア比で直結しているエンジンがレッドラインを超える。実車なら壊れるところだが、
     * ここでは車輪の角速度をレッドラインで抑えている都合上、<b>入った瞬間に強烈な
     * エンジンブレーキが掛かって車が急減速する</b>という形で出る。実車のギアボックスにも
     * 同じ理由でロックアウトが付いている。</p>
     */
    private static int selectGearManually(CarSpec spec, CarState state, CarInput input) {
        int gear = state.gear;
        int requested = gear;
        if (input.shiftUp()) {
            requested = Math.min(spec.forwardGears(), gear + 1);
        } else if (input.shiftDown()) {
            requested = Math.max(-1, gear - 1);
        }
        if (requested == gear) {
            return gear;
        }
        if (Math.abs(requested) > Math.abs(gear) || requested == 0 || gear == 0) {
            // 上の段・ニュートラルの出入りは回転が下がる側なので無条件で通す
            state.shiftTimer = spec.shiftSeconds();
            return requested;
        }
        double rpm = Math.abs(drivenWheelSpeed(spec, state) * spec.totalRatio(requested))
                * 60.0 / (2.0 * Math.PI);
        if (rpm > spec.redlineRpm()) {
            return gear;
        }
        state.shiftTimer = spec.shiftSeconds();
        return requested;
    }

    /**
     * ABS。ロックしかけた輪のブレーキを緩める。
     *
     * <p>制動力の配分は固定なのに、ブレーキをかけると荷重は前へ移る。結果、<b>後輪だけが
     * 先にロックして横のグリップを失い、高速からのブレーキと舵で尻が出る</b>。
     * 実車で前輪から先にロックするよう配分を決めるのも、ABS を積むのも、これを避けるため。</p>
     *
     * <p><b>後軸は左右同圧にする（セレクトロー）。</b> 輪ごとに独立して制御すると、
     * わずかにヨーがあるだけで内外輪の滑り率が違い、ブレーキの解放量が左右でずれて
     * <b>ヨーを増幅してしまう</b>（外乱 0.03rad/s が 1.5 倍に育つ）。実車の ABS が
     * 後軸をセレクトローにしているのはこれを防ぐため。前軸は操舵性のため輪ごとに制御する。</p>
     *
     * <p>{@code absSlip} を 0 にすれば無効になり、ロックさせられる。</p>
     */
    private static void updateAbs(CarSpec spec, CarState state, double dt) {
        double response = 1.0 - Math.exp(-dt * ABS_RATE);
        if (!Double.isFinite(spec.absSlip())) {
            java.util.Arrays.fill(state.brakeRelease, 1.0);
            return;
        }
        // 後軸は左右同圧（セレクトロー）。前軸は輪ごとに制御して操舵性を残す
        modulateAxle(spec, state, response, Wheel.FRONT_LEFT, false);
        modulateAxle(spec, state, response, Wheel.FRONT_RIGHT, false);
        modulateAxle(spec, state, response, Wheel.REAR_LEFT, true);
    }

    /**
     * 1 輪ぶん、あるいは後軸まとめてブレーキを緩める。
     *
     * @param selectLow true なら軸の左右でロックの深い方に合わせ、同じ圧を掛ける
     */
    private static void modulateAxle(CarSpec spec, CarState state, double response,
                                     Wheel wheel, boolean selectLow) {
        int index = wheel.ordinal();
        double lock = -state.wheelSlipRatio[index];
        int partner = -1;
        if (selectLow) {
            partner = (wheel.isLeft()
                    ? (wheel.isFront() ? Wheel.FRONT_RIGHT : Wheel.REAR_RIGHT)
                    : (wheel.isFront() ? Wheel.FRONT_LEFT : Wheel.REAR_LEFT)).ordinal();
            lock = Math.max(lock, -state.wheelSlipRatio[partner]);
        }

        double excess = lock - spec.absSlip();
        double target = excess > 0.0 ? Math.max(0.0, 1.0 - excess * ABS_GAIN) : 1.0;
        double release = state.brakeRelease[index] + (target - state.brakeRelease[index]) * response;
        state.brakeRelease[index] = release;
        if (partner >= 0) {
            state.brakeRelease[partner] = release;
        }
    }

    /**
     * トラクションコントロール。駆動輪が滑りすぎたらアクセルを絞る。
     *
     * <p>アクセルが 0/100 しかないため、旋回中に全開にすると<b>駆動輪が必ず空転しきって</b>
     * 横のグリップを失い、パワーオーバーステアからスピンに至る。実車でこれを避けているのは
     * ドライバーのアクセル操作と、この装置。</p>
     *
     * <p>{@code tractionControlSlip} を 0 にすれば無効になり、アクセルで自由に流せる。</p>
     */
    private static void updateTractionControl(CarSpec spec, CarState state, double dt) {
        if (!Double.isFinite(spec.tractionControlSlip())) {
            state.tractionControlThrottle = 1.0;
            return;
        }

        double slip = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            if (spec.driveShare(wheel) > 0.0) {
                slip = Math.max(slip, state.wheelSlipRatio[wheel.ordinal()]);
            }
        }

        double excess = slip - spec.tractionControlSlip();
        double target = excess > 0.0 ? Math.max(0.0, 1.0 - excess * TRACTION_CONTROL_GAIN) : 1.0;
        // 一気に切ると駆動力が断続して暴れるので、なましてから効かせる
        double response = 1.0 - Math.exp(-dt * TRACTION_CONTROL_RATE);
        state.tractionControlThrottle += (target - state.tractionControlThrottle) * response;
    }

    /** 駆動輪の平均角速度 [rad/s]。 */
    private static double drivenWheelSpeed(CarSpec spec, CarState state) {
        double sum = 0.0;
        double weight = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            double share = spec.driveShare(wheel);
            if (share > 0.0) {
                sum += state.wheelAngularVelocity[wheel.ordinal()] * share;
                weight += share;
            }
        }
        return weight > 0.0 ? sum / weight : 0.0;
    }

    /**
     * その輪の実効的な慣性モーメント [kg*m^2]。
     *
     * <p>駆動輪には、エンジンと変速機の慣性が総減速比の 2 乗に比例して乗ってくる。
     * 1 速では数十 kg*m^2 に達し、最高段では車輪単体とほとんど変わらない。</p>
     */
    private static double effectiveWheelInertia(CarSpec spec, CarState state, Wheel wheel) {
        double share = spec.driveShare(wheel);
        if (share <= 0.0 || state.shiftTimer > 0.0 || state.gear == 0) {
            return spec.wheelInertia();
        }
        double ratio = spec.totalRatio(state.gear);
        return spec.wheelInertia() + spec.engineInertia() * ratio * ratio * share;
    }

    // ------------------------------------------------------------------
    // タイヤ
    // ------------------------------------------------------------------

    /**
     * 各輪が出す力を合計する。
     *
     * <p>タイヤは縦（駆動・制動）と横（旋回）でひとつのグリップを分け合う。合力の大きさが
     * 摩擦係数×接地荷重を超えたら、縦横まとめて同じ比率で削る——これが<b>摩擦円</b>。
     * 全開で加速しながらフルグリップで曲がることはできなくなり、アクセルを踏めば横が抜ける。</p>
     *
     * <p>縦力は車輪の回転から求める。駆動トルクで車輪が路面より速く回れば空転（滑り率が正）、
     * ブレーキで止まればロック（-1）。どちらも合力を飽和させるので横のグリップが消える。</p>
     */
    private static TireForces computeTireForces(CarSpec spec, CarState state, GroundContact contact,
                                                CarInput input, double dt) {
        if (!state.isGrounded()) {
            // 空中では車輪に路面からの反力が無い。回転はそのまま保つ（着地で滑り率が跳ねる）
            return TireForces.NONE;
        }

        double brakeForce = commandedBrakeForce(spec, state, input);
        updateAbs(spec, state, dt);

        double longitudinal = 0.0;
        double lateral = 0.0;
        double yawMoment = 0.0;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double load = state.wheelLoad[index];
            if (load <= 0.0) {
                // 浮いている輪は路面へ力を出せないが、駆動トルクでは回る。
                // ここで回さずに放置すると角速度が止まったままになり、
                // 差動制限が「こちらが遅い輪だ」と誤認してトルクを空転側へ送ってしまう
                spinFreeWheel(spec, state, wheel, dt, brakeForce * spec.brakeShare(wheel));
                continue;
            }
            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);

            // 車輪の位置での速度。車体の回転ぶんが乗るので、前輪と後輪でずれる
            double wheelForward = state.forwardSpeed - rightOffset * state.yawRate;
            double wheelRight = state.lateralSpeed + forwardOffset * state.yawRate;

            // 速度をタイヤ自身の向きへ回してから測る。こうしておくと、
            // 後退時にタイヤが「前進している」と誤認して力の向きが反転するのを避けられる
            double steer = wheel.isFront() ? state.steerAngle : 0.0;
            double cos = Math.cos(steer);
            double sin = Math.sin(steer);
            double rollingSpeed = wheelForward * cos + wheelRight * sin;
            double slidingSpeed = -wheelForward * sin + wheelRight * cos;

            // 縦横どちらの滑りも、速度が 0 に近づくと角度・比が跳ね上がる。
            // 分母に下限を入れて正則化する（これを怠ると、停止寸前にスリップ角が 90 度へ飛んで
            // 横力が摩擦円を食い潰し、発進もバックもできない車になる）
            double referenceSpeed = Math.max(Math.abs(rollingSpeed), spec.slipReferenceSpeed());

            // 横: スリップ角に比例して立ち上がる。
            // 後輪を少し硬くしておくと、限界でまず前が逃げる（アンダーステア）性格になる
            double slipAngle = Math.atan2(slidingSpeed, referenceSpeed);
            double corneringStiffness =
                    spec.corneringStiffness() * (wheel.isFront() ? 1.0 : spec.rearCorneringBias()) * load;
            double lateralForce = -corneringStiffness * slipAngle;

            // 縦: まず車輪の回転をグリップしている前提で進め、滑り率を求める
            double longitudinalStiffness = spec.longitudinalStiffness() * load;
            double inertia = effectiveWheelInertia(spec, state, wheel);
            double driveTorque = state.wheelDriveTorque[index];
            // 制動力はブレーキ配分、転がり抵抗は接地荷重の比で分ける。
            // 荷重に比例させておくと、浮いている輪に転がり抵抗が掛からないのが自動で成り立ち、
            // 片輪だけ砂に落ちたときの左右差がそのままヨーモーメントになる
            double wheelBrakeForce = brakeForce * spec.brakeShare(wheel) * state.brakeRelease[index]
                    + rollingResistanceForce(spec, contact, wheel, load)
                    + handbrakeForce(spec, input, wheel);
            double brakeTorque = brakeTorque(spec, state, wheel, dt, inertia, wheelBrakeForce);

            double angularVelocity = advanceGrippingWheel(spec, state, wheel, dt, inertia,
                    driveTorque, brakeTorque, rollingSpeed, referenceSpeed, longitudinalStiffness);
            double slipRatio = clampSlipRatio(
                    (angularVelocity * spec.wheelRadius() - rollingSpeed) / referenceSpeed);
            double longitudinalForce = longitudinalStiffness * slipRatio;

            // 摩擦円。合力が上限を超えたぶんを縦横まとめて削る
            double gripLimit = spec.tireFriction() * contact.gripScale(wheel) * load;
            double magnitude = Math.hypot(longitudinalForce, lateralForce);
            if (magnitude > gripLimit) {
                double scale = gripLimit / magnitude;
                longitudinalForce *= scale;
                lateralForce *= scale;

                // 滑っている間、路面が返す縦力は車輪の回転にほぼ依らない一定値になる。
                // そうなると方程式は固くなくなるので、ここだけ素直に積分してよい。
                // 掴みきれなかったぶんのトルクが車輪を空転（またはロック）させる
                angularVelocity = clampAngularVelocity(spec,
                        state.wheelAngularVelocity[index] + dt / inertia
                                * (driveTorque + brakeTorque - longitudinalForce * spec.wheelRadius()),
                        rollingSpeed, referenceSpeed);
                slipRatio = clampSlipRatio(
                        (angularVelocity * spec.wheelRadius() - rollingSpeed) / referenceSpeed);
            }

            state.wheelAngularVelocity[index] = clampToRedline(spec, state, wheel, angularVelocity);
            state.wheelSlipRatio[index] = slipRatio;

            // 接地面で捨てている摩擦の仕事率。タイヤの力 × 実際の滑り速度。
            // 滑り角ではなく速度を使うので、止まりかけでは自然に 0 へ落ちる
            double slipSpeed = Math.hypot(angularVelocity * spec.wheelRadius() - rollingSpeed, slidingSpeed);
            state.wheelFrictionPower[index] = Math.hypot(longitudinalForce, lateralForce) * slipSpeed;

            // タイヤの力を車体基準へ戻す。切れ角のぶん、縦力と横力が混ざる
            double bodyForward = longitudinalForce * cos - lateralForce * sin;
            double bodyRight = longitudinalForce * sin + lateralForce * cos;

            longitudinal += bodyForward;
            lateral += bodyRight;
            // 位置 (前後 a, 左右 b) に働く力 (前 Ff, 右 Fr) が生むヨーモーメントは Fr*a - Ff*b。
            // 左右で縦力が違えば（片輪だけ空転など）それもヨーを生む
            yawMoment += bodyRight * forwardOffset - bodyForward * rightOffset;
        }

        return new TireForces(longitudinal, lateral, yawMoment);
    }

    /** 接地していない輪を、駆動トルクとブレーキだけで回す。 */
    private static void spinFreeWheel(CarSpec spec, CarState state, Wheel wheel, double dt, double brakeForce) {
        int index = wheel.ordinal();
        double inertia = effectiveWheelInertia(spec, state, wheel);
        double brake = brakeTorque(spec, state, wheel, dt, inertia,
                brakeForce * spec.brakeShare(wheel) * state.brakeRelease[index]);
        double next = state.wheelAngularVelocity[index]
                + dt / inertia * (state.wheelDriveTorque[index] + brake);
        state.wheelAngularVelocity[index] = clampToRedline(spec, state, wheel, next);
        state.wheelSlipRatio[index] = 0.0;
        // 路面に触れていないので摩擦は生じない。空転していても煙は出ない
        state.wheelFrictionPower[index] = 0.0;
    }

    /**
     * 駆動輪の回転を、エンジンのレブリミットから決まる上限で抑える。
     *
     * <p>駆動輪はギア比でエンジンと直結しているので、エンジンが回れる以上には回らない。
     * 空転しても際限なく吹け上がらないのはこのため。</p>
     */
    private static double clampToRedline(CarSpec spec, CarState state, Wheel wheel, double angularVelocity) {
        double ratio = Math.abs(spec.totalRatio(state.gear));
        if (spec.driveShare(wheel) <= 0.0 || ratio <= 0.0) {
            return angularVelocity;
        }
        double max = spec.redlineRpm() * 2.0 * Math.PI / 60.0 / ratio;
        return Math.max(-max, Math.min(max, angularVelocity));
    }

    /**
     * グリップしている前提で車輪の回転を dt だけ進める。
     *
     * <p>グリップ域では縦力が滑り率に比例するため、方程式が非常に固くなる
     * （既定の諸元では時定数が 0.3ms 程度）。12.5ms 刻みで素直に積分すると必ず発散するので、
     * <b>タイヤの縦力だけ陰的に扱い</b>、次の角速度について解いた式を直接使う。
     * これで刻みを細かくせずに安定する。</p>
     *
     * <pre>
     *   I(ω' - ω)/dt = 駆動 + 制動 - Kx(ω'r - v)/V * r
     *   ω' = (ω + dt/I(駆動 + 制動 + Kx*r*v/V)) / (1 + dt*Kx*r^2/(I*V))
     * </pre>
     *
     * <p>滑り出したら縦力が一定になって固さが消えるので、呼び出し側で素直な積分に切り替える。</p>
     */
    private static double advanceGrippingWheel(CarSpec spec, CarState state, Wheel wheel, double dt,
                                               double inertia, double driveTorque, double brakeTorque,
                                               double rollingSpeed, double referenceSpeed,
                                               double longitudinalStiffness) {
        double radius = spec.wheelRadius();
        double angularVelocity = state.wheelAngularVelocity[wheel.ordinal()];

        double implicitTerm = dt * longitudinalStiffness * radius * radius / (inertia * referenceSpeed);
        double next = (angularVelocity + dt / inertia
                * (driveTorque + brakeTorque + longitudinalStiffness * radius * rollingSpeed / referenceSpeed))
                / (1.0 + implicitTerm);
        return clampAngularVelocity(spec, next, rollingSpeed, referenceSpeed);
    }

    /**
     * 車輪の回転を止める向きのトルク。
     *
     * <p>この 1 ステップで止まりきる以上には効かせない。行き過ぎると車輪が逆回転してしまう。</p>
     */
    private static double brakeTorque(CarSpec spec, CarState state, Wheel wheel, double dt,
                                      double inertia, double brakeForce) {
        double angularVelocity = state.wheelAngularVelocity[wheel.ordinal()];
        if (brakeForce <= 0.0 || angularVelocity == 0.0) {
            return 0.0;
        }
        double stoppingTorque = Math.abs(angularVelocity) * inertia / dt;
        return -Math.signum(angularVelocity)
                * Math.min(brakeForce * spec.wheelRadius(), stoppingTorque);
    }

    /** 滑り率が現実離れした値にならないよう角速度を抑える。 */
    private static double clampAngularVelocity(CarSpec spec, double angularVelocity,
                                               double rollingSpeed, double referenceSpeed) {
        double span = MAX_SLIP_RATIO * referenceSpeed;
        double max = (rollingSpeed + span) / spec.wheelRadius();
        double min = (rollingSpeed - span) / spec.wheelRadius();
        return Math.max(min, Math.min(max, angularVelocity));
    }

    private static double clampSlipRatio(double slipRatio) {
        return Math.max(-MAX_SLIP_RATIO, Math.min(MAX_SLIP_RATIO, slipRatio));
    }

    /**
     * ブレーキが車輪の回転を止める向きに掛ける力 [N]。常に正。
     *
     * <p>転がり抵抗はここには含めない。路面で変わるうえ接地荷重に比例するので、
     * 輪ごとに {@link #rollingResistanceForce} で足す。</p>
     */
    private static double commandedBrakeForce(CarSpec spec, CarState state, CarInput input) {
        double full = spec.brakeDecel() * spec.mass();

        if (spec.isManual()) {
            // 段を自分で入れるので、S は前進中でも後退中でも純粋にブレーキ。
            // 向きは brakeTorque が車輪の回転を見て決める
            return full * state.brake;
        }
        if (input.throttle()) {
            // 後退中の W はブレーキ扱い。アクセルの踏み込み量をそのまま踏力に使う
            return state.forwardSpeed < -spec.stopThreshold() ? full * state.throttle : 0.0;
        }
        if (input.brake()) {
            return state.forwardSpeed > spec.stopThreshold() ? full * state.brake : 0.0;
        }
        return 0.0;
    }

    /**
     * サイドブレーキが掛ける力 [N]。
     *
     * <p><b>後輪だけに掛かり、ABS を通さない。</b>後輪をロックさせて車を回すための装置なので、
     * ロックを防ぐ ABS を通してしまっては意味がない。</p>
     */
    private static double handbrakeForce(CarSpec spec, CarInput input, Wheel wheel) {
        if (!input.handbrake() || wheel.isFront()) {
            return 0.0;
        }
        return spec.handbrakeDecel() * spec.mass() / 2.0;
    }

    /**
     * その輪に掛かる転がり抵抗 [N]。
     *
     * <p>諸元の {@code rollingResistance} は車全体の減速度なので、全輪ぶんを足すと
     * {@code rollingResistance * mass} に戻るよう、接地荷重の比で分ける
     * （全荷重の合計は 車重×g なので、荷重÷g がそのまま質量ぶんの取り分になる）。</p>
     */
    private static double rollingResistanceForce(CarSpec spec, GroundContact contact,
                                                 Wheel wheel, double load) {
        return spec.rollingResistance() * contact.rollingScale(wheel) * load / GRAVITY;
    }

    // ------------------------------------------------------------------
    // サスペンション
    // ------------------------------------------------------------------

    /**
     * サスペンション。各輪のバネとダンパーが出す力から、車体の上下・ピッチ・ロールを解く。
     *
     * <p>タイヤの力は接地面（高さ 0）に働くのに対し、その反作用は重心（高さ
     * {@code cgHeight}）に働く。この高さの差がモーメントになり、ブレーキで前が沈み、
     * コーナーで外側へ傾く——つまり荷重移動が生まれる。</p>
     *
     * <p>ロール側だけはタイヤの横力ではなく<b>実際に生じている横加速度</b>から求める。
     * 低速補正が速度を書き換えるので、低速域ではタイヤ力と実際の加速度が食い違い、
     * タイヤ力を使うと<b>1km/h でほとんど横 G が掛かっていないのに車体が数度傾く</b>。</p>
     */
    private static void updateSuspension(CarSpec spec, CarState state, GroundContact contact,
                                         TireForces forces, double dt) {
        double totalLoad = 0.0;
        double pitchMoment = 0.0;
        double rollMoment = 0.0;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);

            // 地面が無い（NEGATIVE_INFINITY）ときは伸びが無限大になり、
            // 下の clamp で伸びきり長に落ちて荷重 0 になる
            double gap = state.hardpointHeight(forwardOffset, rightOffset)
                    - contact.groundHeight[index] - spec.wheelRadius();
            double length = Math.min(spec.suspensionMaxLength(), gap);
            state.suspensionLength[index] = Math.max(0.0, length);

            // gap が負ならストロークを使いきってバンプストップに乗っている
            double compression = spec.suspensionMaxLength() - length;
            double springTravel = Math.min(compression, spec.suspensionMaxLength());
            double bumpStopTravel = Math.max(0.0, compression - spec.suspensionMaxLength());

            double load = 0.0;
            if (compression > 0.0) {
                // ダンパーはハードポイントが上がる（＝伸びる）ほど荷重を減らす向きに効く
                double speed = state.hardpointVerticalSpeed(forwardOffset, rightOffset);
                load = spec.suspensionStiffness(wheel) * springTravel
                        + spec.bumpStopStiffness(wheel) * bumpStopTravel
                        - spec.suspensionDamping(wheel) * speed;
                // サスペンションは押すだけで引っ張れない。上限は停車時荷重の何倍かで置くので、
                // 荷重配分の軽い側は上限も低い
                load = Math.max(0.0, Math.min(spec.staticWheelLoad(wheel) * MAX_LOAD_FACTOR, load));
            }
            state.wheelLoad[index] = load;
        }

        applyAntiRollBars(spec, state);

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double load = state.wheelLoad[index];
            totalLoad += load;
            // 前側を持ち上げる力は鼻上げ、右側を持ち上げる力はロールを戻す向き
            pitchMoment += load * spec.wheelForwardOffset(wheel);
            rollMoment -= load * spec.wheelRightOffset(wheel);
        }

        // 荷重移動。前向きの力は鼻上げ（スクワット）、右向きの加速度は左下がり（外へ傾く）
        pitchMoment += forces.longitudinal() * spec.cgHeight();
        rollMoment -= state.lateralAcceleration * spec.mass() * spec.cgHeight();

        state.verticalSpeed += (totalLoad / spec.mass() - GRAVITY) * dt;
        state.height += state.verticalSpeed * dt;

        state.pitchRate += (pitchMoment / spec.pitchInertia()) * dt;
        state.pitch = wrapAngle(state.pitch + state.pitchRate * dt);

        state.rollRate += (rollMoment / spec.rollInertia()) * dt;
        // ±180 度に巻き取る。裏返って回り続けたときに角度が青天井に増えるのを防ぐ
        // （同期する float の精度が落ちるうえ、立ち直りの向きも決められなくなる）
        state.roll = wrapAngle(state.roll + state.rollRate * dt);
    }

    /**
     * 横転したまま<b>止まってしまった</b>ときの減衰。
     *
     * <p><b>起こす力は加えない。</b>転がる勢いを削るだけで、足を下へ向けるのは
     * サスペンションの仕事——タイヤが地面を押し返す力は、傾いた車体を水平へ戻す向きに
     * 働くので、勢いさえ削れば車は自分で起き上がる。</p>
     *
     * <p>効くのは 2 つそろったときだけ:</p>
     *
     * <ul>
     *   <li><b>止まっている</b>（前後・横の合成速度が {@code stopThreshold} 以下）。
     *       転がっている最中は自由に転がってよい——事故の見え方には手を入れず、
     *       <b>収拾がつかなくなったところだけ収める</b></li>
     *   <li><b>大きく傾いている</b>（{@link #TUMBLE_START} から効きはじめ
     *       {@link #TUMBLE_FULL} で全開）。停まっている車の揺れ戻りまで殺さないため</li>
     * </ul>
     *
     * <p><b>接地は問わない。</b>以前は「空中で効かせるとジャンプで崩した体勢が勝手に
     * 直ってしまう」ため接地を条件にしていたが、<b>止まっていることを条件にした時点で
     * その心配は無くなる</b>——飛んでいる車には必ず速度があるので、空中で効くのは
     * 落下しているだけの車だけ。走行中に効かないことも速度の条件から自明になる。</p>
     *
     * <p>強さは車の性格ではなく「収拾のつかない姿勢から抜け出せるか」だけで決まるので、
     * 諸元には持たせず {@link #TUMBLE_DAMPING_PER_TICK} に固定してある。</p>
     */
    private static void dampTumbling(CarSpec spec, CarState state, double dt) {
        // 止まっているときだけ。転がっている最中は勢いを削らない
        if (Math.hypot(state.forwardSpeed, state.lateralSpeed) > spec.stopThreshold()) {
            return;
        }
        // 車体の上向きと世界の上向きのなす角。水平で 0、裏返しで π
        double upright = Math.cos(state.pitch) * Math.cos(state.roll);
        double tilt = Math.acos(Math.max(-1.0, Math.min(1.0, upright)));
        if (tilt <= TUMBLE_START) {
            return;
        }
        double blend = Math.min(1.0, (tilt - TUMBLE_START) / (TUMBLE_FULL - TUMBLE_START));
        blend = blend * blend * (3.0 - 2.0 * blend); // 効きはじめを滑らかに

        // 「1 ティックで 30%」を刻み幅へ割り直す。サブステップ数を変えても効きが変わらない
        double perStep = 1.0 - Math.pow(1.0 - TUMBLE_DAMPING_PER_TICK, dt / TICK_SECONDS);
        double decay = Math.min(1.0, blend * perStep);
        state.rollRate -= state.rollRate * decay;
        state.pitchRate -= state.pitchRate * decay;
    }

    /**
     * 止まって横転しているときに、1 ティックで角速度がどれだけ減るか。
     *
     * <p>走行中も効かせていた頃は<b>接地しているのがぶつかった一瞬だけ</b>だったので
     * 8 割ぶん削らないと収まらなかった。止まってからだけ効かせるならずっと接地して
     * いるので、この強さで足りる。</p>
     */
    private static final double TUMBLE_DAMPING_PER_TICK = 0.30;

    /** 1 ティックの秒数 [s]。上の割合をサブステップの刻みへ割り直すのに使う。 */
    private static final double TICK_SECONDS = 0.05;

    /**
     * スタビライザー。左右のサス変位の差に応じて、沈んでいる側の荷重を増やし、
     * 伸びている側から同じだけ減らす。
     *
     * <p><b>左右が同じだけ沈む動き（＝上下）には効かず、逆に動く動き（＝ロール）にだけ効く。</b>
     * だからバネを緩めたまま——沈み込みもノーズダイブも柔らかいまま——ロールだけを締められる。
     * 実車のセッティングで前後のロール剛性配分を決めているのがこれで、
     * 前を強くするとアンダー、後ろを強くするとオーバーになる。</p>
     *
     * <p><b>移す量は「軽い側が手放せるぶん」で頭打ちにすること。</b> 荷重は 0 未満になれない
     * （サスは押すだけで引けない）ので、そのまま足し引きすると<b>減らした側が 0 で止まる一方
     * 増やした側はそのまま残り、合計荷重が車重を上回る</b>。内輪が浮くほど強いスタビでも、
     * 実際に移せるのは内輪の荷重を 0 にするところまで——そこから先はバーがねじれず、
     * 内輪が持ち上がるだけ。頭打ちにしておけば力の対が必ず打ち消し合い、
     * 合計荷重の保存もそのまま成り立つ。</p>
     */
    private static void applyAntiRollBars(CarSpec spec, CarState state) {
        for (int axle = 0; axle < 2; axle++) {
            Wheel left = axle == 0 ? Wheel.FRONT_LEFT : Wheel.REAR_LEFT;
            Wheel right = axle == 0 ? Wheel.FRONT_RIGHT : Wheel.REAR_RIGHT;
            double stiffness = spec.antiRollStiffness(left);
            if (stiffness <= 0.0) {
                continue;
            }
            int l = left.ordinal();
            int r = right.ordinal();
            // 伸びきった側は変位 0。suspensionLength は [0, ストローク] に収めてあるので、
            // 差を取るだけで縮み量になる
            double travelLeft = spec.suspensionMaxLength() - state.suspensionLength[l];
            double travelRight = spec.suspensionMaxLength() - state.suspensionLength[r];

            // 左が余計に縮んでいれば、左を地面へ押し付けて右を持ち上げる向き
            double transfer = stiffness * (travelLeft - travelRight);
            transfer = Math.max(-state.wheelLoad[l], Math.min(state.wheelLoad[r], transfer));

            state.wheelLoad[l] += transfer;
            state.wheelLoad[r] -= transfer;
        }
    }

    // ------------------------------------------------------------------
    // 車体の運動
    // ------------------------------------------------------------------

    /**
     * 車体の速度と向きを進める。
     *
     * <p>車体基準の座標系は車と一緒に回るため、力を質量で割っただけでは足りず、
     * 回転による見かけの項（{@code lateralSpeed * yawRate} など）が入る。</p>
     */
    private static void updateVelocities(CarSpec spec, CarState state, GroundContact contact,
                                         TireForces forces, double dt, CarInput input) {
        // 空気抵抗はタイヤと無関係にかかるので、グリップの上限とは別に足す
        double dragAccel = -spec.dragCoefficient() * state.forwardSpeed * Math.abs(state.forwardSpeed);

        double forwardAccel = forces.longitudinal() / spec.mass() + state.lateralSpeed * state.yawRate + dragAccel;
        double lateralAccel = forces.lateral() / spec.mass() - state.forwardSpeed * state.yawRate;

        state.forwardSpeed += forwardAccel * dt;
        state.lateralSpeed += lateralAccel * dt;
        state.yawRate += (forces.yawMoment() / spec.yawInertia()) * dt;

        // 無操作で止まりきる直前は、転がり抵抗の符号が反転して逆走しないよう 0 に落とす
        if (!input.throttle() && !input.brake()
                && Math.abs(state.forwardSpeed)
                        <= spec.rollingResistance() * averageRollingScale(state, contact) * dt) {
            state.forwardSpeed = 0.0;
        }
        state.forwardSpeed = Math.max(-spec.maxReverseSpeed(), Math.min(spec.maxSpeed(), state.forwardSpeed));
    }

    /** 接地荷重で重み付けした転がり抵抗の倍率。停止しきい値に使う。 */
    private static double averageRollingScale(CarState state, GroundContact contact) {
        double weighted = 0.0;
        double total = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            double load = state.wheelLoad[wheel.ordinal()];
            weighted += contact.rollingScale(wheel) * load;
            total += load;
        }
        return total > 0.0 ? weighted / total : 1.0;
    }

    /**
     * 低速域で運動学モデルへ寄せる。
     *
     * <p>スリップ角は進行方向の角度なので、停止寸前には僅かな横速度でも角度が跳ね上がり、
     * 数値が振動する。ここは実車シムでも定番の難所で、低速では
     * 「切れ角どおりに曲がる」運動学モデルへ滑らかに戻すのが手堅い。</p>
     *
     * <p><b>速いか遅いかは前後方向だけで判断してはいけない。</b> 大きく横に滑っている車は
     * ほとんど横を向いていて、前後速度は小さいのに横速度は大きい。前後速度だけを見ると
     * これを「止まりかけ」と誤判定し、<b>横速度を 1 ティックで 0 に潰して急停止させる</b>。
     * さらにその急変が巨大な横加速度として荷重移動に入り、車体が横転する。</p>
     *
     * <p><b>横速度を 0 に寄せてはいけない。</b> 運動学的な旋回では旋回中心が後軸上に来るので、
     * 重心はホイールベースの半分にヨー角速度を掛けたぶんの横速度を持つ。これを 0 にすると
     * 後輪に実在しないスリップ角が立ち、1km/h でも数千 N の横力が出る。走りには
     * 影響しないが（横速度は次のステップでまた潰されるため）、その横力は荷重移動の
     * モーメントに入るので、<b>ほとんど止まっているのに車体が傾く</b>。</p>
     */
    private static void blendToKinematicAtLowSpeed(CarSpec spec, CarState state, double dt) {
        double speed = Math.hypot(state.forwardSpeed, state.lateralSpeed);
        double blend = Math.min(1.0, speed / spec.lowSpeedBlendSpeed());
        if (blend >= 1.0) {
            return;
        }
        double kinematicYawRate = (state.forwardSpeed / spec.wheelBase()) * Math.tan(state.steerAngle);
        // 旋回中心は後軸上。重心はそこから前へ離れているので、その距離×角速度が横速度になる
        // （距離は重心から後軸まで。前が重いほど長くなる）
        double kinematicLateralSpeed = kinematicYawRate * -spec.wheelForwardOffset(Wheel.REAR_LEFT);

        // 一気に置き換えず、時定数を持たせて寄せる。置き換えてしまうと滑り終わりの
        // 1 ティックで速度が飛び、その急変が横加速度として荷重移動に入る
        double relaxation = 1.0 - Math.exp(-dt * (1.0 - blend) * KINEMATIC_RELAXATION_RATE);
        state.yawRate += (kinematicYawRate - state.yawRate) * relaxation;
        state.lateralSpeed += (kinematicLateralSpeed - state.lateralSpeed) * relaxation;
    }

    /** 角度を -PI 〜 PI に収める */
    /** 転がりの減衰が効きはじめる傾き [rad]。普通に走る範囲（〜10 度）から充分離す。 */
    private static final double TUMBLE_START = Math.toRadians(60.0);
    /** 転がりの減衰が全開になる傾き [rad]。 */
    private static final double TUMBLE_FULL = Math.toRadians(110.0);

    private static double wrapAngle(double radians) {
        double a = radians % (Math.PI * 2.0);
        if (a >= Math.PI) a -= Math.PI * 2.0;
        if (a < -Math.PI) a += Math.PI * 2.0;
        return a;
    }
}
