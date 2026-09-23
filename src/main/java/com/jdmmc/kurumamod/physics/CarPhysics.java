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

    /**
     * トラクションコントロールの効きの強さ。滑り率の超過ぶんに掛けてアクセルを絞る。
     *
     * <p>超過はピーク滑り率で割った尺度で測る（{@code aidSlipScale}）。比例なので閾値の上に
     * 居座る量はこのゲインで決まり、3 だとピークをかなり越える。ピークを越えると落ちる
     * タイヤ（{@code tireFalloff} 0.3）では、後輪駆動の舵＋全開が舗装で車体スリップ角 50〜56 度
     * （スピン）になった。10 で 17〜24 度に収まり、0-100 は舗装 5.35→5.36 秒。</p>
     */
    private static final double TRACTION_CONTROL_GAIN = 10.0;
    /** トラクションコントロールの応答の速さ [1/s]。速すぎると振動する。 */
    private static final double TRACTION_CONTROL_RATE = 30.0;

    /**
     * ABS がブレーキを緩める速さ [1/s]。滑り率が閾値をピーク滑り率 1 つぶん超えているとき、
     * 1 秒あたりに緩める割合。<b>積分で効かせる</b>（{@link #modulateAxle}）。
     */
    private static final double ABS_INTEGRAL_RATE = 10.0;

    /**
     * 縦にグリップを使いきっていても、横へ回せるぶんとして残す割合。
     *
     * <p>0 にすると全制動中に舵が完全に死ぬ。実車の ABS が目指すのは
     * 「ロックさせずに操舵性を残すこと」なので、それに倣って確保しておく。</p>
     */
    private static final double MIN_STEERING_GRIP = 0.45;

    /**
     * 切り込みの上乗せ。まだグリップを使っていないうちだけ、切れ角の上限をこの割合だけ広げる。
     * 効き方と必要なガードは {@link #turnInLimit}。
     *
     * <p>2.0 は「まだ何もしていない状態でだけ定常角度の 3 倍まで許す」という上限で、
     * <b>実際に切り込む量はその半分ほど</b>——定常角度の 1.46 倍（72km/h）・1.81 倍（100km/h）・
     * 2.33 倍（220km/h）で、実車のドライバーの切り込み量と同じ帯に収まる。</p>
     *
     * <p>これ以上上げても<b>割に合わない</b>（定速スキッドパッドの実測。spin は切り返し
     * 560 条件のうちスピンした数で、上乗せ無しでも 18 件ある）:</p>
     *
     * <table>
     *   <tr><th>強さ</th><th>spin</th><th>t90 72km/h</th><th>t90 100km/h</th><th>t90 220km/h</th><th>切り込み/定常 (220km/h)</th></tr>
     *   <tr><td>0（無し）</td><td>18</td><td>0.65s</td><td>0.88s</td><td>2.06s</td><td>1.13</td></tr>
     *   <tr><td><b>2.0</b></td><td>28</td><td>0.53s</td><td>0.66s</td><td>1.50s</td><td>2.33</td></tr>
     *   <tr><td>4.0</td><td>29</td><td>0.51s</td><td>0.63s</td><td>1.34s</td><td>3.38</td></tr>
     *   <tr><td>6.0</td><td>30</td><td>0.51s</td><td>0.61s</td><td>1.26s</td><td>4.22</td></tr>
     * </table>
     *
     * <p>低中速では 2.0 で頭打ちになり（前輪のピークで蓋をされるため）、高速では伸び続けるが、
     * 切り込みが定常の 4 倍を超えて<b>ドライバーの操作としては不自然</b>になる。</p>
     *
     * <p><b>調整画面には出さない。</b>車の性格ではなく「人はハンドルをこう回す」という話で、
     * しかも {@code steerGripMargin} という同じ向きのつまみが既にある——2 つ並べると
     * どちらが効いているのか分からなくなる。</p>
     */
    private static final double TURN_IN_BONUS = 2.0;

    /**
     * 舵の変化率の基準にする範囲の下限。最大切れ角に対する割合。
     *
     * <p>変化率は「いま使える切れ角の範囲」を基準にするので、範囲が 0 に近づくと
     * 舵がまったく動かなくなる。氷の上のように上限が 0.3 度まで絞られる場面のための下限。</p>
     */
    private static final double MIN_STEER_SPAN = 0.02;

    /**
     * クラッチを切りきるまでの時間 [s]。速くてよい——切るのは踏むだけなので。
     *
     * <p>0 にすると駆動トルクが 1 ティックで断続するので、<b>変速を 0/1 で
     * 切り替えてはいけない</b>のと同じ理由で有限にしてある（{@link #shiftEngagement} 参照）。</p>
     */
    private static final double CLUTCH_RELEASE_SECONDS = 0.08;

    /**
     * クラッチを繋ぎきるまでの時間 [s]。<b>切るときよりずっと遅くすること。</b>
     *
     * <p>ここが速いと、空吹かししたエンジンが車輪の回転へ<b>一瞬で引き戻される</b>。
     * 実測で 0.12 秒だと 1 ティック（12.5ms）に 1001rpm 落ち、回転計も排気音も跳ねる。
     * 0.30 秒にすると 1 ティックあたり 369rpm まで下がる。</p>
     *
     * <p>これは<b>ペダルを戻す速さ</b>で、{@link #CLUTCH_CAPACITY} に掛かる。
     * 速くすると滑りの区間が短くなり、蹴りが乱暴になる。</p>
     */
    private static final double CLUTCH_ENGAGE_SECONDS = 0.30;

    /**
     * クラッチが伝えられるトルクの上限。最大トルクの何倍か。
     *
     * <p>実車の乾式単板クラッチは最大トルクの 1.3〜2 倍で切られる（それ以下だと登坂で滑り、
     * 大きすぎると繋いだ瞬間が乱暴になる）。<b>諸元として持たない</b>——最大トルクが決まれば
     * 決まってしまう値なので（[他の諸元から決まる値]の考え方）。</p>
     *
     * <p>ここで頭打ちになるぶんが<b>クラッチの滑り</b>。回転差を 1 ティックで埋めきれない
     * ときは埋めきれないままエンジンが回り続ける。</p>
     */
    private static final double CLUTCH_CAPACITY = 1.6;

    /**
     * クラッチが直結したと見なす回転差 [rpm]。
     *
     * <p>滑っている間はトルクで繋ぐが、回転が揃ったら<b>直結の解きかたへ戻す</b>
     * （エンジンの慣性を車輪側へ換算する {@link #effectiveWheelInertia} の経路）。
     * そうしないと、普通に走っている間ずっと滑りクラッチを解くことになり、
     * <b>加速も変速も今までと変わってしまう</b>。</p>
     *
     * <p>切り替わる瞬間に伝達トルクが {@code engineInertia * 差 / dt} だけ跳ねるので、
     * 小さくすること。10rpm なら 2 速で 0.5m/s² 相当。</p>
     */
    private static final double CLUTCH_LOCK_RPM = 10.0;

    /**
     * 繋がりきったクラッチが回転を揃えるのにかける時間 [s]。
     *
     * <p>1 ティックで揃えようとすると必ず少し行き過ぎ、次のティックで逆向きのトルクが出る。
     * 実測で伝達トルクが 560 → -93 → 308 → -27 と<b>符号を振りながら減衰した</b>。
     * 数ティックかけて揃えれば出ない。ここは「揃うまでの時間」であって
     * {@link #CLUTCH_ENGAGE_SECONDS}（ペダルを戻す時間）とは別。</p>
     */
    private static final double CLUTCH_SYNC_SECONDS = 0.05;

    /**
     * 変速時間のうち、駆動系を抜く／繋ぐのに使う割合（前後それぞれ）。
     *
     * <p>0 にすると 0/1 の切り替えに戻り、コーナー中の変速で横グリップが段差になる。
     * 0.5 まで上げると駆動が 0 になる瞬間が消えて、変速そのものが感じられなくなる。</p>
     */
    private static final double SHIFT_RAMP = 0.35;

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
        updateDrivetrain(spec, state, contact, dt, input);
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
                double steady = Math.min(limit, required * spec.steerGripMargin());
                limit = Math.min(limit, turnInLimit(spec, state, contact, speed, steady));
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
        // <b>基準にするのは最大切れ角ではなく、いま使える切れ角の範囲。</b>
        // 最大切れ角（35 度）を基準にすると 117 度/秒 ＝ 1 ティックで 5.85 度動くので、
        // 上限がそれより小さい速度域では<b>キーを 1 回叩いた時点で張り付き、制限が
        // まったく効かなくなる</b>（100km/h で 1 ティック 4.38 度に対し上限 4.41 度）。
        // 押している長さでアナログに操作できるという肝心の性質が、80km/h あたりから
        // 上では死んでいた。範囲を基準にすれば、どの速度でも同じだけ刻める
        double span = Math.max(limit, spec.maxSteerAngle() * MIN_STEER_SPAN);
        state.steerAngle = approach(state.steerAngle, target, span, seconds, dt);

        // 速度が上がって上限が下がったときは、そちらには即座に従う
        state.steerAngle = Math.max(-limit, Math.min(limit, state.steerAngle));
    }

    /**
     * 切れ角の上限 [rad]。定常旋回に必要な角度を、<b>まだタイヤが仕事をしていないうちだけ</b>広げる。
     *
     * <p>実車のドライバーの「切り込んでから戻す」を、上限の側で成立させるためのもの。
     * 使っているグリップが増えるほど上乗せは減り、限界で {@code steady} に戻るので、
     * <b>定常旋回の切れ角も旋回半径も最大横 G も変わらない</b>
     * （定速スキッドパッドの実測で 100km/h の定常舵 2.40→2.43 度・半径 73.9→76.7m・1.00G のまま）。</p>
     *
     * <p>広げるぶんの上限が {@link #TURN_IN_BONUS}。効き方:</p>
     *
     * <table>
     *   <tr><th>速度</th><th>横 G が 90% に立つまで</th><th>0.5 秒後の横 G</th></tr>
     *   <tr><td>36km/h</td><td>0.50 → 0.50s</td><td>0.85 → 0.85G</td></tr>
     *   <tr><td>72km/h</td><td>0.65 → 0.53s</td><td>0.79 → 0.88G</td></tr>
     *   <tr><td>100km/h</td><td>0.88 → 0.66s</td><td>0.64 → 0.79G</td></tr>
     *   <tr><td>150km/h</td><td>1.29 → 0.90s</td><td>0.42 → 0.61G</td></tr>
     *   <tr><td>220km/h</td><td>2.06 → 1.50s</td><td>0.20 → 0.33G</td></tr>
     * </table>
     *
     * <p><b>ガードが 4 つ要る。どれを外しても壊れる</b>（いずれも実測）:</p>
     * <ul>
     *   <li><b>回っていることも見る</b>（{@code speed * yawRate}）。横 G だけで見ると、
     *       切り返しの途中で横 G が 0 を通る瞬間に上乗せが満タンへ復活し、まだ前の向きへ
     *       回っている車にフルの切れ角を許して<b>振り回す</b>。切り返しのスピンが
     *       28 件から <b>179 件</b>（560 条件中）へ増える</li>
     *   <li><b>縦も含めて合成で見る</b>。横 G だけだと、転がり抵抗がグリップ枠の大半を
     *       占める路面で上乗せが素通りする。<b>氷で車体スリップ角が 0.5 度から 17.3 度</b>になった</li>
     *   <li><b>制動中は上乗せしない</b>。ABS が操舵性のためにわざと絞った上限を広げることになる。
     *       フルブレーキ＋フルロックで停止まで、<b>33.5 度から 89.8 度＝スピン</b>になった</li>
     *   <li><b>前輪をピークより深く切らせない</b>（下の {@code peak}）。低速では定常角度そのものが
     *       ピークを越えているので、そこで上乗せすると<b>前輪が逃げるだけで逆に遅くなる</b>。
     *       36km/h で切り込みが 17.5→27.7 度になり、t90 が 0.50→0.58 秒と<b>悪化</b>した。
     *       蓋をすると低速では上乗せが丸ごと消え（27.7→17.5 度）、悪化しなくなる</li>
     * </ul>
     *
     * @param steady この速度で定常旋回するのに要る切れ角（＝これまでの上限）。下回らせない
     */
    private static double turnInLimit(CarSpec spec, CarState state, GroundContact contact,
                                      double speed, double steady) {
        double gripAccel = spec.tireFriction() * contact.frontGripScale() * GRAVITY;
        if (gripAccel <= 0.0) {
            return steady;
        }
        // すでに使っているぶん。タイヤが出している力（縦横の合成）と、車が回っていることの
        // 大きい方を見る。旋回中は speed*yawRate が横 G と一致し、切り返しの最中はこちらが残る
        double committed = Math.max(
                Math.hypot(state.lateralAcceleration, state.longitudinalAcceleration),
                Math.abs(speed * state.yawRate));
        double headroom = Math.max(0.0, 1.0 - committed / gripAccel);
        double widened = steady
                * (1.0 + TURN_IN_BONUS * headroom * Math.max(0.0, 1.0 - state.brake));

        // 上乗せで前輪をピークより深く切らせない
        double peak = Math.abs(selfAligningAngle(spec, state))
                + peakFrontSlipAngle(spec, state, contact);
        return Math.max(steady, Math.min(widened, peak));
    }

    /**
     * 前輪が横力のピークを出すスリップ角 [rad]。
     *
     * <p>横へ回せるグリップ（摩擦円で縦に使っているぶんを引いた残り）を
     * コーナリングパワー係数で割ったもの。{@link #steerForMaxCornering} と同じ摩擦円を使う。</p>
     */
    private static double peakFrontSlipAngle(CarSpec spec, CarState state, GroundContact contact) {
        double gripAccel = spec.tireFriction() * contact.frontGripScale() * GRAVITY;
        double used = Math.min(gripAccel, Math.abs(state.longitudinalAcceleration));
        double remaining = Math.sqrt(Math.max(0.0, gripAccel * gripAccel - used * used));
        double lateral = Math.max(gripAccel * MIN_STEERING_GRIP, remaining);
        return lateral / (GRAVITY * spec.corneringStiffness());
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
    private static void updateDrivetrain(CarSpec spec, CarState state, GroundContact contact, double dt,
                                         CarInput input) {
        state.gear = selectGear(spec, state, dt, input);
        java.util.Arrays.fill(state.wheelDriveTorque, 0.0);
        updateClutch(spec, state, dt, input);

        // 駆動輪の回転からエンジン回転数を逆算する。停止時はアイドルで下支えする。
        // クラッチが切れているぶんだけ車輪から切り離され、下の方で自分の慣性で回る
        double ratio = spec.totalRatio(state.gear);
        double drivelineSpeed = drivenWheelSpeed(spec, state);
        double rpm = Math.abs(drivelineSpeed * ratio) * 60.0 / (2.0 * Math.PI);
        // 停止時はアイドルで下支えする。クラッチの繋がり先もこの値
        double target = Math.max(spec.idleRpm(), rpm);
        // 直結の判定は<b>入るときだけ</b>行う。回転差で毎ティック見直してはいけない——
        // 加速中は 1 ティックで駆動系が 43rpm 動くので、どんな閾値でも必ず外れて
        // <b>普通に走っているだけで滑りっぱなしになる</b>（実測で制動距離が 21.4→12.1m、
        // 氷での車体スリップ角が 0.5→90 度になった）。切れるのはクラッチを踏んだとき
        // （サイドブレーキ）とニュートラルだけ
        if (clutchEngagement(spec, state) < 1.0) {
            state.clutchLocked = false;
        } else if (!state.clutchLocked) {
            state.clutchLocked = Math.abs(state.engineRpm - target) <= CLUTCH_LOCK_RPM;
        }
        if (state.clutchLocked) {
            state.engineRpm = target;
        }

        updateTractionControl(spec, state, contact, dt);

        // マニュアルでは段を自分で入れるので、バックでも W がアクセル。
        // ニュートラルでも踏めば回る（空ぶかし）——駆動系が切れているだけで、
        // エンジンが止まっているわけではない。
        // オートマは「停止して S でバック」なので S がアクセルを兼ねる。
        // <b>オートマのニュートラルでは回さない</b>——あちらの 0 は「後退中に W を踏んだ」
        // のような過渡的な状態で、そこでアクセルを踏むのは空ぶかしの意図ではない
        boolean accelerating = spec.isManual()
                ? input.throttle()
                // 前進中の S は段を保つようになったので、W と同時押しでも駆動しないよう明示する
                : (input.throttle() && !input.brake() && state.gear >= 1)
                        || (input.brake() && state.gear == -1);

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
        // 直結しているなら、エンジンが出したトルクがそのまま駆動系へ行く（今までどおり。
        // エンジンの慣性は effectiveWheelInertia が車輪側へ換算して受け持つ）。
        // 滑っているなら、クラッチが伝えられるぶんだけを伝え、残りでエンジン自身が回る
        double transmitted = state.clutchLocked
                ? torque
                : clutchTorque(spec, state, dt, torque, target);

        // 変速中は駆動系が切れる。ここを 0/1 で切り替えると横グリップが段差になって出る
        state.engineTorque = transmitted * shiftEngagement(spec, state);

        if (state.gear == 0) {
            // ニュートラルでは駆動系が切れている（差動制限のイニシャルトルクだけは残る）。
            // エンジン自身は上で回してあるので、ここへ来るのは「車輪へは何も伝えない」だけ
            state.driveTorque = 0.0;
            updateDifferential(spec, state);
            return;
        }

        // 後退は同じトルクを逆向きに掛ける
        double direction = state.gear == -1 ? -1.0 : 1.0;
        state.driveTorque = state.engineTorque * ratio * spec.drivetrainEfficiency() * direction;

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

        // 後退中に W を踏んだらブレーキ扱い。駆動はニュートラル
        if (input.throttle() && state.forwardSpeed < -spec.stopThreshold()) {
            return 0;
        }
        // 前進中に S を踏んでも<b>ニュートラルへ落とさない</b>。落とすと、離した瞬間に
        // 下の「0 なら 1 速」を通って<b>車速に関係なく 1 速へ放り込まれ</b>、強烈な
        // エンジンブレーキで後輪が滑る（実測で 25m/s から離した直後に後輪の滑り率 -0.44）。
        // 段を保ったまま下の変速判定を通せば、減速に合わせてシフトダウンしていく。
        // 駆動を切りたい場面（サイドブレーキ）はクラッチが受け持つ（updateClutch）
        boolean movingForward = state.forwardSpeed > spec.stopThreshold();
        if (input.brake() && !movingForward) {
            return -1; // 停止していれば S でバックに入る
        }
        if (state.gear <= 0) {
            return 1;
        }

        // クラッチが直結するまでは空吹かしになる。その回転で段を選ぶと、
        // サイドブレーキを引いているだけでレブに当たって<b>勝手にシフトアップする</b>
        // （実測で 3 速から 4 速へ上がり、解放後に高すぎる段で出てくる）。
        // <b>ペダルが戻りきったかではなく、回転が揃って直結したかで見ること</b>——
        // 繋ぎ戻しの途中はまだ空吹かしのままなので、そこで見ると同じことが起きる
        if (!state.clutchLocked) {
            return state.gear;
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

    /** 既定の車が舗装路で縦のグリップのピークを出す滑り率。補助の閾値の基準 */
    private static final double DEFAULT_PEAK_SLIP =
            CarSpec.DEFAULT.tireFriction() / CarSpec.DEFAULT.longitudinalStiffness();

    /**
     * 補助（ABS・TCS）の閾値に掛ける倍率。その輪のピーク滑り率 ÷ {@link #DEFAULT_PEAK_SLIP}。
     *
     * <p>縦のピーク滑り率は μ / 縦のすべり剛性なので、路面と諸元で大きく変わる
     * （舗装 0.056・氷 0.008）。閾値を固定の滑り率で持つと、氷では ABS がピークの 17 倍まで
     * 滑らせることになり、<b>ピークを越えると落ちるタイヤ（{@code tireFalloff}）では
     * そのぶんがまるごと制動距離に出る</b>。実車の ABS / TCS はピーク付近を狙う。</p>
     *
     * <p>既定の車・舗装路では 1 ちょうどなので、{@link CarSpec#absSlip()} /
     * {@link CarSpec#tractionControlSlip()} の値はそこでの閾値として読める。</p>
     */
    private static double aidSlipScale(CarSpec spec, GroundContact contact, Wheel wheel) {
        double peak = spec.tireFriction() * contact.gripScale(wheel) / spec.longitudinalStiffness();
        return Math.max(1e-3, peak / DEFAULT_PEAK_SLIP);
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
    private static void updateAbs(CarSpec spec, CarState state, GroundContact contact, double dt) {
        if (!Double.isFinite(spec.absSlip())) {
            java.util.Arrays.fill(state.brakeRelease, 1.0);
            return;
        }
        // 後軸は左右同圧（セレクトロー）。前軸は輪ごとに制御して操舵性を残す
        modulateAxle(spec, state, contact, dt, Wheel.FRONT_LEFT, false);
        modulateAxle(spec, state, contact, dt, Wheel.FRONT_RIGHT, false);
        modulateAxle(spec, state, contact, dt, Wheel.REAR_LEFT, true);
    }

    /**
     * 1 輪ぶん、あるいは後軸まとめてブレーキを緩める。
     *
     * <p><b>比例ではなく積分で効かせる。</b>比例（超過ぶんに比例して緩める）だと、緩めるには
     * 超過し続けていなければならないので、滑り率が閾値の上に居座る。制動力はタイヤのμから
     * 決めていて路面のμを知らないので、低μ路ほど踏力が余り、居座る量が大きくなる
     * （氷ではピークの 50 倍を超えて、ほぼロックしたまま止まっていた）。
     * ピークを越えるとグリップが落ちるタイヤ（{@code tireFalloff}）では、それがそのまま
     * 制動距離に出る。積分なら閾値ちょうどに落ち着く。</p>
     *
     * @param selectLow true なら軸の左右でロックの深い方に合わせ、同じ圧を掛ける
     */
    private static void modulateAxle(CarSpec spec, CarState state, GroundContact contact, double dt,
                                     Wheel wheel, boolean selectLow) {
        int index = wheel.ordinal();
        // ピーク滑り率で割って測る（aidSlipScale）。セレクトローもこの尺度で比べる
        double lock = -state.wheelSlipRatio[index] / aidSlipScale(spec, contact, wheel);
        int partner = -1;
        if (selectLow) {
            partner = (wheel.isLeft()
                    ? (wheel.isFront() ? Wheel.FRONT_RIGHT : Wheel.REAR_RIGHT)
                    : (wheel.isFront() ? Wheel.FRONT_LEFT : Wheel.REAR_LEFT)).ordinal();
            lock = Math.max(lock, -state.wheelSlipRatio[partner] / aidSlipScale(spec, contact, Wheel.VALUES[partner]));
        }

        // 超過をピーク滑り率いくつぶんかで測る。閾値より浅ければ同じ速さで踏み戻す
        double excessPeaks = (lock - spec.absSlip()) / DEFAULT_PEAK_SLIP;
        double release = Math.max(0.0, Math.min(1.0,
                state.brakeRelease[index] - ABS_INTEGRAL_RATE * excessPeaks * dt));
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
     *
     * <p><b>ABS と違って比例のままにしてある。</b>閾値（強さ 1.0 でピークの 0.9 倍）が
     * ピークより下にあるので、積分で閾値ちょうどに張り付かせると<b>グリップを使い残す</b>
     * （0-100 が舗装 5.35→5.40 秒・氷 22.2→25.4 秒と、どの路面でも遅くなった）。
     * 比例の居座りがピークの少し上まで滑らせてくれている。</p>
     */
    private static void updateTractionControl(CarSpec spec, CarState state, GroundContact contact, double dt) {
        if (!Double.isFinite(spec.tractionControlSlip())) {
            state.tractionControlThrottle = 1.0;
            return;
        }

        double slip = 0.0;
        for (Wheel wheel : Wheel.VALUES) {
            if (spec.driveShare(wheel) > 0.0) {
                slip = Math.max(slip, state.wheelSlipRatio[wheel.ordinal()] / aidSlipScale(spec, contact, wheel));
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
        if (share <= 0.0 || state.gear == 0) {
            return spec.wheelInertia();
        }
        // 駆動系が切れている間はエンジン側の慣性も外れる。トルクと同じ割合で抜き差しすること
        // （別々に切り替えると、片方だけ段差が残る）
        double ratio = spec.totalRatio(state.gear);
        return spec.wheelInertia()
                + spec.engineInertia() * ratio * ratio * share * drivelineEngagement(spec, state);
    }

    /**
     * 駆動系がどれだけ繋がっているか。変速とクラッチの両方が掛かる。
     *
     * <p>駆動トルクと、車輪側へ換算したエンジンの慣性の<b>両方</b>にこれを掛けること。
     * 片方だけだともう片方に段差が残る。</p>
     *
     * <p><b>0/1 で切り替えてはいけない。</b>滑っている間だけエンジンの慣性を車輪から
     * 外すと、車輪が軽くなりすぎて（2 速で 26.3→2.3kg·m²）クラッチの容量トルクに
     * 行き過ぎ、<b>伝達トルクが毎ティック ±560N·m と反転して発振する</b>
     * （後輪の角速度が 26〜48rad/s を往復した）。ペダルの戻りに合わせて滑らかに
     * 戻せば行き過ぎない。完全に切れているとき（サイドブレーキ）は 0 になるので、
     * <b>後輪がロックする</b>という肝心の性質は保たれる。</p>
     */
    private static double drivelineEngagement(CarSpec spec, CarState state) {
        return shiftEngagement(spec, state) * clutchEngagement(spec, state);
    }

    /**
     * 変速中に駆動系がどれだけ繋がっているか。1 で直結、0 で完全に切れている。
     *
     * <p><b>変速を 0/1 で切り替えてはいけない。</b>コーナーを全開で回りながら変速すると、
     * 縦に使っていたグリップが 1 ティックでまるごと横へ回るので、<b>その瞬間だけ車が内側へ食い込む</b>。
     * 実測（2→3、中速コーナー・全開）で横 G が 0.90→1.07G、旋回半径が 44.4→31.9m と
     * <b>0.35 秒だけ 28% 小さく回り</b>、抜けると戻る。「シフトアップでグリップが強く出る」という
     * 違和感の正体はこれで、<b>タイヤでもデフでもなく変速の入り方</b>が作っていた。</p>
     *
     * <p>実車の自動変速機も駆動を切ってから繋ぐが、<b>抜くのにも繋ぐのにも時間をかける</b>
     * （トルクフェーズ）。ここでは変速時間の前後 {@link #SHIFT_RAMP} ぶんを smoothstep で
     * 抜き差しする。両端で傾きが 0 になるので、折れ目も残らない。</p>
     *
     * <p>中央では 0 まで落としきる。<b>落としきらないと変速そのものが感じられなくなる</b>ので、
     * 消したいのは段差であって、変速で駆動が切れること自体ではない。</p>
     */
    /**
     * サイドブレーキに合わせてクラッチを切る。<b>プレイヤーは操作しない</b>（マニュアルでも自動）。
     *
     * <p>サイドブレーキは後輪だけに掛かるので、<b>後輪を駆動していなければクラッチは要らない</b>
     * （前輪駆動でサイドを引いても、エンジンに繋がっているのは前輪のまま）。切ってしまうと
     * 前輪へ駆動を送れなくなり、前輪駆動のサイドターンが成立しなくなる。</p>
     *
     * <p>クラッチは 1 つの装置なので、要るときは全部切る。{@code driveBias} が 0 を
     * 超えた瞬間に切り替わるが、後輪へ 1% でも駆動が行っていればエンジンは引きずられるので
     * それでよい。</p>
     */
    private static void updateClutch(CarSpec spec, CarState state, double dt, CarInput input) {
        double target = input.handbrake() && spec.driveBias() > 0.0 ? 0.0 : 1.0;
        if (target > state.clutch) {
            state.clutch = Math.min(target, state.clutch + dt / CLUTCH_ENGAGE_SECONDS);
        } else {
            state.clutch = Math.max(target, state.clutch - dt / CLUTCH_RELEASE_SECONDS);
        }
    }

    /**
     * クラッチがどれだけ繋がっているか。両端で傾きが 0 になるよう均してある。
     *
     * <p><b>マニュアルのニュートラルは完全に切れているのと同じ。</b>こうしておくと、
     * エンジンが車輪の回転（＝0）へ引き戻されなくなり、<b>空ぶかしできる</b>。</p>
     *
     * <p><b>オートマのニュートラルでは切らない。</b>あちらの段 0 は「後退中に W を踏んだ」
     * ときに通る<b>過渡的な状態</b>。ここで切ると、
     * 止まりきって 1 速へ入る瞬間に<b>2600rpm のままクラッチミートになって車が飛び出す</b>
     * （実測で制動＋フルロックの車体スリップ角が 33.5→71.1 度）。</p>
     */
    private static double clutchEngagement(CarSpec spec, CarState state) {
        return state.gear == 0 && spec.isManual() ? 0.0 : smoothstep(state.clutch);
    }

    /**
     * 滑っているクラッチが伝えるトルク [N*m]。あわせてエンジン自身の回転を進める。
     *
     * <p>ペダルが戻ったぶんだけ回転を揃えにいき、<b>そのために要るトルクをそのまま車へ渡す</b>。
     * これでエンジンの角運動量が車へ伝わる——以前は回転数を車輪側の値へ混ぜているだけだったので、
     * 7000rpm から繋いでもエンジンの運動エネルギー 67kJ がまるごと捨てられ、
     * <b>車はぴくりとも動かなかった</b>（実測で速度変化 0.0000m/s）。</p>
     *
     * <p><b>「回転を揃えるのに要るトルク」を慣性だけから求めてはいけない。</b>
     * 駆動系には路面からの反力が掛かっているので、それを無視すると<b>必要なトルクを
     * 大きく低く見積もる</b>。エンジンと駆動系の換算慣性で解いたときは、伝達トルクが
     * エンジンの出力の 1 割ほどにしかならず、<b>回転が永久に揃わないまま直結に戻れなくなった</b>
     * ——サイドブレーキを一度引くと、そのあとずっと駆動トルクが 728〜1291N·m
     * （本来 4400N·m）に落ちて<b>アクセルを踏んでもドリフトを維持できない</b>という形で出た。
     * ペダルの戻りを「揃える速さ」として与えれば、路面の反力を知らなくても必ず揃う。</p>
     *
     * <p>クラッチを切っているとき（サイドブレーキ）とニュートラルでは容量が 0 になるので、
     * 伝達 0・エンジンは生のトルクだけで回る＝空ぶかしになる。<b>場合分けは要らない。</b></p>
     *
     * @param torque エンジンが出している生のトルク（車輪へ伝わる前）
     * @param target 繋がった先の回転数 [rpm]。アイドルで下支えした車輪側の回転
     */
    private static double clutchTorque(CarSpec spec, CarState state, double dt,
                                       double torque, double target) {
        double engineOmega = state.engineRpm * 2.0 * Math.PI / 60.0;
        double targetOmega = target * 2.0 * Math.PI / 60.0;
        double inertia = spec.engineInertia();
        double engagement = clutchEngagement(spec, state);

        // ペダルが戻ったぶんだけ回転を揃えにいく。<b>そのために要るトルクが、そのまま
        // クラッチが伝えるトルクになる</b>——エンジンが失った角運動量が車へ渡る。
        // エンジン自身が出しているトルクはそこへ足す（滑っていても駆動は伝わる）
        double sync = Math.min(1.0, dt / CLUTCH_SYNC_SECONDS);
        double wanted = engineOmega + (targetOmega - engineOmega) * engagement * sync;
        double transmitted = torque + inertia * (engineOmega - wanted) / dt;

        // クラッチが伝えられる上限。ここで頭打ちになるぶんが滑りとして残る
        double capacity = CLUTCH_CAPACITY * spec.peakTorque() * engagement;
        transmitted = Math.max(-capacity, Math.min(capacity, transmitted));

        // 伝えられなかったぶんでエンジンが回る（吹け上がる／落ちる）
        double next = engineOmega + dt * (torque - transmitted) / inertia;
        state.engineRpm = Math.max(spec.idleRpm(),
                Math.min(spec.redlineRpm(), next * 60.0 / (2.0 * Math.PI)));
        return transmitted;
    }

    /**
     * 駆動系がどれだけ繋がっているか。変速とクラッチの両方が掛かる。
     *
     * <p>駆動トルクと、車輪側へ換算したエンジンの慣性の<b>両方</b>にこれを掛けること。
     * 片方だけだともう片方に段差が残る。</p>
     *
     * <p><b>エンジンの回転そのものにはクラッチだけを掛ける</b>（変速は掛けない）。
     * 変速中も回転を車輪に追従させておかないと、シフトのたびにエンジンが吹け上がって
     * 回転計と排気音が跳ねる。切りたいのは<b>駆動</b>であって回転の繋がりではない。</p>
     */
    private static double shiftEngagement(CarSpec spec, CarState state) {
        if (state.shiftTimer <= 0.0) {
            return 1.0;
        }
        double duration = spec.shiftSeconds();
        if (duration <= 0.0) {
            return 1.0;
        }
        double progress = clamp01(1.0 - state.shiftTimer / duration);
        if (progress < SHIFT_RAMP) {
            return 1.0 - smoothstep(progress / SHIFT_RAMP);
        }
        if (progress > 1.0 - SHIFT_RAMP) {
            return smoothstep((progress - (1.0 - SHIFT_RAMP)) / SHIFT_RAMP);
        }
        return 0.0;
    }

    /** 両端で傾きが 0 になる補間。段差だけでなく折れ目も出したくないときに使う。 */
    private static double smoothstep(double x) {
        double t = clamp01(x);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
        updateAbs(spec, state, contact, dt);

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

            // 縦の滑り率だけは、転がり方向の速度ではなく<b>接地面が路面を擦る速さそのもの</b>で
            // 正規化する。転がり方向で割ると、車体が横を向くほど分母が cos(スリップ角) で
            // 痩せていき、同じ空転量が<b>実際より大きな滑り率</b>として出る。すると
            // 角速度の上限（clampAngularVelocity）が一緒に縮んで駆動輪が回れなくなり、
            // ギア比で直結しているエンジンまで引きずり下ろされる——
            // <b>深く横を向くほどエンジンの回転が落ちる</b>という形で出た（easy_drift・
            // TCS 切・3 速 25m/s で、スリップ角 60 度まで 6976rpm を保つのに 75 度で 3360rpm）。
            // 総速度で割れば分母は速度そのものなので痩せず、実測で 6984rpm と平らになる。
            // <b>グリップ走行では slidingSpeed ≈ 0 なので referenceSpeed と一致する</b>ため、
            // 0-100・最高速・制動距離・最大横 G・舵の立ち上がりはいずれも変わらない（実測で一致）。
            double slipReference = Math.max(Math.hypot(rollingSpeed, slidingSpeed),
                    spec.slipReferenceSpeed());

            // 横: スリップ角に比例して立ち上がる。
            // 後輪を少し硬くしておくと、限界でまず前が逃げる（アンダーステア）性格になる
            double slipAngle = Math.atan2(slidingSpeed, referenceSpeed);
            // タイヤが力に変える荷重。荷重感度のぶん、重い輪ほど割り引かれる。
            // 転がり抵抗と摩擦の仕事率は実際の荷重のまま
            double tireLoad = spec.tireLoadFactor(wheel, load);
            double corneringStiffness =
                    spec.corneringStiffness() * (wheel.isFront() ? 1.0 : spec.rearCorneringBias()) * tireLoad;
            double lateralForce = -corneringStiffness * slipAngle;

            // 縦: まず車輪の回転をグリップしている前提で進め、滑り率を求める
            double longitudinalStiffness = spec.longitudinalStiffness() * tireLoad;
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
                    driveTorque, brakeTorque, rollingSpeed, slipReference, longitudinalStiffness);
            double slipRatio = clampSlipRatio(
                    (angularVelocity * spec.wheelRadius() - rollingSpeed) / slipReference);
            double longitudinalForce = longitudinalStiffness * slipRatio;

            // 摩擦円。合力が上限を超えたぶんを縦横まとめて削る
            double gripLimit = spec.tireFriction() * contact.gripScale(wheel) * tireLoad;
            double magnitude = Math.hypot(longitudinalForce, lateralForce);
            if (magnitude > gripLimit) {
                // 縦も横も剛性×荷重の直線なので、magnitude / gripLimit は
                // 「ピークの何倍まで滑っているか」（縦横を合わせた正規化スリップ）そのもの
                double overshoot = magnitude / gripLimit;
                double slidingNow = Math.hypot(angularVelocity * spec.wheelRadius() - rollingSpeed, slidingSpeed);
                double scale = gripLimit * slideGripFactor(spec, overshoot, slidingNow) / magnitude;
                longitudinalForce *= scale;
                lateralForce *= scale;

                // 滑っている間、路面が返す縦力は車輪の回転にほぼ依らない一定値になる。
                // そうなると方程式は固くなくなるので、ここだけ素直に積分してよい。
                // 掴みきれなかったぶんのトルクが車輪を空転（またはロック）させる
                angularVelocity = clampAngularVelocity(spec,
                        state.wheelAngularVelocity[index] + dt / inertia
                                * (driveTorque + brakeTorque - longitudinalForce * spec.wheelRadius()),
                        rollingSpeed, slipReference);
                slipRatio = clampSlipRatio(
                        (angularVelocity * spec.wheelRadius() - rollingSpeed) / slipReference);
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

    /** ピークの何倍まで滑ったところでグリップが落ちきるか。既定のタイヤ（μ1.0）なら横は約 19 度 */
    private static final double FALLOFF_FULL_OVERSHOOT = 4.0;
    /** 接地面の滑り速度がこれに届くまでは落ち込みを弱める [m/s]。止まりかけで正則化された角度に騙されないため */
    private static final double FALLOFF_SLIDE_SPEED = 1.0;

    /**
     * ピークを越えて滑っているときに、摩擦円の上限に掛ける倍率（1 以下）。
     *
     * <p>実タイヤの横力（縦力も）はピークを越えると下がる。ピークまでは直線のままにして、
     * そこから {@link #FALLOFF_FULL_OVERSHOOT} 倍までを smoothstep で {@code 1 - tireFalloff} へ落とす。
     * <b>ピーク以下は一切変えない</b>ので、グリップ走行の挙動はそのまま。</p>
     *
     * @param overshoot  ピークに対する正規化スリップ（1 を超えている前提）
     * @param slideSpeed 接地面が路面を擦る速さ [m/s]
     */
    private static double slideGripFactor(CarSpec spec, double overshoot, double slideSpeed) {
        if (spec.tireFalloff() <= 0.0) return 1.0;
        double t = Math.min(1.0, (overshoot - 1.0) / (FALLOFF_FULL_OVERSHOOT - 1.0));
        double shape = t * t * (3.0 - 2.0 * t);
        double gate = Math.min(1.0, slideSpeed / FALLOFF_SLIDE_SPEED);
        return 1.0 - spec.tireFalloff() * shape * gate;
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
