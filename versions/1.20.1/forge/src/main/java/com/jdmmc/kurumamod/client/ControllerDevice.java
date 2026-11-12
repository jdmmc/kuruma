package com.jdmmc.kurumamod.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import com.jdmmc.kurumamod.client.input.ControllerInput;

/**
 * コントローラを GLFW で読む（core の {@link ControllerInput.Device}）。{@code glfwGetGamepadState} は GLFW が
 * 持っている対応表で多くのコントローラを Xbox 系の並びへ正規化してくれるので、まずこれを試し、
 * 対応表に無い機器では生の軸とボタンへ落とす。
 */
final class ControllerDevice implements ControllerInput.Device {

    /** 使うコントローラ。-1 なら未接続。 */
    private int joystick = -1;

    /** 使い回すゲームパッドの状態。解放してはいけないので手放さない。 */
    private GLFWGamepadState gamepadState;

    private float[] axes = new float[0];
    private boolean[] buttons = new boolean[0];

    @Override
    public void poll() {
        try {
            pollUnsafe();
        } catch (RuntimeException e) {
            axes = new float[0];
            buttons = new boolean[0];
            joystick = -1;
        }
    }

    private void pollUnsafe() {
        if (joystick < 0 || !GLFW.glfwJoystickPresent(joystick)) {
            joystick = findJoystick();
        }
        if (joystick < 0) {
            axes = new float[0];
            buttons = new boolean[0];
            return;
        }
        // 対応表がある機器は Xbox 系の並びへ正規化して読む
        if (GLFW.glfwJoystickIsGamepad(joystick)) {
            GLFWGamepadState state = gamepadState();
            if (GLFW.glfwGetGamepadState(joystick, state)) {
                FloatBuffer stateAxes = state.axes();
                axes = new float[stateAxes.limit()];
                stateAxes.get(axes);
                ByteBuffer stateButtons = state.buttons();
                buttons = new boolean[stateButtons.limit()];
                for (int i = 0; i < buttons.length; i++) {
                    buttons[i] = stateButtons.get(i) == GLFW.GLFW_PRESS;
                }
                return;
            }
        }
        // 対応表に無い機器は生のまま
        FloatBuffer rawAxes = GLFW.glfwGetJoystickAxes(joystick);
        axes = new float[rawAxes == null ? 0 : rawAxes.limit()];
        if (rawAxes != null) {
            rawAxes.get(axes);
        }
        ByteBuffer rawButtons = GLFW.glfwGetJoystickButtons(joystick);
        buttons = new boolean[rawButtons == null ? 0 : rawButtons.limit()];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = rawButtons.get(i) == GLFW.GLFW_PRESS;
        }
    }

    /**
     * ゲームパッドの状態を受ける入れ物。<b>1 度だけ作って使い回す。</b>
     *
     * <p>{@code GLFWGamepadState.create()} が返すのは
     * {@code BufferUtils.createByteBuffer} による<b>GC 管理のバッファ</b>で、
     * {@code free()} してはいけない。try-with-resources に入れると
     * {@code NativeResource.close()} から解放が呼ばれ、jemalloc が確保していない
     * メモリを解放することになってヒープが壊れる。毎ティック確保していたため、
     * 約 15 分走ったところで EXCEPTION_ACCESS_VIOLATION で落ちた。</p>
     */
    private GLFWGamepadState gamepadState() {
        if (gamepadState == null) {
            gamepadState = GLFWGamepadState.create();
        }
        return gamepadState;
    }

    private static int findJoystick() {
        for (int id = GLFW.GLFW_JOYSTICK_1; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
            if (GLFW.glfwJoystickPresent(id)) {
                return id;
            }
        }
        return -1;
    }

    @Override
    public float[] axes() {
        return axes;
    }

    @Override
    public boolean[] buttons() {
        return buttons;
    }

    @Override
    public boolean connected() {
        return joystick >= 0;
    }

    @Override
    public String name() {
        return joystick < 0 ? null : GLFW.glfwGetJoystickName(joystick);
    }
}
