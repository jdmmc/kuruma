package com.jdmmc.kurumamod.tuning;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;

import java.util.ArrayList;
import java.util.List;

/**
 * 調整画面に並べる項目の一覧。
 *
 * <p><b>ここに 1 行足せば、画面・リセット・サーバーへの同期がすべて追従する。</b>
 * パケットは {@link #ALL} の順に値を並べて送るので、順序を変えるとクライアントと
 * サーバーで解釈がずれる点にだけ注意する（バージョンをまたぐ互換性はまだ考えていない）。</p>
 *
 * <p>載せていないのは、車の性格ではなく数値計算の都合で決まっている値
 * （{@code lowSpeedBlendSpeed}、{@code stopThreshold}）と、<b>他の諸元が決まれば
 * 決まってしまう値</b>。後者は {@link CarSpec} 側のメソッドとして導出してある:</p>
 *
 * <table border="1">
 *   <caption>導出される諸元</caption>
 *   <tr><th>諸元</th><th>何から決まるか</th></tr>
 *   <tr><td>バネ定数（前後）</td><td>荷重とストローク（沈み込みの割合を一定に保つ）</td></tr>
 *   <tr><td>ダンパー</td><td>そのバネと分担荷重（減衰比を一定に保つ）</td></tr>
 *   <tr><td>バンプストップ</td><td>停車時荷重</td></tr>
 *   <tr><td>重心高</td><td>停車時の車高</td></tr>
 *   <tr><td>慣性モーメント 3 つ</td><td>車重とホイールベース／トレッド</td></tr>
 *   <tr><td>車輪の慣性</td><td>タイヤ半径</td></tr>
 *   <tr><td>登坂限界</td><td>タイヤ半径（と 1 ブロックぶんの下限）</td></tr>
 *   <tr><td>ファイナル</td><td>タイヤ半径（総減速比÷半径 を一定に保つ）</td></tr>
 *   <tr><td>制動配分</td><td>荷重配分・重心高・ホイールベース</td></tr>
 *   <tr><td>ブレーキとサイドの効き</td><td>タイヤの摩擦係数（と後軸の荷重割合）</td></tr>
 *   <tr><td>変速点</td><td>レブリミットと段の比</td></tr>
 *   <tr><td>バックの最高速</td><td>レブリミットと 1 速の総減速比とタイヤ半径</td></tr>
 * </table>
 *
 * <p><b>導出した値は、それを決めている項目の補足として画面に出す</b>（バネならストロークと
 * 車重の行に「33600/26400 N/m・沈み 9.8cm」と出る）。数字が見えないと、効いているのか
 * 分からないため。</p>
 *
 * <p><b>寸法（{@code wheelBase}・{@code trackWidth}・{@code wheelRadius}）を動かすと
 * タイヤの位置と大きさだけが変わり、車体は変わらない。</b>タイヤは物理が置く位置に描かれる
 * のに対し、車体はメッシュの形が正で（伸ばすとキャビンごと伸びてしまう）追従させていないため。
 * 大きく動かすとタイヤがフェンダーからはみ出すが、これは<b>そういう車を作ったことの正直な表示</b>
 * として受け入れている。車体の大きさを合わせたいときは
 * {@code assets/kurumamod/vehicles/car.json} の {@code bodyScale} で別に指定する。</p>
 *
 * <p>タイヤ半径だけは<b>接地の辻褄が自動で合う</b>。静止時のサス長は半径に依らないので、
 * 車高（{@code staticRideHeight}）が半径ぶん持ち上がり、タイヤはちょうど接地したまま
 * 車体が上がる（＝リフトアップ）。ギア比を通して最高速にも効く。</p>
 */
public final class Tunables {

    /** 見出しでまとめた項目の束。 */
    public record Group(String name, List<TunableParameter> parameters) {

        public String translationKey() {
            return "tuning.kurumamod.group." + name;
        }

        /** タブに出す短い名前。 */
        public String shortKey() {
            return "tuning.kurumamod.group." + name + ".short";
        }
    }

