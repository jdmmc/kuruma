package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.physics.CarInput;
import com.mojang.logging.LogUtils;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDLStdinc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * コントローラの読み取りと、割り当ての保存。
 *
 * <p>SDL3 をそのまま叩く（26.3 でウィンドウと入力が GLFW から SDL3 になった）。SDL の gamepad は
 * 対応表で多くのコントローラを Xbox 系の並びへ正規化してくれるので、まずこれを試し、
 * 対応表に無い機器では生の軸とボタン（joystick）へ落とす。</p>
 *
 * <p><b>軸とボタンの番号は GLFW のゲームパッドの並びのまま</b>（{@link #AXIS_LEFT_X}・{@link #BUTTON_A} など）。
 * 保存してある割り当て（{@code kurumamod-controller.json} の {@code index}）をそのまま使えるように、
 * SDL の番号から GLFW の番号へ並べ替えて {@link #axes()}・{@link #buttons()} に入れる。値の範囲も GLFW と同じ
 * （スティック −1〜1、トリガーは離して −1・引ききって 1）。</p>
 *
 * <p><b>Minecraft は SDL を映像（{@code SDL_INIT_VIDEO}）だけで初期化している。</b>ゲームパッドの
 * サブシステムは最初の {@link #poll()}（メインスレッド）で自分で立ち上げる。状態は Minecraft の
 * イベントの汲み出し（{@code SDL_PollEvent}）で更新されるので、こちらで汲む必要は無い。</p>
 *
 * <p><b>アナログ入力は「0/100 しかない」という前提を崩す。</b>アクセルを微妙に開ける、
 * 舵を少しだけ当てる、といった操作ができるようになるので、トラクションコントロールや
 * 切れ角の上限といった補助の要否は変わってくる。</p>
 *
 * <p>割り当ては {@code config/kurumamod-controller.json} に保存する。</p>
 */
public final class ControllerInput {

    private static final String FILE = "kurumamod-controller.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 中立付近の遊び。スティックは静止していても完全な 0 にはならない。 */
    private static final double DEADZONE = 0.12;

    private static final Logger LOGGER = LogUtils.getLogger();

    // GLFW のゲームパッドの並び。割り当ての保存形式（index）がこの番号なので変えないこと
    public static final int AXIS_LEFT_X = 0;
    public static final int AXIS_LEFT_Y = 1;
    public static final int AXIS_RIGHT_X = 2;
    public static final int AXIS_RIGHT_Y = 3;
    public static final int AXIS_LEFT_TRIGGER = 4;
    public static final int AXIS_RIGHT_TRIGGER = 5;
    public static final int BUTTON_A = 0;
    public static final int BUTTON_B = 1;
    public static final int BUTTON_X = 2;
    public static final int BUTTON_Y = 3;
    public static final int BUTTON_LEFT_BUMPER = 4;
    public static final int BUTTON_RIGHT_BUMPER = 5;
    public static final int BUTTON_BACK = 6;
    public static final int BUTTON_START = 7;
    public static final int BUTTON_GUIDE = 8;
    public static final int BUTTON_LEFT_THUMB = 9;
    public static final int BUTTON_RIGHT_THUMB = 10;
    public static final int BUTTON_DPAD_UP = 11;
    public static final int BUTTON_DPAD_RIGHT = 12;
    public static final int BUTTON_DPAD_DOWN = 13;
    public static final int BUTTON_DPAD_LEFT = 14;

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

    private static final Map<ControllerAction, ControllerBinding> BINDINGS =
            new EnumMap<>(ControllerAction.class);
    private static boolean loaded;

    /** 使うコントローラの SDL の ID。-1 なら未接続。 */
    private static int joystick = -1;
    /** 開いているゲームパッド（対応表のある機器）。0 なら開いていない。 */
    private static long gamepad;
    /** 対応表の無い機器を生のまま開いたもの。0 なら開いていない。 */
    private static long rawJoystick;
    private static boolean enabled = true;

    /** SDL のゲームパッドのサブシステム。0 = まだ、1 = 使える、-1 = 立ち上げに失敗した。 */
    private static int sdlState;
    private static int rescanCooldown;

    // 読み取った生の状態。割り当て画面でも使う
    private static float[] axes = new float[0];
    private static boolean[] buttons = new boolean[0];

    // 押した瞬間を取るための前回の状態
    private static final Map<ControllerAction, Boolean> PRESSED =
            new EnumMap<>(ControllerAction.class);

    private ControllerInput() {
    }

    // ------------------------------------------------------------------
    // 割り当て
    // ------------------------------------------------------------------

    public static ControllerBinding binding(ControllerAction action) {
        load();
        return BINDINGS.getOrDefault(action, action.defaultBinding());
    }

    public static void bind(ControllerAction action, ControllerBinding binding) {
        load();
        BINDINGS.put(action, binding);
        save();
    }

    public static void resetBindings() {
        load();
        BINDINGS.clear();
        for (ControllerAction action : ControllerAction.values()) {
            BINDINGS.put(action, action.defaultBinding());
        }
        save();
    }

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        load();
        enabled = value;
        save();
    }

    private static Path file() {
        return Platform.configDir().resolve(FILE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (ControllerAction action : ControllerAction.values()) {
            BINDINGS.put(action, action.defaultBinding());
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            if (root.has("enabled")) {
                enabled = root.get("enabled").getAsBoolean();
            }
            for (ControllerAction action : ControllerAction.values()) {
                if (!root.has(action.key())) {
                    continue;
                }
                JsonObject entry = root.getAsJsonObject(action.key());
                BINDINGS.put(action, new ControllerBinding(
                        ControllerBinding.Type.valueOf(entry.get("type").getAsString()),
                        entry.get("index").getAsInt(),
                        entry.has("invert") && entry.get("invert").getAsBoolean(),
                        entry.has("full") && entry.get("full").getAsBoolean()));
            }
        } catch (IOException | RuntimeException e) {
            // 壊れていても運転はできるべきなので、既定の割り当てのまま進む
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        for (Map.Entry<ControllerAction, ControllerBinding> entry : BINDINGS.entrySet()) {
            ControllerBinding binding = entry.getValue();
            JsonObject object = new JsonObject();
            object.addProperty("type", binding.type().name());
            object.addProperty("index", binding.index());
            object.addProperty("invert", binding.invert());
            object.addProperty("full", binding.fullAxis());
            root.add(entry.getKey().key(), object);
        }
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            // 保存できなくても走れる方を優先する
        }
    }

    // ------------------------------------------------------------------
    // 読み取り
    // ------------------------------------------------------------------

    /**
     * 毎ティック呼ぶ。接続を探し、軸とボタンを読み込む。
     *
     * <p>ここで何が起きても運転は続けられるべきなので、例外は握りつぶして
     * 「コントローラ無し」に倒す。</p>
     */
    public static void poll() {
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

    private static void pollUnsafe() {
        load();
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
                if (i == AXIS_LEFT_TRIGGER || i == AXIS_RIGHT_TRIGGER) {
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
    private static boolean initSdl() {
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

    private static boolean stillConnected() {
        return gamepad != 0L ? SDLGamepad.SDL_GamepadConnected(gamepad)
                : rawJoystick != 0L && SDLJoystick.SDL_JoystickConnected(rawJoystick);
    }

    /** つながっている最初の機器を開く。ゲームパッドとして開けるならそちらを優先する。 */
    private static void open() {
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

    private static void close() {
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

    public static boolean isConnected() {
        return joystick >= 0;
    }

    /** つながっているコントローラの名前。無ければ null。 */
    public static String name() {
        if (gamepad != 0L) {
            return SDLGamepad.SDL_GetGamepadName(gamepad);
        }
        return rawJoystick != 0L ? SDLJoystick.SDL_GetJoystickName(rawJoystick) : null;
    }

    public static float[] axes() {
        return axes;
    }

    public static boolean[] buttons() {
        return buttons;
    }

    /** 割り当てを 1 つ読む。遊びを抜いた値。 */
    public static double value(ControllerAction action) {
        double raw = binding(action).read(axes, buttons);
        return applyDeadzone(raw);
    }

    /**
     * 中立付近の遊びを抜く。
     *
     * <p>単に切り捨てると、遊びの外へ出た瞬間に値が飛ぶ。残りの範囲へ伸ばし直して
     * <b>連続にする</b>こと。</p>
     */
    private static double applyDeadzone(double value) {
        double magnitude = Math.abs(value);
        if (magnitude < DEADZONE) {
            return 0.0;
        }
        double scaled = (magnitude - DEADZONE) / (1.0 - DEADZONE);
        return Math.copySign(Math.min(1.0, scaled), value);
    }

    /** 押した瞬間だけ true。変速に使う。 */
    public static boolean consumePress(ControllerAction action) {
        boolean down = value(action) > CarInput.AXIS_THRESHOLD;
        boolean was = Boolean.TRUE.equals(PRESSED.get(action));
        PRESSED.put(action, down);
        return down && !was;
    }

    /**
     * 運転入力を組み立てる。使える状態でなければ null。
     *
     * <p>ステアは正で右。アクセルとブレーキは 0..1。</p>
     */
    public static CarInput toCarInput() {
        if (!isEnabled() || !isConnected()) {
            return null;
        }
        double throttle = Math.abs(value(ControllerAction.THROTTLE));
        double brake = Math.abs(value(ControllerAction.BRAKE));
        double steer = value(ControllerAction.STEER);
        // 軸（ハンコンのサイドレバー）に割り当てれば引き量がそのまま効きになる。ボタンなら 0 か 1
        double handbrake = Math.abs(value(ControllerAction.HANDBRAKE));
        return CarInput.analog(throttle, brake, steer,
                consumePress(ControllerAction.SHIFT_UP),
                consumePress(ControllerAction.SHIFT_DOWN),
                handbrake);
    }

    /** 何も触っていないか。キーボードと併用するときの切り分けに使う。 */
    public static boolean isIdle() {
        for (ControllerAction action : ControllerAction.values()) {
            if (Math.abs(value(action)) > CarInput.AXIS_THRESHOLD) {
                return false;
            }
        }
        return true;
    }
}
