package com.jdmmc.kurumamod.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import com.jdmmc.kurumamod.client.input.ControllerInput;

/**
 * コントローラを GLFW で読む（core の {@link ControllerInput.Device}）。<b>つながっている機器を全部読む</b>
 * （ハンコン・ペダル・シフター・サイドブレーキが別々の USB 機器でも、それぞれを割り当てられるように）。
 *
 * <p>{@code glfwGetGamepadState} は GLFW が持っている対応表で多くのコントローラを Xbox 系の並びへ
 * 正規化してくれるので、まずこれを試し、対応表に無い機器（ハンコン・ペダルなどはたいていこちら）では
 * 生の軸とボタンへ落とす。</p>
 */
public final class ControllerDevice implements ControllerInput.Device {

    /** 使い回すゲームパッドの状態。解放してはいけないので手放さない。 */
    private GLFWGamepadState gamepadState;

    private List<ControllerInput.Reading> readings = List.of();

    @Override
    public void poll() {
        try {
            pollUnsafe();
        } catch (RuntimeException e) {
            readings = List.of();
        }
    }

    private void pollUnsafe() {
        List<ControllerInput.Reading> result = new ArrayList<>();
        for (int id = GLFW.GLFW_JOYSTICK_1; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
            if (!GLFW.glfwJoystickPresent(id)) {
                continue;
            }
            ControllerInput.Reading reading = read(id);
            if (reading != null) {
                result.add(reading);
            }
        }
        readings = result;
    }

    private ControllerInput.Reading read(int joystick) {
        String guid = GLFW.glfwGetJoystickGUID(joystick);
        String name = GLFW.glfwGetJoystickName(joystick);
        // 対応表がある機器は Xbox 系の並びへ正規化して読む
        if (GLFW.glfwJoystickIsGamepad(joystick)) {
            GLFWGamepadState state = gamepadState();
            if (GLFW.glfwGetGamepadState(joystick, state)) {
                FloatBuffer stateAxes = state.axes();
                float[] axes = new float[stateAxes.limit()];
                stateAxes.get(0, axes);
                ByteBuffer stateButtons = state.buttons();
                boolean[] buttons = new boolean[stateButtons.limit()];
                for (int i = 0; i < buttons.length; i++) {
                    buttons[i] = stateButtons.get(i) == GLFW.GLFW_PRESS;
                }
                String gamepadName = GLFW.glfwGetGamepadName(joystick);
                return new ControllerInput.Reading(guid, gamepadName != null ? gamepadName : name, true, false, joystick,
                        axes, buttons);
            }
        }
        // 対応表に無い機器は生のまま（GLFW は十字キー（ハット）を 4 つのボタンとして後ろへ足している）
        FloatBuffer rawAxes = GLFW.glfwGetJoystickAxes(joystick);
        ByteBuffer rawButtons = GLFW.glfwGetJoystickButtons(joystick);
        if (rawAxes == null && rawButtons == null) {
            // 調べている間に抜かれた
            return null;
        }
        float[] axes = new float[rawAxes == null ? 0 : rawAxes.limit()];
        if (rawAxes != null) {
            rawAxes.get(0, axes);
        }
        boolean[] buttons = new boolean[rawButtons == null ? 0 : rawButtons.limit()];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = rawButtons.get(i) == GLFW.GLFW_PRESS;
        }
        // GLFW は機器の種類を返さない。対応表に無い機器はハンコン・ペダルの類が多いので、ハンコンと推測しておく
        return new ControllerInput.Reading(guid, name, false, true, joystick, axes, buttons);
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

    @Override
    public List<ControllerInput.Reading> readings() {
        return readings;
    }
}