    public static final List<Group> GROUPS = List.of(
            new Group("suspension", List.of(
                    // バネ・ダンパー・バンプストップは荷重とストロークから決まるので、ここには無い。
                    // 硬さを変えたいときはストロークを動かす（沈み込みの割合が一定なので、
                    // 短くすればそのぶん硬く、長くすれば柔らかくなる）
                    TunableParameter.linear("stroke", 15, 80, "%.0f",
                                    CarSpec::suspensionMaxLength, CarSpec.Builder::suspensionMaxLength)
                            .unit(0.01)
                            .detail(Tunables::springs),
                    // スタビはバネと違って左右逆に動くときだけ効くので、沈み込みも
                    // ノーズダイブも変えずにロールだけ締められる。0 で無し。
                    // 見せる補足がロール剛性の前後配分なのは、ロール量より
                    // 「アンダーかオーバーか」がこの配分で決まるため
                    TunableParameter.linear("front_anti_roll", 0, 20000, "%.0f",
                                    CarSpec::frontAntiRollStiffness, CarSpec.Builder::frontAntiRollStiffness)
                            .detail(Tunables::frontRollShare),
                    TunableParameter.linear("rear_anti_roll", 0, 20000, "%.0f",
                                    CarSpec::rearAntiRollStiffness, CarSpec.Builder::rearAntiRollStiffness)
                            .detail(Tunables::frontRollShare))),

            new Group("tire", List.of(
                    // 目盛りは既定を 1.00 とした相対値（実際のμは CarSpec.REFERENCE_FRICTION 倍）。
                    // ブレーキとサイドブレーキの効きもここから決まる（μg を少し超える踏力を用意する）
                    TunableParameter.linear("friction", 0.3, 1.5, "%.2f",
                                    CarSpec::tireFriction, CarSpec.Builder::tireFriction)
                            .unit(CarSpec.REFERENCE_FRICTION)
                            .detail(Tunables::brakeForce),
                    // ピークを越えて滑ったときに失うグリップ。表示は %、内部は割合
                    TunableParameter.linear("tire_falloff", 0, 50, "%.0f",
                                    CarSpec::tireFalloff, CarSpec.Builder::tireFalloff)
                            .unit(0.01),
                    TunableParameter.linear("cornering_stiffness", 4, 25, "%.1f",
                            CarSpec::corneringStiffness, CarSpec.Builder::corneringStiffness),
                    TunableParameter.linear("rear_cornering_bias", 0.7, 1.8, "%.2f",
                            CarSpec::rearCorneringBias, CarSpec.Builder::rearCorneringBias),
                    TunableParameter.linear("longitudinal_stiffness", 5, 40, "%.1f",
                            CarSpec::longitudinalStiffness, CarSpec.Builder::longitudinalStiffness),
                    // 見た目も追従する（CarObjRenderer がこの値でタイヤを拡大する）。
                    // 車高・重心高・ファイナル・車輪の慣性・登坂限界がまとめてここから決まる
                    TunableParameter.linear("wheel_radius", 25, 110, "%.1f",
                                    CarSpec::wheelRadius, CarSpec.Builder::wheelRadius)
                            .unit(0.01)
                            .detail(Tunables::rideHeight))),

            new Group("body", List.of(
                    // バネ・ダンパー・慣性モーメントは車重と寸法から決まるので、ここには無い
                    TunableParameter.linear("mass", 500, 2500, "%.0f",
                                    CarSpec::mass, CarSpec.Builder::mass)
                            .detail(Tunables::springs),
                    // 前後の荷重配分。50 で中央、大きいほど前が重い。
                    // これが 50 のままだと、スタビの前後配分を振っても効き方が対称で
                    // 「アンダーかオーバーか」が出ない。制動配分もここから決まる
                    TunableParameter.linear("weight_distribution", 35, 70, "%.0f",
                                    CarSpec::weightBias, CarSpec.Builder::weightBias)
                            .unit(0.01)
                            .detail(Tunables::axleLoads),
                    // 寸法はタイヤの位置だけを動かす。車体は追従しない（メッシュの形が正）
                    // 既定が実車の 1.7 倍なので、範囲も実車相当（2.6 / 1.5）から
                    // 2 倍強までを見る
                    TunableParameter.linear("wheel_base", 2.0, 7.0, "%.2f",
                                    CarSpec::wheelBase, CarSpec.Builder::wheelBase)
                            .detail(Tunables::inertias),
                    TunableParameter.linear("track_width", 1.2, 4.0, "%.2f",
                                    CarSpec::trackWidth, CarSpec.Builder::trackWidth)
                            .detail(Tunables::inertias))),

            new Group("engine", List.of(
                    TunableParameter.linear("peak_torque", 60, 700, "%.0f",
                                    CarSpec::peakTorque, CarSpec.Builder::peakTorque)
                            .detail(Tunables::peakPower),
                    TunableParameter.linear("peak_torque_rpm", 1500, 8000, "%.0f",
                            CarSpec::peakTorqueRpm, CarSpec.Builder::peakTorqueRpm),
                    TunableParameter.linear("torque_falloff", 0.1, 0.9, "%.2f",
                            CarSpec::torqueFalloff, CarSpec.Builder::torqueFalloff),
                    TunableParameter.linear("idle_rpm", 500, 2000, "%.0f",
                            CarSpec::idleRpm, CarSpec.Builder::idleRpm),
                    // 変速点もここから決まる（シフトアップはレブの手前、シフトダウンは
                    // 上げた直後に落ちる回転数より下）
                    TunableParameter.linear("redline_rpm", 4000, 12000, "%.0f",
                                    CarSpec::redlineRpm, CarSpec.Builder::redlineRpm)
                            .detail(Tunables::shiftPoints),
                    TunableParameter.linear("engine_brake", 0, 120, "%.0f",
                            CarSpec::engineBrakeTorque, CarSpec.Builder::engineBrakeTorque),
                    TunableParameter.linear("engine_inertia", 0.05, 1.0, "%.2f",
                            CarSpec::engineInertia, CarSpec.Builder::engineInertia))),

            new Group("gearbox", List.of(
                    // ファイナルはタイヤ半径から、変速点はレブと段の比から決まるので、ここには無い。
                    // ギアリング全体を動かしたいときは 1 速の比を使う
                    TunableParameter.linear("gear_count", 3, 8, "%.0f",
                                    CarSpec::gearCount, CarSpec.Builder::gearCount)
                            .detail(Tunables::shiftPoints),
                    TunableParameter.linear("first_gear", 1.5, 5.0, "%.2f",
                            CarSpec::firstGearRatio, CarSpec.Builder::firstGearRatio),
                    TunableParameter.linear("top_gear", 0.4, 1.5, "%.2f",
                                    CarSpec::topGearRatio, CarSpec.Builder::topGearRatio)
                            .detail(Tunables::topGearSpeed),
                    TunableParameter.linear("drivetrain_efficiency", 0.7, 1.0, "%.2f",
                            CarSpec::drivetrainEfficiency, CarSpec.Builder::drivetrainEfficiency),
                    TunableParameter.linear("shift_time", 0.0, 1.5, "%.2f",
                            CarSpec::shiftSeconds, CarSpec.Builder::shiftSeconds),
                    TunableParameter.linear("manual_transmission", 0.0, 1.0, "%.0f",
                                    CarSpec::manualTransmission, CarSpec.Builder::manualTransmission)
                            .detail(spec -> spec.isManual() ? "MT (R/F)" : "AT"))),

            new Group("driveline", List.of(
                    TunableParameter.linear("drive_bias", 0.0, 1.0, "%.2f",
                                    CarSpec::driveBias, CarSpec.Builder::driveBias)
                            .detail(Tunables::driveLayout),
                    TunableParameter.linear("diff_preload", 0, 300, "%.0f",
                            CarSpec::diffPreload, CarSpec.Builder::diffPreload),
                    TunableParameter.linear("diff_lock_ratio", 0.0, 1.0, "%.2f",
                                    CarSpec::diffLockRatio, CarSpec.Builder::diffLockRatio)
                            .detail(Tunables::differentialType),
                    TunableParameter.linear("diff_coast_ratio", 0.0, 1.0, "%.2f",
                                    CarSpec::diffCoastRatio, CarSpec.Builder::diffCoastRatio)
                            .detail(Tunables::differentialWay),
                    TunableParameter.linear("diff_locking_rate", 0, 200, "%.0f",
                            CarSpec::diffLockingRate, CarSpec.Builder::diffLockingRate))),

            new Group("brakes", List.of(
                    // ブレーキの効き・制動配分・サイドブレーキ・バックの最高速は、
                    // タイヤの摩擦係数・荷重配分・ギア比から決まるので、ここには無い
                    TunableParameter.linear("abs", 0.0, 1.0, "%.2f",
                                    CarSpec::abs, CarSpec.Builder::abs)
                            .detail(spec -> spec.abs() <= 0.0 ? "OFF" : "ON"),
                    TunableParameter.linear("max_speed", 20, 350, "%.0f",
                                    CarSpec::maxSpeed, CarSpec.Builder::maxSpeed)
                            .unit(1.0 / 3.6),
                    TunableParameter.linear("rolling_resistance", 0.0, 1.0, "%.2f",
                            CarSpec::rollingResistance, CarSpec.Builder::rollingResistance),
                    TunableParameter.log("drag", 0.0001, 0.005, "%.5f",
                            CarSpec::dragCoefficient, CarSpec.Builder::dragCoefficient))),

            new Group("controls", List.of(
                    TunableParameter.linear("max_steer", 10, 50, "%.0f",
                                    CarSpec::maxSteerAngle, CarSpec.Builder::maxSteerAngle)
                            .unit(Math.PI / 180.0),
                    // 2 倍が意味を持つ量なので対数割り当て。線形だと 1.0〜2.0 の
                    // いちばん効く領域が目盛りの端に潰れる。
                    // 8.0 まで上げると 20m/s でも最大切れ角まで切れる（＝実質この補助は無効）
                    TunableParameter.log("steer_grip_margin", 0.8, 8.0, "%.2f",
                            CarSpec::steerGripMargin, CarSpec.Builder::steerGripMargin),
                    // 見た目だけの上限。物理には効かない
                    TunableParameter.linear("visual_slip_limit", 0, 90, "%.0f",
                                    CarSpec::visualSlipLimit, CarSpec.Builder::visualSlipLimit)
                            .unit(Math.PI / 180.0)
                            .detail(spec -> spec.visualSlipLimit() <= 0.0 ? "OFF" : "ON"),
                    TunableParameter.linear("steer_rate", 0.0, 1.0, "%.2f",
                                    CarSpec::steerRateSeconds, CarSpec.Builder::steerRateSeconds)
                            .detail(spec -> spec.steerRateSeconds() <= 0.0 ? "即座" : "ゆっくり"),
                    TunableParameter.linear("steer_return", 0.0, 1.0, "%.2f",
                            CarSpec::steerReturnSeconds, CarSpec.Builder::steerReturnSeconds),
                    TunableParameter.linear("self_aligning", 0.0, 1.0, "%.2f",
                                    CarSpec::selfAligning, CarSpec.Builder::selfAligning)
                            .detail(spec -> spec.selfAligning() <= 0.0 ? "中立へ戻る" : "進行方向を向く"),
                    TunableParameter.linear("pedal_press", 0.0, 1.0, "%.2f",
                            CarSpec::pedalPressSeconds, CarSpec.Builder::pedalPressSeconds),
                    TunableParameter.linear("pedal_release", 0.0, 1.0, "%.2f",
                            CarSpec::pedalReleaseSeconds, CarSpec.Builder::pedalReleaseSeconds),
                    TunableParameter.linear("traction_control", 0.0, 1.0, "%.2f",
                                    CarSpec::tractionControl, CarSpec.Builder::tractionControl)
                            .detail(spec -> spec.tractionControl() <= 0.0 ? "OFF" : "ON"))));

