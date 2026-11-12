package com.jdmmc.kurumamod.client.input;

/**
 * コントローラの割り当て 1 つぶん。
 *
 * <p>軸とボタンのどちらでも割り当てられるようにしてある。アクセルをトリガー（軸）に
 * 割り当てればアナログになり、ボタンに割り当てればオンかオフかになる——<b>どちらも成り立つ</b>ので、
 * 種類を持たせて両方受け付ける。</p>
 *
 * <p><b>割り当てはそれぞれ機器を持つ</b>（{@link #device}）。ハンコン・ペダル・H シフター・サイドブレーキを
 * 別々の USB 機器としてつなぐのがシミュレーターではふつうなので、BeamNG や Assetto Corsa と同じく
 * 「どの機器の、どの軸／ボタンか」で覚える。</p>
 *
 * @param type       軸かボタンか。{@link Type#NONE} なら未割り当て
 * @param index      その種類の中での番号
 * @param invert     軸の向きを反転するか
 * @param fullAxis   軸が -1..1 で来るか（スティック・ハンドル）、0..1 で来るか（トリガー・ペダル）。
 *                   前者はアクセルに割り当てたときに -1..1 を 0..1 へ畳む必要がある
 * @param device     機器の id（{@link ControllerInput.Pad#id}）。null なら機器を問わず最初の機器
 *                   （機器を覚えていなかった頃の割り当てと、既定の割り当て）
 * @param deviceName 割り当てたときの機器の名前。つながっていないときに画面へ出すのと、id が合わないときの手がかり
 * @param deadzone   中立付近の遊び（0..1）。ゲームパッドのスティックは静止しても 0 にならないので要るが、
 *                   ハンコンやペダルに掛けると中立付近が効かなくなる
 * @param wheel      ハンコンとして扱うか（ハンドルの割り当てでだけ意味を持つ）。true なら直結
 *                   （{@code CarInput#directSteer}）。割り当てたときに機器から推測し、画面で切り替えられる
 */
public record ControllerBinding(Type type, int index, boolean invert, boolean fullAxis,
                                String device, String deviceName, double deadzone, boolean wheel) {

    public enum Type {
        NONE,
        AXIS,
        BUTTON
    }

    /** ゲームパッドの遊び。スティックは静止していても完全な 0 にはならない。機器を覚える前はこれが一律だった */
    public static final double GAMEPAD_DEADZONE = 0.12;
    /**
     * ハンコン・ペダルなど（対応表に無い生の機器）の遊び。掛けない——ハンドルの中立付近が効かなくなる。
     * ペダルの静止時の揺れは「踏んでいる」の閾値（{@code CarInput.AXIS_THRESHOLD}）が吸う
     */
    public static final double RAW_DEADZONE = 0.0;

    public static final ControllerBinding UNBOUND =
            new ControllerBinding(Type.NONE, -1, false, false, null, null, 0.0, false);

    /** 既定の割り当て（機器を問わない・ゲームパッドの遊び）。 */
    public static ControllerBinding axis(int index, boolean invert, boolean fullAxis) {
        return new ControllerBinding(Type.AXIS, index, invert, fullAxis, null, null, GAMEPAD_DEADZONE, false);
    }

    /** 既定の割り当て（機器を問わない）。 */
    public static ControllerBinding button(int index) {
        return new ControllerBinding(Type.BUTTON, index, false, false, null, null, 0.0, false);
    }

    /** 機器を指定して軸を割り当てる。遊びはその機器の種類で決める。 */
    public static ControllerBinding axis(ControllerInput.Pad pad, int index, boolean invert, boolean fullAxis) {
        return new ControllerBinding(Type.AXIS, index, invert, fullAxis, pad.id(), pad.name(),
                pad.gamepad() ? GAMEPAD_DEADZONE : RAW_DEADZONE, pad.wheel());
    }

    /** 機器を指定してボタンを割り当てる。 */
    public static ControllerBinding button(ControllerInput.Pad pad, int index) {
        return new ControllerBinding(Type.BUTTON, index, false, false, pad.id(), pad.name(), 0.0, false);
    }

    public boolean isBound() {
        return type != Type.NONE;
    }

    /** 向きだけ反転したもの。 */
    public ControllerBinding inverted() {
        return new ControllerBinding(type, index, !invert, fullAxis, device, deviceName, deadzone, wheel);
    }

    /** ハンコンとして扱うかを切り替えたもの。 */
    public ControllerBinding withWheel(boolean value) {
        return new ControllerBinding(type, index, invert, fullAxis, device, deviceName, deadzone, value);
    }

    /**
     * 今の入力から -1..1 の値を読む（遊びは抜いていない）。
     *
     * @param axes    軸の値
     * @param buttons ボタンの押下状態
     */
    public double read(float[] axes, boolean[] buttons) {
        switch (type) {
            case AXIS -> {
                if (index < 0 || index >= axes.length) {
                    return 0.0;
                }
                double value = axes[index];
                if (invert) {
                    value = -value;
                }
                // トリガーは静止位置が -1 なので 0..1 へ均す
                return fullAxis ? value : (value + 1.0) / 2.0;
            }
            case BUTTON -> {
                return index >= 0 && index < buttons.length && buttons[index] ? 1.0 : 0.0;
            }
            default -> {
                return 0.0;
            }
        }
    }
}
