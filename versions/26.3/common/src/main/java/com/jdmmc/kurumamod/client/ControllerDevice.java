package com.jdmmc.kurumamod.client;

import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLGUID;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDL_GUID;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.jdmmc.kurumamod.client.input.ControllerInput;

/**
 * コントローラを SDL3 で読む（core の {@link ControllerInput.Device}）。26.3 でウィンドウと入力が GLFW から SDL3 に
 * なった。SDL の gamepad は対応表で多くのコントローラを Xbox 系の並びへ正規化してくれるので、まずこれを試し、
 * 対応表に無い機器では生の軸とボタン（joystick）へ落とす。<b>つながっている機器を全部開いて読む</b>
 * （ハンコン・ペダル・シフター・サイドブレーキが別々の USB 機器でも、それぞれを割り当てられるように）。
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

    /** 何ティックおきに新しくつながった機器を探すか。毎ティック一覧を取るほどのことではない。 */
    private static final int RESCAN_TICKS = 20;

    /** GUID を 16 進の文字列にするときの長さ（32 文字と終端）。 */
    private static final int GUID_STRING_BYTES = 33;

    /** 開いている機器。SDL の ID（つなぐたびに増える）の順に並べる＝つないだ順。 */
    private final Map<Integer, Opened> opened = new TreeMap<>();
    /** SDL のゲームパッドのサブシステム。0 = まだ、1 = 使える、-1 = 立ち上げに失敗した。 */
    private int sdlState;
    private int rescanCooldown;

    /**
     * GUID を受ける入れ物と、文字列にするバッファ。<b>1 度だけ作って使い回す</b>（どちらも GC 管理の
     * バッファなので {@code free()} してはいけない。{@code GLFWGamepadState} で踏んだのと同じ）。
     */
    private SDL_GUID guidStruct;
    private ByteBuffer guidText;

    private List<ControllerInput.Reading> readings = List.of();

    /** 開いた機器 1 台。どちらか一方のハンドルだけを持つ。 */
    private static final class Opened {
        /** 対応表のある機器をゲームパッドとして開いたもの。0 なら開いていない。 */
        long gamepad;
        /** 対応表の無い機器を生のまま開いたもの。0 なら開いていない。 */
        long rawJoystick;
        String guid;
        /** SDL の ID。フォースフィードバックが機器を引き直すのに使う */
        int id;
        /**
         * SDL がハンコンと言っているか（{@code SDL_JOYSTICK_TYPE_WHEEL}）。Xbox 向けのハンコンはゲームパッドとして
         * 開けてしまうので、ゲームパッドでもこれを見る。生の機器は種類が分からなくてもハンコンと推測する
         * （一覧に無い DD ハンコンなど）
         */
        boolean wheel;
    }

    @Override
    public void poll() {
        try {
            pollUnsafe();
        } catch (RuntimeException | LinkageError e) {
            // SDL の native が読めない環境でも運転は続けられるべきなので、ここで止める
            readings = List.of();
            sdlState = -1;
            opened.clear();
        }
    }

    private void pollUnsafe() {
        if (!initSdl()) {
            readings = List.of();
            return;
        }
        // 抜かれた機器を閉じる
        Iterator<Opened> it = opened.values().iterator();
        while (it.hasNext()) {
            Opened device = it.next();
            if (!stillConnected(device)) {
                close(device);
                it.remove();
            }
        }
        if (rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_TICKS;
            openNew();
        }
        List<ControllerInput.Reading> result = new ArrayList<>(opened.size());
        for (Opened device : opened.values()) {
            result.add(device.gamepad != 0L ? readGamepad(device) : readRaw(device));
        }
        readings = result;
    }

    /** 対応表がある機器は Xbox 系の並びへ正規化して読む。 */
    private static ControllerInput.Reading readGamepad(Opened device) {
        long gamepad = device.gamepad;
        float[] axes = new float[SDLGamepad.SDL_GAMEPAD_AXIS_COUNT];
        for (int i = 0; i < axes.length; i++) {
            float value = SDLGamepad.SDL_GetGamepadAxis(gamepad, i) / 32767.0F;
            // SDL のトリガーは 0〜1、GLFW は離して −1・引ききって 1。保存した割り当てのため GLFW に揃える
            if (i == ControllerInput.AXIS_LEFT_TRIGGER || i == ControllerInput.AXIS_RIGHT_TRIGGER) {
                value = value * 2.0F - 1.0F;
            }
            axes[i] = Math.max(-1.0F, Math.min(1.0F, value));
        }
        boolean[] buttons = new boolean[SDL_BUTTON_OF.length];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = SDLGamepad.SDL_GetGamepadButton(gamepad, SDL_BUTTON_OF[i]);
        }
        return new ControllerInput.Reading(device.guid, SDLGamepad.SDL_GetGamepadName(gamepad), true, device.wheel, device.id,
                axes, buttons);
    }

    /** 対応表に無い機器は生のまま。GLFW と同じく、ハット（十字キー）は上・右・下・左の 4 つのボタンとして後ろへ足す。 */
    private static ControllerInput.Reading readRaw(Opened device) {
        long rawJoystick = device.rawJoystick;
        int axisCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickAxes(rawJoystick));
        float[] axes = new float[axisCount];
        for (int i = 0; i < axisCount; i++) {
            axes[i] = Math.max(-1.0F, Math.min(1.0F, SDLJoystick.SDL_GetJoystickAxis(rawJoystick, i) / 32767.0F));
        }
        int buttonCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickButtons(rawJoystick));
        int hatCount = Math.max(0, SDLJoystick.SDL_GetNumJoystickHats(rawJoystick));
        boolean[] buttons = new boolean[buttonCount + hatCount * 4];
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
        return new ControllerInput.Reading(device.guid, SDLJoystick.SDL_GetJoystickName(rawJoystick), false, true, device.id,
                axes, buttons);
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

    private static boolean stillConnected(Opened device) {
        return device.gamepad != 0L ? SDLGamepad.SDL_GamepadConnected(device.gamepad)
                : device.rawJoystick != 0L && SDLJoystick.SDL_JoystickConnected(device.rawJoystick);
    }

    /** まだ開いていない機器を全部開く。ゲームパッドとして開けるならそちらを優先する。 */
    private void openNew() {
        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids == null) {
            return;
        }
        try {
            for (int i = ids.position(); i < ids.limit(); i++) {
                int id = ids.get(i);
                if (opened.containsKey(id)) {
                    continue;
                }
                Opened device = new Opened();
                if (SDLGamepad.SDL_IsGamepad(id)) {
                    device.gamepad = SDLGamepad.SDL_OpenGamepad(id);
                }
                if (device.gamepad == 0L) {
                    device.rawJoystick = SDLJoystick.SDL_OpenJoystick(id);
                }
                if (device.gamepad == 0L && device.rawJoystick == 0L) {
                    continue;
                }
                device.id = id;
                device.guid = guidOf(id);
                device.wheel = SDLJoystick.SDL_GetJoystickTypeForID(id) == SDLJoystick.SDL_JOYSTICK_TYPE_WHEEL;
                opened.put(id, device);
            }
        } finally {
            SDLStdinc.SDL_free(ids);
        }
    }

    /** SDL の GUID を 16 進 32 文字で。GLFW の {@code glfwGetJoystickGUID} と同じ形式。 */
    private String guidOf(int id) {
        if (guidStruct == null) {
            guidStruct = SDL_GUID.create();
            guidText = BufferUtils.createByteBuffer(GUID_STRING_BYTES);
        }
        SDLJoystick.SDL_GetJoystickGUIDForID(id, guidStruct);
        SDLGUID.SDL_GUIDToString(guidStruct, guidText);
        StringBuilder text = new StringBuilder(GUID_STRING_BYTES - 1);
        for (int i = 0; i < guidText.capacity(); i++) {
            byte b = guidText.get(i);
            if (b == 0) {
                break;
            }
            text.append((char) b);
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static void close(Opened device) {
        if (device.gamepad != 0L) {
            SDLGamepad.SDL_CloseGamepad(device.gamepad);
        }
        if (device.rawJoystick != 0L) {
            SDLJoystick.SDL_CloseJoystick(device.rawJoystick);
        }
    }

    @Override
    public List<ControllerInput.Reading> readings() {
        return readings;
    }

    /**
     * 開いている機器の SDL のジョイスティック（{@code SDL_Joystick*}）。フォースフィードバックが
     * {@code SDL_OpenHapticFromJoystick} に渡す。開いていなければ 0。
     *
     * @param id {@link ControllerInput.Pad#nativeId}
     */
    public long joystickHandle(int id) {
        Opened device = opened.get(id);
        if (device == null) {
            return 0L;
        }
        return device.gamepad != 0L ? SDLGamepad.SDL_GetGamepadJoystick(device.gamepad) : device.rawJoystick;
    }
}
