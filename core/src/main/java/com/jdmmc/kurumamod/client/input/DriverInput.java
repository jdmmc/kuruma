package com.jdmmc.kurumamod.client.input;

import com.jdmmc.kurumamod.physics.CarInput;

/**
 * 運転入力の組み立て方。<b>キーをどう読むか（{@code KeyMapping}・GLFW・SDL）は版の {@code CarClientDriver}</b>で、
 * ここは読んだ結果から {@link CarInput} を決めるところだけ。
 *
 * <ul>
 *   <li>コントローラを触っている間はそちらを使い、触っていなければキーボードへ戻す（{@link #choose}）。
 *       どちらかに固定すると、持ち替えるたびに設定を触ることになる</li>
 *   <li>スタートの信号の間は止めておく（{@link #frozen}）</li>
 *   <li>画面を開いたままの変速は、押した瞬間だけを拾う（{@link #throughScreen}）</li>
 * </ul>
 */
public final class DriverInput {

    /** 変速のキーが前のティックに押されていたか。画面越しの変速で、押した瞬間を取るのに使う。 */
    private static boolean shiftUpHeld;
    private static boolean shiftDownHeld;

    private DriverInput() {
    }

    /** 触っているコントローラの入力。触っていなければ（つながっていなければ）null。 */
    private static CarInput activePad() {
        CarInput pad = ControllerInput.toCarInput();
        return pad != null && !ControllerInput.isIdle() ? pad : null;
    }

    /**
     * コントローラを触っていればその入力、触っていなければキーボードの入力。
     *
     * <p><b>H シフターの段はどちらにも載せる。</b>ペダルから足を離した（コントローラが「触っていない」になった）
     * だけでキーボードの入力へ切り替わり、シフターをニュートラルへ抜いたのが伝わらなくなるため。</p>
     */
    public static CarInput choose(CarInput keyboard) {
        CarInput pad = activePad();
        return (pad != null ? pad : keyboard).withGearSelect(ControllerInput.selectedGear());
    }

    /**
     * スタートの信号の間の入力。<b>惰性で進まないよう止め、ハンドルだけは渡す</b>（グリッドで向きを
     * 合わせられるように）。サイドを引き、前へ動いていればブレーキも掛ける——止まってからブレーキを
     * 踏み続けると、オートマはバックに入ってしまうので、前へ動いている間だけ。
     *
     * @param forward 前へ動いているか
     */
    public static CarInput frozen(boolean forward, boolean left, boolean right) {
        CarInput pad = activePad();
        // H シフターの段は渡す（グリッドで 1 速に入れて待てるように）
        int gear = ControllerInput.selectedGear();
        if (pad != null) {
            return CarInput.analog(0.0, forward ? 1.0 : 0.0, pad.steerAxis(), false, false, 1.0)
                    .withGearSelect(gear).withDirectSteer(pad.directSteer());
        }
        return new CarInput(false, forward, left, right, false, false, true).withGearSelect(gear);
    }

    /**
     * 画面の外で、変速のキーが押されているかを覚えておく。
     *
     * <p>画面越しの変速は押しっぱなしの状態で閉じたときに 1 回ぶん誤って拾わないよう、
     * 画面の外では「押されている」で揃えておく（離すまで次の押下にならない）。</p>
     */
    public static void trackShiftKeys(boolean upHeld, boolean downHeld) {
        shiftUpHeld = upHeld;
        shiftDownHeld = downHeld;
    }

    /**
     * 画面を開いたままの運転入力。キーは押されているか（物理的な状態）で受け取り、変速は押した瞬間だけを拾う。
     */
    public static CarInput throughScreen(boolean up, boolean down, boolean left, boolean right,
                                         boolean shiftUpDown, boolean shiftDownDown, boolean handbrake) {
        boolean shiftUp = shiftUpDown && !shiftUpHeld;
        boolean shiftDown = shiftDownDown && !shiftDownHeld;
        trackShiftKeys(shiftUpDown, shiftDownDown);
        return choose(new CarInput(up, down, left, right, shiftUp, shiftDown, handbrake));
    }
}
