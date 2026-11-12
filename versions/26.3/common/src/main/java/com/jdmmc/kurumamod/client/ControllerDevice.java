package com.jdmmc.kurumamod.client;

import com.mojang.logging.LogUtils;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDLStdinc;
import org.slf4j.Logger;

import java.nio.IntBuffer;
import com.jdmmc.kurumamod.client.input.ControllerInput;

/**
 * コントローラを SDL3 で読む（core の {@link ControllerInput.Device}）。26.3 でウィンドウと入力が GLFW から SDL3 に
 * なった。SDL の gamepad は対応表で多くのコントローラを Xbox 系の並びへ正規化してくれるので、まずこれを試し、
 * 対応表に無い機器では生の軸とボタン（joystick）へ落とす。
 *
 * <p><b>軸とボタンの番号は GLFW のゲームパッドの並びへ並べ替える</b>（{@link ControllerInput#AXIS_LEFT_X} など）。
 * 保存してある割り当てを 1.21.1 と同じに読むため。値の範囲も GLFW と同じ（スティック −1〜1、トリガーは離して −1・
 * 引ききって 1）。</p>
 *
 * <p><b>Minecraft は SDL を映像（{@code SDL_INIT_VIDEO}）だけで初期化している。</b>ゲームパッドの
 * サブシステムは最初の {@link #poll()}（メインスレッド）で自分で立ち上げる。状態は Minecraft の
 * イベントの汲み出し（{@code SDL_PollEvent}）で更新されるので、こちらで汲む必要は無い。</p>
 */
public final class ControllerDevice implements ControllerInput.Device {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * GLFW の番号 → SDL の番号。15 番より後ろは GLFW に無かったボタン（背面パドル・タッチパッドなど）で、
     * SDL のものを後ろへ足してある。
     */
    private static final int[] SDL_BUTTON_OF = {
            SDLGamepad.SDL_GAMEPAD_BUTTON_SOUTH, SDLGamepad.SDL_GAMEPAD_BUTTON_EAST,
            SDLGamepad.SDL_GAMEPAD_BUTTON_WEST, SDLGamepad.SDL_GAMEPAD_BUTTON_NORTH,
            SDLGamepad.SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, SDLGamepad.SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER,
            SDLGamepad.SDL_GAMEPAD_BUTTON_BACK, SDLGamepad.SDL_GAMEPAD_BUTTON_START,
            SDLGamepad.SDL_GAMEPAD_BUTTON_GUIDE, SDLGamepad.SDL_GAMEPAD_BUTTON_LEFT_STICK,
            SDLGamepad.SDL_GAMEPAD_BUTTON_RIGHT_STICK, SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_UP,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_RIGHT, SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_DOWN,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_LEFT,
            SDLGamepad.SDL_GAMEPAD_BUTTON_MISC1, SDLGamepad.SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1,
            SDLGamepad.SDL_GAMEPAD_BUTTON_LEFT_PADDLE1, SDLGamepad.SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2,
            SDLGamepad.SDL_GAMEPAD_BUTTON_LEFT_PADDLE2, SDLGamepad.SDL_GAMEPAD_BUTTON_TOUCHPAD,
            SDLGamepad.SDL_GAMEPAD_BUTTON_MISC2, SDLGamepad.SDL_GAMEPAD_BUTTON_MISC3,
            SDLGamepad.SDL_GAMEPAD_BUTTON_MISC4, SDLGamepad.SDL_GAMEPAD_BUTTON_MISC5,
            SDLGamepad.SDL_GAMEPAD_BUTTON_MISC6,
    };

    /** 未接続のとき、何ティックおきに探し直すか。毎ティック一覧を取るほどのことではない。 */
    private static final int RESCAN_TICKS = 20;

    /** 使うコントローラの SDL の ID。-1 なら未接続。 */
    private int joystick = -1;
    /** 開いているゲームパッド（対応表のある機器）。0 なら開いていない。 */
    private long gamepad;
    /** 対応表の無い機器を生のまま開いたもの。0 なら開いていない。 */
    private long rawJoystick;
    /** SDL のゲームパッドのサブシステム。0 = まだ、1 = 使える、-1 = 立ち上げに失敗した。 */
    private int sdlState;
    private int rescanCooldown;

    private float[] axes = new float[0];
    private boolean[] buttons = new boolean[0];

    @Override
    public void poll() {
        try {
            pollUnsafe();
        } catch (RuntimeException | LinkageError e) {
            // SDL の native が読めない環境でも運転は続けられるべきなので、ここで止める
            axes = new float[0];
            buttons = new boolean[0];
            sdlState = -1;
            gamepad = 0L;
            rawJoystick = 0L;
            joystick = -1;
        }
    }