    /** 全項目を並べたもの。パケットはこの順で送る。 */
    public static final List<TunableParameter> ALL = flatten();

    private Tunables() {
    }

    private static List<TunableParameter> flatten() {
        List<TunableParameter> all = new ArrayList<>();
        for (Group group : GROUPS) {
            all.addAll(group.parameters());
        }
        return List.copyOf(all);
    }

    /** 最高出力。トルクの数字だけでは速さが読めないので併記する。 */
    private static String peakPower(CarSpec spec) {
        double best = 0.0;
        for (double rpm = spec.idleRpm(); rpm <= spec.redlineRpm(); rpm += 50.0) {
            best = Math.max(best, spec.engineTorque(rpm) * rpm * 2.0 * Math.PI / 60.0);
        }
        return String.format("%.0f", best / 735.5); // 馬力
    }

    /** 最高段でレブリミットまで回したときの速度。ギアの高さの目安。 */
    private static String topGearSpeed(CarSpec spec) {
        return String.format("%.0f", spec.topGearSpeed() * 3.6);
    }

    /** デフの種類。0 ならオープンデフ。 */
    private static String differentialType(CarSpec spec) {
        boolean open = spec.diffLockRatio() <= 0.0 && spec.diffPreload() <= 0.0;
        return open ? "オープンデフ" : "LSD";
    }

