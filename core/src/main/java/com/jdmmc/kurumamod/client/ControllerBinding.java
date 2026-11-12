package com.jdmmc.kurumamod.client;

/**
 * コントローラの割り当て 1 つぶん。
 *
 * <p>軸とボタンのどちらでも割り当てられるようにしてある。アクセルをトリガー（軸）に
 * 割り当てればアナログになり、ボタンに割り当てれば 0/100 になる——<b>どちらも成立する</b>ので、
 * 種類を持たせて両方受け付ける。</p>
 *
 * @param type     軸かボタンか。{@link Type#NONE} なら未割り当て
 * @param index    その種類の中での番号
 * @param invert   軸の向きを反転するか
 * @param fullAxis 軸が -1..1 で来るか（スティック）、0..1 で来るか（トリガー）。
 *                 前者はアクセルに割り当てたときに -1..1 を 0..1 へ畳む必要がある
 */
public record ControllerBinding(Type type, int index, boolean invert, boolean fullAxis) {

    public enum Type {
        NONE,
        AXIS,
        BUTTON
    }

    public static final ControllerBinding UNBOUND = new ControllerBinding(Type.NONE, -1, false, false);

    public static ControllerBinding axis(int index, boolean invert, boolean fullAxis) {
        return new ControllerBinding(Type.AXIS, index, invert, fullAxis);
    }

    public static ControllerBinding button(int index) {
        return new ControllerBinding(Type.BUTTON, index, false, false);
    }

    public boolean isBound() {
        return type != Type.NONE;
    }

    /**
     * 今の入力から -1..1 の値を読む。
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