    private void pollUnsafe() {
        if (!initSdl()) {
            axes = new float[0];
            buttons = new boolean[0];
            return;
        }
        if (joystick >= 0 && !stillConnected()) {
            close();
        }
        if (joystick < 0 && rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_TICKS;
            open();
        }
        if (joystick < 0) {
            axes = new float[0];
            buttons = new boolean[0];
            return;
        }
        // 対応表がある機器は Xbox 系の並びへ正規化して読む
        if (gamepad != 0L) {
            axes = new float[SDLGamepad.SDL_GAMEPAD_AXIS_COUNT];
            for (int i = 0; i < axes.length; i++) {
                float value = SDLGamepad.SDL_GetGamepadAxis(gamepad, i) / 32767.0F;
                // SDL のトリガーは 0〜1、GLFW は離して −1・引ききって 1。保存した割り当てのため GLFW に揃える
                if (i == ControllerInput.AXIS_LEFT_TRIGGER || i == ControllerInput.AXIS_RIGHT_TRIGGER) {
                    value = value * 2.0F - 1.0F;
                }
                axes[i] = Math.max(-1.0F, Math.min(1.0F, value));
            }
            buttons = new boolean[SDL_BUTTON_OF.length];
            for (int i = 0; i < buttons.length; i++) {
                buttons[i] = SDLGamepad.SDL_GetGamepadButton(gamepad, SDL_BUTTON_OF[i]);
            }
            return;
        }
        // 対応表に無い機器は生のまま。GLFW と同じく、ハット（十字キー）は上・右・下・左の 4 つのボタンとして後ろへ足す
        int axisCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickAxes(rawJoystick));
        axes = new float[axisCount];
        for (int i = 0; i < axisCount; i++) {
            axes[i] = Math.max(-1.0F, Math.min(1.0F, SDLJoystick.SDL_GetJoystickAxis(rawJoystick, i) / 32767.0F));
        }
        int buttonCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickButtons(rawJoystick));
        int hatCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickHats(rawJoystick));
        buttons = new boolean[buttonCount + hatCount * 4];
        for (int i = 0; i < buttonCount; i++) {
            buttons[i] = SDLJoystick.SDL_GetJoystickButton(rawJoystick, i);
        }
        for (int hat = 0; hat < hatCount; hat++) {
            int bits = SDLJoystick.SDL_GetJoystickHat(rawJoystick, hat);
            int base = buttonCount + hat * 4;
            buttons[base] = (bits & 0x01) != 0;     // 上
            buttons[base + 1] = (bits & 0x02) != 0; // 右
            buttons[base + 2] = (bits & 0x04) != 0; // 下
            buttons[base + 3] = (bits & 0x08) != 0; // 左
        }
    }

    /**
     * SDL のゲームパッドのサブシステムを立ち上げる。立ち上がっていれば true。
     *
     * <p><b>メインスレッドから呼ぶこと</b>（SDL の決まり）。{@link #poll()} はクライアントのティックから
     * 呼ばれるので満たしている。失敗したら二度と試さない（毎ティックエラーを出さないため）。</p>
     */
    private boolean initSdl() {
        if (sdlState == 0) {
            if ((SDLInit.SDL_WasInit(SDLInit.SDL_INIT_GAMEPAD) & SDLInit.SDL_INIT_GAMEPAD) != 0
                    || SDLInit.SDL_InitSubSystem(SDLInit.SDL_INIT_GAMEPAD)) {
                sdlState = 1;
            } else {
                sdlState = -1;
                LOGGER.warn("SDL のゲームパッドを使えませんでした（コントローラ無しで続けます）: {}",
                        SDLError.SDL_GetError());
            }
        }
        return sdlState > 0;
    }

    private boolean stillConnected() {
        return gamepad != 0L ? SDLGamepad.SDL_GamepadConnected(gamepad)
                : rawJoystick != 0L && SDLJoystick.SDL_JoystickConnected(rawJoystick);
    }

    /** つながっている最初の機器を開く。ゲームパッドとして開けるならそちらを優先する。 */
    private void open() {
        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids == null) {
            return;
        }
        try {
            for (int i = ids.position(); i < ids.limit(); i++) {
                int id = ids.get(i);
                if (SDLGamepad.SDL_IsGamepad(id)) {
                    long handle = SDLGamepad.SDL_OpenGamepad(id);
                    if (handle != 0L) {
                        gamepad = handle;
                        joystick = id;
                        return;
                    }
                }
                long handle = SDLJoystick.SDL_OpenJoystick(id);
                if (handle != 0L) {
                    rawJoystick = handle;
                    joystick = id;
                    return;
                }
            }
        } finally {
            SDLStdinc.SDL_free(ids);
        }
    }

    private void close() {
        if (gamepad != 0L) {
            SDLGamepad.SDL_CloseGamepad(gamepad);
        }
        if (rawJoystick != 0L) {
            SDLJoystick.SDL_CloseJoystick(rawJoystick);
        }
        gamepad = 0L;
        rawJoystick = 0L;
        joystick = -1;
        rescanCooldown = 0;
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
        if (gamepad != 0L) {
            return SDLGamepad.SDL_GetGamepadName(gamepad);
        }
        return rawJoystick != 0L ? SDLJoystick.SDL_GetJoystickName(rawJoystick) : null;
    }
}
