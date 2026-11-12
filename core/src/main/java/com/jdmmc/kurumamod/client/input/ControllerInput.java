package com.jdmmc.kurumamod.client.input;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.physics.CarInput;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * コントローラの読み取りと、割り当ての保存。
 *
 * <p><b>機器を読むのは版の {@code ControllerDevice}</b>（1.20.1・1.21.1 は GLFW、26.3 は SDL3）。
 * どちらも、対応表のある機器は Xbox 系の並びへ正規化し、無い機器は生の軸とボタンへ落とす。
 * <b>軸とボタンの番号は GLFW のゲームパッドの並び</b>（{@link #AXIS_LEFT_X}・{@link #BUTTON_A} など）で、
 * 値の範囲も GLFW と同じ（スティック −1〜1、トリガーは離して −1・引ききって 1）。保存してある割り当て
 * （{@code index}）をどの版でもそのまま使えるように、26.3 の側が SDL の番号から並べ替える。</p>
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
     * 機器を読むもの。版が {@link #setDevice} で置く。
     *
     * <p>{@link #poll()} は<b>例外を投げないこと</b>（ネイティブが読めない環境でも運転は続けられるべきなので、
     * 機器の側で「つながっていない」に倒す）。</p>
     */
    public interface Device {
        /** 毎ティック呼ぶ。接続を探し、軸とボタンを読み込む。 */
        void poll();

        /** 読み取った軸（番号は GLFW の並び）。つながっていなければ空。 */
        float[] axes();

        boolean[] buttons();

        boolean connected();

        /** つながっているコントローラの名前。無ければ null。 */
        String name();
    }

    private static Device device;
    private static Path configDir;

    /** 機器の読み方と、割り当てを置く {@code config/} を決める。クライアントの起動時に 1 回。 */
    public static void setDevice(Device reader, Path dir) {
        device = reader;
        configDir = dir;
    }

    private static final Map<ControllerAction, ControllerBinding> BINDINGS =
            new EnumMap<>(ControllerAction.class);
    private static boolean loaded;

    private static boolean enabled = true;

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
        return configDir.resolve(FILE);
    }

    private static void load() {
        if (loaded || configDir == null) {
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
        if (configDir == null) {
            return;
        }
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
     * 毎ティック呼ぶ。接続を探し、軸とボタンを読み込む（{@link Device#poll}）。
     *
     * <p>ここで何が起きても運転は続けられるべきなので、例外は握りつぶして
     * 「コントローラ無し」に倒す。</p>
     */
    public static void poll() {
        load();
        try {
            if (device != null) {
                device.poll();
                axes = device.axes();
                buttons = device.buttons();
                return;
            }
        } catch (RuntimeException | LinkageError e) {
            // 機器の側で倒しきれなかったとき
        }
        axes = new float[0];
        buttons = new boolean[0];
    }


    public static boolean isConnected() {
        return device != null && device.connected() && axes.length + buttons.length > 0;
    }

    /** つながっているコントローラの名前。無ければ null。 */
    public static String name() {
        return device == null ? null : device.name();
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
