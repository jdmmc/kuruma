package com.jdmmc.kurumamod.physics;

/**
 * 車両の諸元。走行中に変わらない値だけを持つ。
 *
 * <p>刻々と変わる値は {@link CarState} 側。物理を分解していくとこちらのフィールドは
 * どんどん増えるので（トルク曲線・ギア比など）、増えるものと増えないものを
 * 最初から分けてある。</p>
 *
 * <p>不変なので車種ごとに 1 つ作って使い回してよい。値を振って挙動を観察したいときは
 * {@link #toBuilder()} で一部だけ差し替えた諸元を作る:</p>
 *
 * <pre>{@code
 * for (double grip : new double[]{0.8, 1.0, 1.2}) {
 *     CarSpec spec = CarSpec.DEFAULT.toBuilder().tireFriction(grip).build();
 *     // ... 時間ステップを回して観察する
 * }
 * }</pre>
 *
 * <p>単位はメートル・キログラム・秒・ラジアン・ニュートン。</p>
 *
 * <p><b>他の諸元が決まれば決まってしまう値は、ここに持たない。</b>バネ・ダンパー・
 * バンプストップ・重心高・慣性モーメント・車輪の慣性・登坂できる段差・終減速比・
 * 制動配分・ブレーキとサイドの効き・変速点・バックの最高速は、下の「諸元から決まる値」で
 * メソッドとして解いている。独立に持つと、片方だけ動かしたときに取り残されて
 * <b>物理的に成り立たない車が黙って作れてしまう</b>（車重を 2 倍にしたのにバネがそのまま、
 * タイヤを 2 倍にしたのにファイナルがそのまま、など）。</p>
 *
 * @param wheelBase            ホイールベース（前後輪の距離） [m]
 * @param trackWidth           トレッド（左右輪の距離） [m]
 * @param mass                 車両重量 [kg]
 * @param weightBias           前軸が受け持つ静的荷重の割合。0.5 で前後中央、大きいほど前が重い
 * @param wheelRadius          タイヤ半径 [m]
 * @param suspensionMaxLength  サスペンションの伸びきり長（ハードポイントから車軸まで） [m]
 * @param frontAntiRollStiffness 前輪のスタビライザー [N/m]。左右のサス変位の差に掛ける
 * @param rearAntiRollStiffness  後輪のスタビライザー [N/m]。同上
 * @param corneringStiffness   コーナリングパワー係数 [1/rad]。接地荷重に掛けて N/rad になる
 * @param longitudinalStiffness 縦方向のすべり剛性 [1/-]。接地荷重に掛けて滑り率 1 あたりの N になる
 * @param driveBias            駆動力の後輪配分。0 で前輪駆動、1 で後輪駆動
 * @param slipReferenceSpeed   滑り率を求めるときの分母の下限 [m/s]。低速で発散させないための下駄
 * @param rearCorneringBias    後輪のコーナリングパワー倍率。1 より大きいほどアンダーステア寄り
 * @param tireFriction         タイヤの摩擦係数。接地荷重に掛けたものがグリップの上限
 * @param lowSpeedBlendSpeed   この速度以下では運動学モデルへ寄せる [m/s]
 * @param steerGripMargin      切れ角上限に対する余裕。1 を超えるとグリップを超えて切れる（＝滑らせられる）
 * @param visualSlipLimit      <b>見た目だけ</b>の、進行方向に対するタイヤ角の上限 [rad]。0 で無効。物理は一切読まない
 * @param maxSteerAngle        前輪の最大切れ角 [rad]
 * @param steerRateSeconds     中立から最大切れ角まで入れるのにかかる時間 [s]。0 で即座
 * @param steerReturnSeconds   舵を放してから戻るまでの時間 [s]
 * @param selfAligning         セルフアライニングの強さ。1 で放すと前輪が完全に進行方向を向く。0 で中立へ戻る
 * @param pedalPressSeconds    アクセル／ブレーキを踏みきるまでの時間 [s]
 * @param pedalReleaseSeconds  アクセル／ブレーキを戻しきるまでの時間 [s]
 * @param peakTorque           最大トルク [N*m]
 * @param peakTorqueRpm        最大トルクが出る回転数 [rpm]
 * @param torqueFalloff        最大トルク回転数から離れたときの落ち方。大きいほど尖った特性
 * @param idleRpm              アイドル回転数 [rpm]。停止中でもここまでは回っている
 * @param redlineRpm           レブリミット [rpm]
 * @param engineBrakeTorque    アクセルを戻したときのエンジンブレーキ（レブリミット時） [N*m]
 * @param engineInertia        エンジンと変速機の慣性モーメント [kg*m^2]
 * @param gearCount            前進ギアの段数
 * @param firstGearRatio       1 速のギア比
 * @param topGearRatio         最高段のギア比。間の段は等比で割り付ける
 * @param drivetrainEfficiency 駆動系の伝達効率
 * @param shiftSeconds         変速にかかる時間 [s]。この間は駆動力が抜ける
 * @param tractionControl      トラクションコントロールの強さ。0 で無効、1 で最も介入する
 * @param abs                  ABS の強さ。0 で無効、1 で最も介入する
 * @param diffPreload          差動制限のイニシャルトルク [N*m]。無入力でも効く分
 * @param diffLockRatio        駆動トルクのうち差動制限に回る割合。0 でオープンデフ
 * @param diffCoastRatio       アクセルを戻したときの効きの倍率。0 で 1way、0.5 で 1.5way、1 で 2way
 * @param diffLockingRate      左右の回転差 1rad/s あたりの伝達トルク [N*m]
 * @param manualTransmission   0 でオートマ、1 でマニュアル
 * @param maxSpeed             前進の最高速度 [m/s]
 * @param rollingResistance    転がり抵抗による減速度 [m/s^2]
 * @param dragCoefficient      空気抵抗の係数（速度の 2 乗に比例する減速度を作る）
 * @param stopThreshold        これ以下の速さは「停止している」とみなす閾値 [m/s]
 */