    /** 1way / 1.5way / 2way の呼び名。 */
    private static String differentialWay(CarSpec spec) {
        double coast = spec.diffCoastRatio();
        if (coast <= 0.05) return "1way";
        if (coast >= 0.95) return "2way";
        return "1.5way";
    }

    /** 駆動方式の呼び名。数字だけだと前後どちらか分からないため。 */
    private static String driveLayout(CarSpec spec) {
        double bias = spec.driveBias();
        if (bias <= 0.05) return "FF";
        if (bias >= 0.95) return "FR";
        return "4WD";
    }

    /**
     * タイヤ半径から決まるもの。車高（＝重心高）とファイナルと登坂限界。
     *
     * <p>タイヤを大きくすると車体が持ち上がり、同じギアリングを保つためにファイナルも上がる。</p>
     */
    private static String rideHeight(CarSpec spec) {
        return String.format("車高%.0f 終減速%.2f 登坂%.0fcm",
                spec.staticRideHeight() * 100.0, spec.finalDriveRatio(), spec.maxClimbStep() * 100.0);
    }

    /**
     * 荷重とストロークから決まるバネ定数と、そのときの沈み込み。
     *
     * <p>バネそのものは触れないので、代わりに<b>いくつになったか</b>を見せる。
     * 沈み込みが前後で揃っていることもここで分かる。</p>
     */
    private static String springs(CarSpec spec) {
        return String.format("%.0f/%.0fN/m 沈み%.1fcm",
                spec.frontSuspensionStiffness(), spec.rearSuspensionStiffness(),
                spec.staticWheelLoad(Wheel.FRONT_LEFT) / spec.frontSuspensionStiffness() * 100.0);
    }

