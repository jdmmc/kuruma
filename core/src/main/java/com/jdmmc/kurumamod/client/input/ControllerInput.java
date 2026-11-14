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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * <p><b>つながっている機器を全部読み、割り当てごとに機器を持つ</b>（{@link ControllerBinding#device}）。
 * ハンコン・ペダル・H シフター・サイドブレーキが別々の機器でも、それぞれの操作をそれぞれの機器から読む
 * （BeamNG や Assetto Corsa と同じ考え方）。</p>
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
     * 機器を読むもの。版が {@link #setDevice} で置く。<b>つながっている機器を全部読む。</b>
     *
     * <p>{@link #poll()} は<b>例外を投げないこと</b>（ネイティブが読めない環境でも運転は続けられるべきなので、
     * 機器の側で「つながっていない」に倒す）。</p>
     */
    public interface Device {
        /** 毎ティック呼ぶ。接続を探し、全部の機器の軸とボタンを読み込む。 */
        void poll();

        /** 読み取った機器。並びは OS が振った番号の順。つながっていなければ空。 */
        List<Reading> readings();
    }

    /**
     * 機器 1 台を読んだもの（版が作る）。
     *
     * @param guid    SDL 形式の GUID（16 進 32 文字。GLFW の {@code glfwGetJoystickGUID} も同じ形式）。読めなければ null
     * @param name    機器の名前
     * @param gamepad 対応表で Xbox 系の並びへ正規化したか（false なら生の軸とボタン）
     * @param wheel   ハンコンらしいか。<b>推測</b>で、割り当てたときの最初の値にしか使わない（画面で直せる）。
     *                SDL3 は機器の種類（{@code SDL_JOYSTICK_TYPE_WHEEL}）、GLFW は種類を返さないので対応表に無いか
     * @param nativeId 版が機器を引き直すための番号（SDL の ID・GLFW の joystick 番号）。フォースフィードバックで使う
     * @param axes    軸（正規化したなら GLFW のゲームパッドの並び）
     * @param buttons ボタン
     */
    public record Reading(String guid, String name, boolean gamepad, boolean wheel, int nativeId,
                          float[] axes, boolean[] buttons) {
    }

    /**
     * つながっている機器 1 台。
     *
     * @param id 割り当てに保存する名前（{@link #deviceKey} ＋ {@code #} ＋ 同じ機種の何台目か）
     */
    public record Pad(String id, String name, boolean gamepad, boolean wheel, int nativeId,
                      float[] axes, boolean[] buttons) {
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

    // フォースフィードバックの設定（ForceFeedback が読む）。強さの既定は控えめにしておく
    private static boolean feedbackEnabled = true;
    private static double feedbackStrength = 0.3;
    private static boolean feedbackInvert;

    /** 読み取った機器。割り当て画面でも使う */
    private static List<Pad> pads = List.of();

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

    /** フォースフィードバックを出すか。 */
    public static boolean feedbackEnabled() {
        load();
        return feedbackEnabled;
    }

    public static void setFeedbackEnabled(boolean value) {
        load();
        feedbackEnabled = value;
        save();
    }

    /** フォースフィードバックの強さ（0〜1。機器が出せる最大に対する割合の上限）。 */
    public static double feedbackStrength() {
        load();
        return feedbackStrength;
    }

    public static void setFeedbackStrength(double value) {
        load();
        feedbackStrength = clampStrength(value);
        save();
    }

    /** フォースフィードバックの向きを裏返すか（機器やドライバーで向きが逆に出るとき）。 */
    public static boolean feedbackInvert() {
        load();
        return feedbackInvert;
    }

    public static void setFeedbackInvert(boolean value) {
        load();
        feedbackInvert = value;
        save();
    }

    private static double clampStrength(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.3;
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
            if (root.has("force_feedback")) {
                JsonObject ffb = root.getAsJsonObject("force_feedback");
                if (ffb.has("enabled")) {
                    feedbackEnabled = ffb.get("enabled").getAsBoolean();
                }
                if (ffb.has("strength")) {
                    feedbackStrength = clampStrength(ffb.get("strength").getAsDouble());
                }
                if (ffb.has("invert")) {
                    feedbackInvert = ffb.get("invert").getAsBoolean();
                }
            }
            if (root.has("wheel_rotation")) {
                wheelRotation = clampRotation(root.get("wheel_rotation").getAsInt());
            }
            for (ControllerAction action : ControllerAction.values()) {
                if (!root.has(action.key())) {
                    continue;
                }
                JsonObject entry = root.getAsJsonObject(action.key());
                // device が無いのは機器を覚える前の割り当て。最初の機器から読み、遊びもその頃の一律の値
                BINDINGS.put(action, new ControllerBinding(
                        ControllerBinding.Type.valueOf(entry.get("type").getAsString()),
                        entry.get("index").getAsInt(),
                        entry.has("invert") && entry.get("invert").getAsBoolean(),
                        entry.has("full") && entry.get("full").getAsBoolean(),
                        string(entry, "device"),
                        string(entry, "device_name"),
                        entry.has("deadzone") ? entry.get("deadzone").getAsDouble()
                                : ControllerBinding.GAMEPAD_DEADZONE,
                        entry.has("wheel") && entry.get("wheel").getAsBoolean()));
            }
        } catch (IOException | RuntimeException e) {
            // 壊れていても運転はできるべきなので、既定の割り当てのまま進む
        }
    }

    private static String string(JsonObject entry, String key) {
        return entry.has(key) && !entry.get(key).isJsonNull() ? entry.get(key).getAsString() : null;
    }

    private static void save() {
        if (configDir == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        JsonObject ffb = new JsonObject();
        ffb.addProperty("enabled", feedbackEnabled);
        ffb.addProperty("strength", feedbackStrength);
        ffb.addProperty("invert", feedbackInvert);
        root.add("force_feedback", ffb);
        root.addProperty("wheel_rotation", wheelRotation);
        for (Map.Entry<ControllerAction, ControllerBinding> entry : BINDINGS.entrySet()) {
            ControllerBinding binding = entry.getValue();
            JsonObject object = new JsonObject();
            object.addProperty("type", binding.type().name());
            object.addProperty("index", binding.index());
            object.addProperty("invert", binding.invert());
            object.addProperty("full", binding.fullAxis());
            if (binding.device() != null) {
                object.addProperty("device", binding.device());
                object.addProperty("device_name", binding.deviceName());
            }
            object.addProperty("deadzone", binding.deadzone());
            object.addProperty("wheel", binding.wheel());
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
     * 毎ティック呼ぶ。接続を探し、全部の機器の軸とボタンを読み込む（{@link Device#poll}）。
     *
     * <p>ここで何が起きても運転は続けられるべきなので、例外は握りつぶして
     * 「コントローラ無し」に倒す。</p>
     */
    public static void poll() {
        load();
        try {
            if (device != null) {
                device.poll();
                pads = identify(device.readings());
                return;
            }
        } catch (RuntimeException | LinkageError e) {
            // 機器の側で倒しきれなかったとき
        }
        pads = List.of();
    }

    /** 読み取ったものに id を振る。同じ機種が 2 台あれば、OS の番号の順に {@code #0}・{@code #1}。 */
    private static List<Pad> identify(List<Reading> readings) {
        if (readings == null || readings.isEmpty()) {
            return List.of();
        }
        List<Pad> result = new ArrayList<>(readings.size());
        Map<String, Integer> seen = new HashMap<>();
        for (Reading reading : readings) {
            String key = deviceKey(reading.guid(), reading.name());
            int nth = seen.merge(key, 1, Integer::sum) - 1;
            result.add(new Pad(key + "#" + nth, reading.name() == null ? key : reading.name(),
                    reading.gamepad(), reading.wheel(), reading.nativeId(), reading.axes(), reading.buttons()));
        }
        return List.copyOf(result);
    }

    /**
     * 機器の id の頭。<b>GUID から USB の vendor と product を取り出して {@code vvvv:pppp} にする。</b>
     *
     * <p>GUID をそのまま使わないのは、SDL3（26.3）は GUID の 3〜4 バイト目に名前の CRC を入れ、
     * GLFW（1.20.1・1.21.1）は 0 にしているため。同じ機器でも版で GUID が変わり、保存した割り当てが
     * 版をまたいで合わなくなる。vendor（5〜6 バイト目）と product（9〜10 バイト目）はどちらも同じ位置に
     * リトルエンディアンで入っている。vendor が 0 の機器（USB の ID を持たない）だけ GUID のまま使う。</p>
     */
    static String deviceKey(String guid, String name) {
        if (guid != null && guid.length() >= 20) {
            String g = guid.toLowerCase(Locale.ROOT);
            String vendor = g.substring(10, 12) + g.substring(8, 10);
            String product = g.substring(18, 20) + g.substring(16, 18);
            if (!vendor.equals("0000")) {
                return vendor + ":" + product;
            }
            return g;
        }
        return name == null ? "unknown" : name;
    }

    public static boolean isConnected() {
        return !pads.isEmpty();
    }

    /** つながっている機器。 */
    public static List<Pad> pads() {
        return pads;
    }

    /**
     * 割り当ての機器。つながっていなければ null。
     *
     * <p>id で探し、見つからなければ<b>割り当てたときの名前</b>で探す（GUID の取れ方が環境で変わったときの
     * 手がかり）。機器を覚えていない割り当て（既定・古い保存）は最初の機器。</p>
     */
    public static Pad padOf(ControllerBinding binding) {
        if (pads.isEmpty() || !binding.isBound()) {
            return null;
        }
        if (binding.device() == null) {
            return pads.get(0);
        }
        for (Pad pad : pads) {
            if (pad.id().equals(binding.device())) {
                return pad;
            }
        }
        if (binding.deviceName() != null) {
            for (Pad pad : pads) {
                if (pad.name().equals(binding.deviceName())) {
                    return pad;
                }
            }
        }
        return null;
    }

    /** 割り当てを 1 つ読む。遊びを抜いた値。機器がつながっていなければ 0。 */
    public static double value(ControllerAction action) {
        ControllerBinding binding = binding(action);
        Pad pad = padOf(binding);
        if (pad == null) {
            return 0.0;
        }
        return applyDeadzone(binding.read(pad.axes(), pad.buttons()), binding.deadzone());
    }

    /**
     * 中立付近の遊びを抜く。
     *
     * <p>単に切り捨てると、遊びの外へ出た瞬間に値が飛ぶ。残りの範囲へ伸ばし直して
     * <b>連続にする</b>こと。</p>
     */
    private static double applyDeadzone(double value, double deadzone) {
        if (deadzone <= 0.0) {
            return Math.max(-1.0, Math.min(1.0, value));
        }
        double magnitude = Math.abs(value);
        if (magnitude < deadzone) {
            return 0.0;
        }
        double scaled = (magnitude - deadzone) / (1.0 - deadzone);
        return Math.copySign(Math.min(1.0, scaled), value);
    }

    /**
     * H シフターで選んでいる段。
     *
     * <p>段のボタンのどれかが押されていればその段（バックは -1）、<b>どれも押されていなければニュートラル（0）</b>。
     * 段を 1 つも割り当てていないか、割り当てた機器がつながっていなければ {@link CarInput#NO_GEAR_SELECT}
     * （シフトアップ・ダウンで変速する）。</p>
     */
    public static int selectedGear() {
        if (!isEnabled() || pads.isEmpty()) {
            return CarInput.NO_GEAR_SELECT;
        }
        boolean shifter = false;
        for (ControllerAction action : ControllerAction.values()) {
            if (!action.isGear() || padOf(binding(action)) == null) {
                continue;
            }
            shifter = true;
            if (value(action) > 0.5) {
                return action.gear();
            }
        }
        return shifter ? 0 : CarInput.NO_GEAR_SELECT;
    }

    /**
     * クラッチペダルの踏み込み量 0..1（1 で切れきる）。
     *
     * <p>割り当てていないか、割り当てた機器がつながっていなければ {@link CarInput#NO_CLUTCH}（クラッチは自動）。
     * H シフターの段と同じく、{@code DriverInput} がキーボードの入力にも載せる——ペダルから足を離しただけで
     * キーボードの入力へ切り替わり、クラッチが自動へ戻ってしまわないように。</p>
     */
    public static double clutchPedal() {
        if (!isEnabled() || padOf(binding(ControllerAction.CLUTCH)) == null) {
            return CarInput.NO_CLUTCH;
        }
        return Math.min(1.0, Math.abs(value(ControllerAction.CLUTCH)));
    }

    /** 押した瞬間だけ true。変速に使う。 */
    public static boolean consumePress(ControllerAction action) {
        boolean down = value(action) > CarInput.AXIS_THRESHOLD;
        boolean was = Boolean.TRUE.equals(PRESSED.get(action));
        PRESSED.put(action, down);
        return down && !was;
    }

    /**
     * 運転入力を組み立てる。使える状態でなければ null。H シフターの段は載せない（{@code DriverInput} が載せる）。
     *
     * <p>ステアは正で右。アクセルとブレーキは 0..1。</p>
     */
    public static CarInput toCarInput() {
        if (!isEnabled() || !isConnected()) {
            return null;
        }
        double throttle = Math.abs(value(ControllerAction.THROTTLE));
        double brake = Math.abs(value(ControllerAction.BRAKE));
        boolean direct = isDirectSteer();
        // 直結は手元のハンドルの角度を 1:1 で車のハンドルの角度にする（軸の値のままだと回転角の設定で比がずれる）
        double steer = direct ? handwheelDegrees() / CAR_LOCK_DEGREES : value(ControllerAction.STEER);
        // 軸（ハンコンのサイドレバー）に割り当てれば引き量がそのまま効きになる。ボタンなら 0 か 1
        double handbrake = Math.abs(value(ControllerAction.HANDBRAKE));
        return CarInput.analog(throttle, brake, steer,
                consumePress(ControllerAction.SHIFT_UP),
                consumePress(ControllerAction.SHIFT_DOWN),
                handbrake).withDirectSteer(direct);
    }

    /**
     * ハンドルをハンコンの直結で扱うか。ハンドルが軸に割り当ててあり、「ハンコン」になっているとき。
     * ボタンに割り当てたハンドルは位置を持たないので直結にしない。
     */
    public static boolean isDirectSteer() {
        ControllerBinding binding = binding(ControllerAction.STEER);
        return binding.type() == ControllerBinding.Type.AXIS && binding.wheel();
    }

    // ------------------------------------------------------------------
    // ハンコンの回転角
    // ------------------------------------------------------------------

    /**
     * 車のハンドルが目いっぱい切れたときの角度 [度]（片側）。車内のハンドルの見た目
     * （{@code CarRig#HANDWHEEL_LOCK}）と同じ値にしてある——手元・画面・物理の 3 つが同じ角度になるように。
     */
    public static final double CAR_LOCK_DEGREES = Math.toDegrees(com.jdmmc.kurumamod.client.model.CarRig.HANDWHEEL_LOCK);
    public static final int MIN_WHEEL_ROTATION = 180;
    public static final int MAX_WHEEL_ROTATION = 2520;
    public static final int WHEEL_ROTATION_STEP = 90;
    private static final int DEFAULT_WHEEL_ROTATION = 900;

    private static int wheelRotation = DEFAULT_WHEEL_ROTATION;

    /**
     * ハンコンの回転角 [度]（ロック・トゥ・ロック）。<b>ドライバー（G HUB・Fanatec の設定など）と同じ値を入れてもらう。</b>
     * 軸の端がここで言う角度の半分にあたる。SDL からも GLFW からも読めないので、ユーザーが決める。
     */
    public static int wheelRotation() {
        load();
        return wheelRotation;
    }

    public static void setWheelRotation(int degrees) {
        load();
        wheelRotation = clampRotation(degrees);
        save();
    }

    private static int clampRotation(int degrees) {
        return Math.max(MIN_WHEEL_ROTATION, Math.min(MAX_WHEEL_ROTATION, degrees));
    }

    /** 手元のハンドルの角度 [度]。正で右。軸の値 × 回転角の半分。 */
    public static double handwheelDegrees() {
        return value(ControllerAction.STEER) * wheelRotation() / 2.0;
    }

    /**
     * 車のロックより先へ回している量 [度]（正で右へ越えている）。越えていなければ 0。
     * フォースフィードバックのソフトロック（壁）に使う。直結でなければ 0。
     */
    public static double beyondLockDegrees() {
        if (!isDirectSteer()) {
            return 0.0;
        }
        double degrees = handwheelDegrees();
        double over = Math.abs(degrees) - CAR_LOCK_DEGREES;
        return over > 0.0 ? Math.copySign(over, degrees) : 0.0;
    }

    /**
     * 何も触っていないか。キーボードと併用するときの切り分けに使う。
     *
     * <p><b>H シフターの段は見ない。</b>段に入っている間はボタンが押しっぱなしなので、見るとキーボードへ
     * 戻れなくなる。段は {@link #selectedGear} を {@code DriverInput} がキーボードの入力にも載せる。</p>
     */
    public static boolean isIdle() {
        for (ControllerAction action : ControllerAction.values()) {
            if (action.isGear()) {
                continue;
            }
            if (Math.abs(value(action)) > CarInput.AXIS_THRESHOLD) {
                return false;
            }
        }
        return true;
    }
}