public record CarSpec(
        double wheelBase,
        double trackWidth,
        double mass,
        double weightBias,
        double wheelRadius,
        double suspensionMaxLength,
        double frontAntiRollStiffness,
        double rearAntiRollStiffness,
        double corneringStiffness,
        double longitudinalStiffness,
        double driveBias,
        double slipReferenceSpeed,
        double rearCorneringBias,
        double tireFriction,
        double lowSpeedBlendSpeed,
        double steerGripMargin,
        double visualSlipLimit,
        double maxSteerAngle,
        double steerRateSeconds,
        double steerReturnSeconds,
        double selfAligning,
        double pedalPressSeconds,
        double pedalReleaseSeconds,
        double peakTorque,
        double peakTorqueRpm,
        double torqueFalloff,
        double idleRpm,
        double redlineRpm,
        double engineBrakeTorque,
        double engineInertia,
        double gearCount,
        double firstGearRatio,
        double topGearRatio,
        double drivetrainEfficiency,
        double shiftSeconds,
        double tractionControl,
        double abs,
        double diffPreload,
        double diffLockRatio,
        double diffCoastRatio,
        double diffLockingRate,
        double manualTransmission,
        double maxSpeed,
        double rollingResistance,
        double dragCoefficient,
        double stopThreshold) {

    /** 補助がいちばん緩いときに許す滑り率。 */
    private static final double LOOSEST_AID_SLIP = 0.35;
    /** 補助がいちばん厳しいときに許す滑り率。 */
    private static final double STRICTEST_AID_SLIP = 0.05;

    // ------------------------------------------------------------------
    // 導出の係数
    //
    // ここから下は「他の諸元が決まれば決まる値」を作るための比。どれも
    // 単位を持たない（あるいは 1 つの寸法あたりの量）ので、車の大小に依らない。
    // ------------------------------------------------------------------

    /**
     * 停車時の沈み込みがストロークの何割か。バネ定数はここから決まる。
     *
     * <p>4 割弱。これより柔らかいとバンプストップに乗ったまま走ることになり、
     * 硬いとストロークを使いきれない。<b>車重にも荷重配分にも依らない</b>ので、
     * 車重を変えてもバネが追従し、沈み込みと固有振動数は前後で揃ったままになる。</p>
     */
    private static final double STATIC_SAG_RATIO = 0.3924;

    /** サスペンションの減衰比。ダンパーはここから決まる（前後それぞれのバネと荷重で解く）。 */
    private static final double DAMPING_RATIO = 0.45;

    /** バンプストップが停車時荷重ぶんを受け止めるまでの縮み [m]。 */
    private static final double BUMP_STOP_TRAVEL = 0.015;

    /** ヨーの回転半径をホイールベースの何倍で見るか。 */
    private static final double YAW_GYRATION = 0.50;
    /** ピッチの回転半径をホイールベースの何倍で見るか。 */
    private static final double PITCH_GYRATION = 0.52;
    /** ロールの回転半径をトレッドの何倍で見るか。 */
    private static final double ROLL_GYRATION = 0.43;

    /** タイヤ 1 本の等価質量 [kg]。車輪の慣性 {@code I = m r^2 / 2} はここから決まる。 */
    private static final double WHEEL_MASS = 22.7;

    /**
     * 乗り越えられる段差の下限 [m]。
     *
     * <p>地形の段差は 1m 刻みなので、それより少しだけ高く取る。ちょうど 1m にすると、
     * ブレーキで前が沈んで判定の基準が数 cm 下がっただけで「登れない壁」に変わってしまう。</p>
     */
    private static final double MIN_CLIMB_STEP = 1.05;

    /** ファイナルをタイヤ半径 1m あたりでいくつにするか。ギアリングに効くのは 総減速比÷半径。 */
    private static final double FINAL_DRIVE_PER_RADIUS = 4.92 / 0.45;

    /** シフトアップ回転数はレブリミットの何割か。 */
    private static final double UPSHIFT_FRACTION = 0.886;

    /** シフトダウン回転数は「シフトアップ後に落ちる回転数」の何割か。1 を超えるとハンチングする。 */
    private static final double DOWNSHIFT_FRACTION = 0.60;

    /** ブレーキの効きが μg の何倍か。1 を超えていないとタイヤの限界まで踏めない。 */
    private static final double BRAKE_MARGIN = 1.22;

    /** サイドブレーキの効きが後軸のグリップの何倍か。1 を超えていないと後輪をロックできない。 */
    private static final double HANDBRAKE_MARGIN = 1.40;

    /** 既定の諸元。 */
    public static final CarSpec DEFAULT = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** この諸元を出発点に、一部だけ差し替えた諸元を作る。 */
    public Builder toBuilder() {
        return new Builder(this);
    }

    // ------------------------------------------------------------------
    // 諸元から決まる値
    // ------------------------------------------------------------------

    /**
     * 車体基準での車輪の前後位置 [m]。前が正。<b>原点は重心</b>。
     *
     * <p>重心から前軸までの距離は「<b>後ろ側</b>の荷重配分 × ホイールベース」。
     * 前が重いほど重心は前軸へ寄るので、掛ける相手が入れ替わることに注意。</p>
     */
    public double wheelForwardOffset(Wheel wheel) {
        return (wheel.isFront() ? 1.0 - weightBias : -weightBias) * wheelBase;
    }

    /**
     * 重心から見た前後輪の中点の位置 [m]。前が正。
     *
     * <p><b>車体メッシュの原点は前後輪の中点、エンティティの原点は重心</b>という食い違いを
     * 埋めるための値。前が重いほど重心は前軸へ寄るので、中点は相対的に後ろへ下がる（負になる）。
     * 車体の描画と座席の位置がこれを使う。</p>
     */
    public double axleMidpointOffset() {
        return (0.5 - weightBias) * wheelBase;
    }

    /** その軸が受け持つ静的荷重の割合。 */
    public double weightShare(Wheel wheel) {
        return wheel.isFront() ? weightBias : 1.0 - weightBias;
    }

    /** 車体基準での車輪の左右位置 [m]。右が正。 */
    public double wheelRightOffset(Wheel wheel) {
        return (wheel.isLeft() ? -0.5 : 0.5) * trackWidth;
    }

    /** 前進ギアの段数（整数）。 */
    public int forwardGears() {
        return Math.max(1, (int) Math.round(gearCount));
    }

    /**
     * ギア比。段の間は等比で割り付ける（実車の変速機もおおむねこの並び）。
     *
     * @param gear 1 から {@link #forwardGears()} までの前進ギア。-1 で後退（1 速と同じ比）
     */
    public double gearRatio(int gear) {
        if (gear <= 0) {
            return gear == -1 ? firstGearRatio : 0.0;
        }
        int gears = forwardGears();
        if (gears == 1 || gear >= gears) {
            return gear >= gears ? topGearRatio : firstGearRatio;
        }
        double step = (double) (gear - 1) / (gears - 1);
        return firstGearRatio * Math.pow(topGearRatio / firstGearRatio, step);
    }

    /** 総減速比。車輪 1 回転あたりエンジンが何回転するか。 */
    public double totalRatio(int gear) {
        return gearRatio(gear) * finalDriveRatio();
    }

    /**
     * エンジンが出すトルク [N*m]。
     *
     * <p>最大トルク回転数を頂点にした放物線。3 つの諸元だけで
     * 「低回転は細く、高回転で頭打ち」という形を作れる。</p>
     */
    public double engineTorque(double rpm) {
        double offset = (rpm - peakTorqueRpm) / peakTorqueRpm;
        return Math.max(0.0, peakTorque * (1.0 - torqueFalloff * offset * offset));
    }

    /**
     * 補助が許す滑り率。強さ 0 で無効（無限に許す）、1 で最も厳しい。
     *
     * <p>スライダーは「強さ」で持つ。許す滑り率のまま出すと、<b>大きいほど効かない</b>という
     * 直感に反する目盛りになり、1.00 が「ほぼ無効」を意味してしまうため。</p>
     */
    private static double allowedSlip(double strength) {
        if (strength <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double clamped = Math.min(1.0, strength);
        return LOOSEST_AID_SLIP - (LOOSEST_AID_SLIP - STRICTEST_AID_SLIP) * clamped;
    }

    /** マニュアルなら true。 */
    public boolean isManual() {
        return manualTransmission >= 0.5;
    }

    /** トラクションコントロールが許す駆動輪の滑り率。無効なら無限大。 */
    public double tractionControlSlip() {
        return allowedSlip(tractionControl);
    }

    /** ABS が許す制動時の滑り率。無効なら無限大。 */
    public double absSlip() {
        return allowedSlip(abs);
    }

    /** その輪が受け持つ駆動力の割合。 */
    public double driveShare(Wheel wheel) {
        return (wheel.isFront() ? 1.0 - driveBias : driveBias) / 2.0;
    }

    /** その輪が受け持つ制動力の割合。 */
    public double brakeShare(Wheel wheel) {
        return (wheel.isFront() ? brakeBias() : 1.0 - brakeBias()) / 2.0;
    }

    /** 停車時にその輪へかかる荷重 [N]。前後で違うので輪ごとに聞くこと。 */
    public double staticWheelLoad(Wheel wheel) {
        return mass * CarPhysics.GRAVITY * weightShare(wheel) / 2.0;
    }

    /** その輪が付いている軸のスタビライザーの強さ [N/m]。 */
    public double antiRollStiffness(Wheel wheel) {
        return wheel.isFront() ? frontAntiRollStiffness : rearAntiRollStiffness;
    }

    /**
     * その輪のバネ定数 [N/m]。
     *
     * <p><b>停車時の沈み込みがストロークの一定割合になる硬さ</b>として決まる。バネは
     * 「何 N/m か」より「どれだけ沈むか」で性格が決まるうえ、独立に持つと
     * <b>車重や荷重配分を変えたときに追従せず、前下がりになったり底付きしたりする</b>。
     * この形にしておくと、荷重に比例して硬さが動くので沈み込みも固有振動数も前後で揃い、
     * 車重を 2 倍にしても姿勢が変わらない。</p>
     */
    public double suspensionStiffness(Wheel wheel) {
        return staticWheelLoad(wheel) / (STATIC_SAG_RATIO * suspensionMaxLength);
    }

    /** 前輪のバネ定数 [N/m]。 */
    public double frontSuspensionStiffness() {
        return suspensionStiffness(Wheel.FRONT_LEFT);
    }

    /** 後輪のバネ定数 [N/m]。 */
    public double rearSuspensionStiffness() {
        return suspensionStiffness(Wheel.REAR_LEFT);
    }

    /**
     * その輪のダンパー減衰係数 [N*s/m]。
     *
     * <p>減衰比が前後で揃うように、<b>その輪のバネと分担荷重から</b>解く
     * （{@code c = 2 * ζ * sqrt(k * m)}）。前後共通の 1 つの数字で持っていた頃は、
     * 荷重配分を入れた時点で前 0.40／後 0.51 とずれていた。</p>
     */
    public double suspensionDamping(Wheel wheel) {
        double cornerMass = mass * weightShare(wheel) / 2.0;
        return 2.0 * DAMPING_RATIO * Math.sqrt(suspensionStiffness(wheel) * cornerMass);
    }

    /** サスペンションの減衰比。前後で共通。 */
    public double dampingRatio() {
        return DAMPING_RATIO;
    }

    /**
     * その輪のバンプストップのバネ定数 [N/m]。縮みきった先で受け止める硬さ。
     *
     * <p>停車時荷重ぶんを {@link #BUMP_STOP_TRAVEL} で受け止める硬さ。車重に追従しないと、
     * 重い車では底付きした先が素通しになって<b>旋回中に横転する</b>。</p>
     */
    public double bumpStopStiffness(Wheel wheel) {
        return staticWheelLoad(wheel) / BUMP_STOP_TRAVEL;
    }

    /**
     * 停車時に落ち着くサスペンションの長さ [m]。
     *
     * <p>バネを荷重から決めているので、沈み込みは常にストロークの
     * {@link #STATIC_SAG_RATIO} 倍。前後で揃うので停車中に車体は傾かない。</p>
     */
    public double staticSuspensionLength(Wheel wheel) {
        return suspensionMaxLength - staticWheelLoad(wheel) / suspensionStiffness(wheel);
    }

    /**
     * 停車時のシャシー基準面の地上高 [m]。スポーン位置を決めるのに使う。
     * 前後で硬さが違うと車体は傾くので、その中間を取る。
     */
    public double staticRideHeight() {
        double front = staticSuspensionLength(Wheel.FRONT_LEFT);
        double rear = staticSuspensionLength(Wheel.REAR_LEFT);
        return (front + rear) / 2.0 + wheelRadius;
    }

    /**
     * 重心高（接地面から） [m]。
     *
     * <p><b>停車時の車高そのもの。</b>重心はシャシー基準面のあたりに来るので、
     * タイヤを大きくしてもストロークを伸ばしても、重心は車体と一緒に上がる。
     * 独立に持っていると、タイヤ半径 110cm の車で<b>重心が車軸より下</b>という
     * 組み合わせが作れてしまう。</p>
     */
    public double cgHeight() {
        return staticRideHeight();
    }

    /** ヨー方向の慣性モーメント [kg*m^2]。回転半径をホイールベースの半分で見る。 */
    public double yawInertia() {
        return mass * square(YAW_GYRATION * wheelBase);
    }

    /** ピッチ方向の慣性モーメント [kg*m^2]。 */
    public double pitchInertia() {
        return mass * square(PITCH_GYRATION * wheelBase);
    }

    /** ロール方向の慣性モーメント [kg*m^2]。腕はホイールベースではなくトレッド。 */
    public double rollInertia() {
        return mass * square(ROLL_GYRATION * trackWidth);
    }

    /**
     * 車輪 1 本の慣性モーメント [kg*m^2]。
     *
     * <p>半径の 2 乗で効く。エンジンと変速機のぶんは {@code engineInertia} として別に持ち、
     * 総減速比の 2 乗を掛けて車輪側へ換算する（ギアによって効き方が変わるので混ぜられない）。</p>
     */
    public double wheelInertia() {
        return 0.5 * WHEEL_MASS * square(wheelRadius);
    }

    /**
     * タイヤが乗り越えられる段差の高さ [m]。これより上まで続くものは壁として扱う。
     *
     * <p>剛体の車輪が越えられる段差の幾何的な上限は<b>半径そのもの</b>（接触点が車軸の
     * 高さに来ると必要な力が発散する）。ただし Minecraft の地形は 1m 刻みなので、
     * 小さいタイヤでも 1 ブロックは登れないと平地で詰む。両方の下からの制約を取る。</p>
     */
    public double maxClimbStep() {
        return Math.max(MIN_CLIMB_STEP, wheelRadius);
    }

    /**
     * 制動力の前輪配分。
     *
     * <p><b>1G で制動しているときの前軸荷重の割合。</b>静的な配分に、荷重移動ぶん
     * （重心高 ÷ ホイールベース）が乗る。固定にしておくと、荷重配分や重心高を変えた
     * ときに後輪だけ先にロックして尻が出る。</p>
     */
    public double brakeBias() {
        return Math.min(0.95, weightBias + cgHeight() / wheelBase);
    }

    /**
     * ブレーキの減速度 [m/s^2]。
     *
     * <p>タイヤが出せるのは μg までなので、それを少し超える踏力を用意する
     * （超えていないとタイヤの限界まで踏めず、ABS も介入しない）。</p>
     */
    public double brakeDecel() {
        return tireFriction * CarPhysics.GRAVITY * BRAKE_MARGIN;
    }

    /**
     * サイドブレーキの減速度 [m/s^2]。後輪だけに掛かり、ABS を通さない。
     *
     * <p>後輪を<b>ロックさせて車を回すための装置</b>なので、後軸のグリップを
     * 上回っていなければ意味がない。効くのは後軸だけなので、後軸の荷重割合で決まる。</p>
     */
    public double handbrakeDecel() {
        return tireFriction * CarPhysics.GRAVITY * (1.0 - weightBias) * HANDBRAKE_MARGIN;
    }

    /** 終減速比。<b>ギアリングに効くのは 総減速比 ÷ タイヤ半径</b>なので、半径に比例させる。 */
    public double finalDriveRatio() {
        return FINAL_DRIVE_PER_RADIUS * wheelRadius;
    }

    /** 隣り合う段の比。段を等比で割り付けているので、どの段でも同じ。 */
    public double gearStep() {
        int gears = forwardGears();
        if (gears <= 1) {
            return topGearRatio / firstGearRatio;
        }
        return Math.pow(topGearRatio / firstGearRatio, 1.0 / (gears - 1));
    }

    /** 自動でシフトアップする回転数 [rpm]。レブリミットの手前に置く。 */
    public double upshiftRpm() {
        return redlineRpm * UPSHIFT_FRACTION;
    }

    /**
     * 自動でシフトダウンする回転数 [rpm]。
     *
     * <p><b>シフトアップ直後に落ちる回転数（アップ回転数 × 段の比）より下</b>でなければ、
     * 上げた瞬間に下げ条件が成立して往復する。段数やギア比を変えると段の比が変わるので、
     * そこから決める。アイドルは下回らせない。</p>
     */
    public double downshiftRpm() {
        return Math.max(idleRpm * 1.3, upshiftRpm() * gearStep() * DOWNSHIFT_FRACTION);
    }

    /**
     * バックの最高速度 [m/s]。
     *
     * <p>バックは 1 速と同じギア比なので、<b>レブリミットで回りきったときの速度</b>が
     * そのまま上限になる。エンジンが回れる以上には進めない。</p>
     */
    public double maxReverseSpeed() {
        double ratio = Math.abs(totalRatio(-1));
        if (ratio <= 0.0) {
            return 0.0;
        }
        return redlineRpm * 2.0 * Math.PI / 60.0 / ratio * wheelRadius;
    }

    private static double square(double value) {
        return value * value;
    }

    /**
     * 諸元の組み立て。項目が多く順序で渡すと読めないため、
     * レコードのコンストラクタを直接使わずこちらを通す。
     */
    public static final class Builder {

        // 実車の 1.2 倍。Minecraft のプレイヤーはずんぐりしているので、実寸どおり
        // （ホイールベース 2.6m）だと車が小さく見える。tools/blender_gauge.py の
        // SCALE と揃えること
        private double wheelBase = 3.12;
        private double trackWidth = 1.80;
        private double mass = 1200.0;
        // 前 56：後 44。エンジンが前にある車の実測に近く、これがあってはじめて
        // 「前を厚くするとアンダー」というセッティングが意味を持つ（前後中央だと対称で効かない）
        private double weightBias = 0.56;
        private double wheelRadius = 0.45; // 直径 0.90m。こちらも実車の 1.2 倍
        // ストロークは足の硬さの入り口でもある。沈み込みがストロークの一定割合になるよう
        // バネを決めているので、短くすればバネもダンパーも比例して硬くなる
        // （25cm で 33,600/26,400N/m・固有振動数 1.59Hz。ここを 15cm にすると 56,000N/m 相当）
        private double suspensionMaxLength = 0.25;
        // スタビライザー。左右のサス変位の差に掛ける（左右が同じだけ沈む動きには効かない）。
        // バネを緩めた状態でロール量と前後バランスを別々に決めるための装置で、
        // 前を強くするとアンダー、後ろを強くするとオーバーになる
        private double frontAntiRollStiffness = 2500.0;
        private double rearAntiRollStiffness = 1500.0;
        // 荷重 1N あたり 12N/rad。乗用車のタイヤとして標準的な範囲
        private double corneringStiffness = 12.0;
        // 縦は横よりグリップの立ち上がりが速いのが実タイヤの性質
        private double longitudinalStiffness = 18.0;
        private double driveBias = 0.5;   // 四輪駆動
        private double slipReferenceSpeed = 2.0;
        // 1.0 ＝ 前後とも同じタイヤ。弱アンダーは前 56：後 44 の荷重配分と
        // 前寄りのスタビ配分が物理的に作るので、タイヤ側でごまかす必要がなくなった。
        // 実測でタックイン 7.4 度・制動＋舵 18.7 度と、スピン判定の 45 度には遠い。
        // 落ち着かせたい人は調整画面で上げられる（1.25 が以前の既定）
        private double rearCorneringBias = 1.0;
        private double tireFriction = 1.0;
        private double lowSpeedBlendSpeed = 3.0;
        // 1 割ぶん多めに切れるようにして、その気になれば限界を超えて滑らせられる余地を残す
        private double steerGripMargin = 1.1;
        // 見た目だけの上限。進行方向に対して測るので、通常の走行（数度）には効かない
        private double visualSlipLimit = Math.toRadians(45.0);
        private double maxSteerAngle = Math.toRadians(35.0);
        // キーは 0/100 でも、舵とペダルが動く速さには限りがある。ここを有限にしておくと
        // 「押している長さ」でアナログに操作できるようになる（0 にすると従来の即座）
        private double steerRateSeconds = 0.30;
        private double steerReturnSeconds = 0.18;
        // 実車は舵を放すと前輪が自分から進行方向を向く。滑っているときはこれがカウンターになる。
        // 既定は無効（中立へ戻る）。使いたい人が入れる
        private double selfAligning = 0.0;
        private double pedalPressSeconds = 0.25;
        private double pedalReleaseSeconds = 0.12;
        // 後輪駆動で前後 50:50 なら、駆動輪のグリップから決まる上限は mu*後軸荷重/車重 = 約 4.9m/s^2。
        // それを超える値にすると、直進でも常に後輪が空転する車になる
        private double peakTorque = 220.0;
        private double peakTorqueRpm = 4200.0;
        private double torqueFalloff = 0.45;
        private double idleRpm = 800.0;
        private double redlineRpm = 7000.0;
        private double engineBrakeTorque = 35.0;
        private double engineInertia = 0.25;
        private double gearCount = 5.0;
        private double firstGearRatio = 3.4;
        private double topGearRatio = 0.85;
        private double drivetrainEfficiency = 0.9;
        private double shiftSeconds = 0.35;
        // 0/100 のアクセルだと旋回中に必ず後輪が空転しきってスピンするので、既定では効かせておく。
        // 0 にすれば無効になり、アクセルで自由に流せる
        private double tractionControl = 1.0;
        // ブレーキ配分は固定なので、荷重が前へ移ると後輪だけロックして尻が出る。
        // 0 にすれば無効になり、ロックさせられる
        private double abs = 0.7;
        // 機械式 LSD。既定はオープンデフ（イニシャルと差動制限率が 0）。
        // 入れると片輪が浮いたときの脱出は良くなるが、後輪により多くの駆動力を掛けられる
        // ぶん横のグリップを食い、旋回中の全開でテールハッピーになる。ドリフト向けの装備
        private double diffPreload = 0.0;
        private double diffLockRatio = 0.0;
        private double diffCoastRatio = 0.5; // 1.5way
        private double diffLockingRate = 40.0;
        // 0 でオートマ、1 でマニュアル
        private double manualTransmission = 0.0;
        // 最高速はトルク曲線と抵抗から自然に決まる。これは暴走を止めるための上限。
        // 抵抗と釣り合う速度（既定の諸元で 208km/h）より先に効いてしまうと、
        // トルクや空気抵抗を変えても最高速がここで頭打ちになって動かなくなる
        private double maxSpeed = 69.4; // 約 250km/h
        // 実車の転がり抵抗はおよそ 0.012G、空気抵抗は 100km/h で 0.03G 程度。
        // 抽象モデル時代の値（1.2 と 0.0035）は 10 倍ほど過大で、
        // 実トルクを入れると最高速に届かなくなる
        private double rollingResistance = 0.15;
        private double dragCoefficient = 0.0004;
        private double stopThreshold = 0.3;

        private Builder() {
        }

        private Builder(CarSpec spec) {
            wheelBase = spec.wheelBase;
            trackWidth = spec.trackWidth;
            mass = spec.mass;
            weightBias = spec.weightBias;
            wheelRadius = spec.wheelRadius;
            suspensionMaxLength = spec.suspensionMaxLength;
            frontAntiRollStiffness = spec.frontAntiRollStiffness;
            rearAntiRollStiffness = spec.rearAntiRollStiffness;
            corneringStiffness = spec.corneringStiffness;
            longitudinalStiffness = spec.longitudinalStiffness;
            driveBias = spec.driveBias;
            slipReferenceSpeed = spec.slipReferenceSpeed;
            rearCorneringBias = spec.rearCorneringBias;
            tireFriction = spec.tireFriction;
            lowSpeedBlendSpeed = spec.lowSpeedBlendSpeed;
            steerGripMargin = spec.steerGripMargin;
            visualSlipLimit = spec.visualSlipLimit;
            maxSteerAngle = spec.maxSteerAngle;
            steerRateSeconds = spec.steerRateSeconds;
            steerReturnSeconds = spec.steerReturnSeconds;
            selfAligning = spec.selfAligning;
            pedalPressSeconds = spec.pedalPressSeconds;
            pedalReleaseSeconds = spec.pedalReleaseSeconds;
            peakTorque = spec.peakTorque;
            peakTorqueRpm = spec.peakTorqueRpm;
            torqueFalloff = spec.torqueFalloff;
            idleRpm = spec.idleRpm;
            redlineRpm = spec.redlineRpm;
            engineBrakeTorque = spec.engineBrakeTorque;
            engineInertia = spec.engineInertia;
            gearCount = spec.gearCount;
            firstGearRatio = spec.firstGearRatio;
            topGearRatio = spec.topGearRatio;
            drivetrainEfficiency = spec.drivetrainEfficiency;
            shiftSeconds = spec.shiftSeconds;
            tractionControl = spec.tractionControl;
            abs = spec.abs;
            diffPreload = spec.diffPreload;
            diffLockRatio = spec.diffLockRatio;
            diffCoastRatio = spec.diffCoastRatio;
            diffLockingRate = spec.diffLockingRate;
            manualTransmission = spec.manualTransmission;
            maxSpeed = spec.maxSpeed;
            rollingResistance = spec.rollingResistance;
            dragCoefficient = spec.dragCoefficient;
            stopThreshold = spec.stopThreshold;
        }

        public Builder wheelBase(double value) {
            wheelBase = value;
            return this;
        }

        public Builder trackWidth(double value) {
            trackWidth = value;
            return this;
        }

        public Builder mass(double value) {
            mass = value;
            return this;
        }

        public Builder weightBias(double value) {
            weightBias = value;
            return this;
        }

        public Builder wheelRadius(double value) {
            wheelRadius = value;
            return this;
        }

        public Builder suspensionMaxLength(double value) {
            suspensionMaxLength = value;
            return this;
        }

        public Builder frontAntiRollStiffness(double value) {
            frontAntiRollStiffness = value;
            return this;
        }

        public Builder rearAntiRollStiffness(double value) {
            rearAntiRollStiffness = value;
            return this;
        }

        public Builder corneringStiffness(double value) {
            corneringStiffness = value;
            return this;
        }

        public Builder longitudinalStiffness(double value) {
            longitudinalStiffness = value;
            return this;
        }

        public Builder driveBias(double value) {
            driveBias = value;
            return this;
        }

        public Builder slipReferenceSpeed(double value) {
            slipReferenceSpeed = value;
            return this;
        }

        public Builder rearCorneringBias(double value) {
            rearCorneringBias = value;
            return this;
        }

        public Builder tireFriction(double value) {
            tireFriction = value;
            return this;
        }

        public Builder lowSpeedBlendSpeed(double value) {
            lowSpeedBlendSpeed = value;
            return this;
        }

        public Builder visualSlipLimit(double value) {
            visualSlipLimit = value;
            return this;
        }

        public Builder steerGripMargin(double value) {
            steerGripMargin = value;
            return this;
        }

        public Builder maxSteerAngle(double radians) {
            maxSteerAngle = radians;
            return this;
        }

        public Builder steerRateSeconds(double value) {
            steerRateSeconds = value;
            return this;
        }

        public Builder steerReturnSeconds(double value) {
            steerReturnSeconds = value;
            return this;
        }

        public Builder selfAligning(double value) {
            selfAligning = value;
            return this;
        }

        public Builder pedalPressSeconds(double value) {
            pedalPressSeconds = value;
            return this;
        }

        public Builder pedalReleaseSeconds(double value) {
            pedalReleaseSeconds = value;
            return this;
        }

        /** 切れ角は度で指定した方が読みやすいことが多いので、度で渡す口も用意しておく。 */
        public Builder maxSteerAngleDegrees(double degrees) {
            maxSteerAngle = Math.toRadians(degrees);
            return this;
        }

        public Builder peakTorque(double value) {
            peakTorque = value;
            return this;
        }

        public Builder peakTorqueRpm(double value) {
            peakTorqueRpm = value;
            return this;
        }

        public Builder torqueFalloff(double value) {
            torqueFalloff = value;
            return this;
        }

        public Builder idleRpm(double value) {
            idleRpm = value;
            return this;
        }

        public Builder redlineRpm(double value) {
            redlineRpm = value;
            return this;
        }

        public Builder engineBrakeTorque(double value) {
            engineBrakeTorque = value;
            return this;
        }

        public Builder engineInertia(double value) {
            engineInertia = value;
            return this;
        }

        public Builder gearCount(double value) {
            gearCount = value;
            return this;
        }

        public Builder firstGearRatio(double value) {
            firstGearRatio = value;
            return this;
        }

        public Builder topGearRatio(double value) {
            topGearRatio = value;
            return this;
        }

        public Builder drivetrainEfficiency(double value) {
            drivetrainEfficiency = value;
            return this;
        }

        public Builder shiftSeconds(double value) {
            shiftSeconds = value;
            return this;
        }

        public Builder tractionControl(double value) {
            tractionControl = value;
            return this;
        }

        public Builder abs(double value) {
            abs = value;
            return this;
        }

        public Builder diffPreload(double value) {
            diffPreload = value;
            return this;
        }

        public Builder diffLockRatio(double value) {
            diffLockRatio = value;
            return this;
        }

        public Builder diffCoastRatio(double value) {
            diffCoastRatio = value;
            return this;
        }

        public Builder diffLockingRate(double value) {
            diffLockingRate = value;
            return this;
        }

        public Builder manualTransmission(double value) {
            manualTransmission = value;
            return this;
        }

        public Builder maxSpeed(double value) {
            maxSpeed = value;
            return this;
        }

        public Builder rollingResistance(double value) {
            rollingResistance = value;
            return this;
        }

        public Builder dragCoefficient(double value) {
            dragCoefficient = value;
            return this;
        }

        public Builder stopThreshold(double value) {
            stopThreshold = value;
            return this;
        }

        public CarSpec build() {
            return new CarSpec(
                    wheelBase, trackWidth, mass, weightBias, wheelRadius, suspensionMaxLength,
                    frontAntiRollStiffness, rearAntiRollStiffness,
                    corneringStiffness, longitudinalStiffness,
                    driveBias, slipReferenceSpeed, rearCorneringBias, tireFriction,
                    lowSpeedBlendSpeed, steerGripMargin, visualSlipLimit,
                    maxSteerAngle, steerRateSeconds, steerReturnSeconds, selfAligning,
                    pedalPressSeconds, pedalReleaseSeconds,
                    peakTorque, peakTorqueRpm, torqueFalloff, idleRpm, redlineRpm,
                    engineBrakeTorque, engineInertia,
                    gearCount, firstGearRatio, topGearRatio, drivetrainEfficiency, shiftSeconds,
                    tractionControl, abs,
                    diffPreload, diffLockRatio, diffCoastRatio, diffLockingRate,
                    manualTransmission, maxSpeed, rollingResistance, dragCoefficient,
                    stopThreshold);
        }
    }
}