    /** 車重と寸法から決まる慣性モーメント（ヨー／ピッチ／ロール）。 */
    private static String inertias(CarSpec spec) {
        return String.format("%.0f/%.0f/%.0f",
                spec.yawInertia(), spec.pitchInertia(), spec.rollInertia());
    }

    /** タイヤの摩擦係数から決まるブレーキとサイドブレーキの効き。 */
    private static String brakeForce(CarSpec spec) {
        // 目盛りが相対値なので、実際のμもここで見せる（見えないと路面の倍率と突き合わせられない）
        return String.format("μ%.2f 制動%.1f サイド%.1f 前%.0f%%",
                spec.tireFriction(), spec.brakeDecel(), spec.handbrakeDecel(), spec.brakeBias() * 100.0);
    }

    /** レブと段の比から決まる変速点。ハンチングしていないことがここで分かる。 */
    private static String shiftPoints(CarSpec spec) {
        return String.format("↑%.0f ↓%.0f 直後%.0f",
                spec.upshiftRpm(), spec.downshiftRpm(), spec.upshiftRpm() * spec.gearStep());
    }

    /** 前軸と後軸が受け持つ重さ [kg]。配分の数字より、何 kg 乗っているかの方が掴みやすい。 */
    private static String axleLoads(CarSpec spec) {
        double front = spec.mass() * spec.weightBias();
        return String.format("%.0f/%.0f", front, spec.mass() - front);
    }

    /**
     * ロール剛性の前後配分（前が何 %）。
     *
     * <p>ロール量そのものより、<b>アンダーかオーバーか</b>を決めるのがこの配分。
     * 前を厚くすると外前輪に荷重が集まって前が先に滑る（アンダー）。50% で中立。</p>
     *
     * <p>スタビは左右で逆向きに効くぶん、同じ数字ならバネの 2 倍のロール剛性を生む
     * （バネは軸あたり k・t²/2、スタビは k・t²）。</p>
     */
    private static String frontRollShare(CarSpec spec) {
        double front = spec.frontSuspensionStiffness() / 2.0 + spec.frontAntiRollStiffness();
        double rear = spec.rearSuspensionStiffness() / 2.0 + spec.rearAntiRollStiffness();
        double total = front + rear;
        return String.format("%.0f", total > 0.0 ? front / total * 100.0 : 50.0);
    }

}
