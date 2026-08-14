package com.jdmmc.kurumamod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.physics.CarInput;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * コントローラの読み取りと、割り当ての保存。
 *
 * <p>GLFW をそのまま叩く。{@code glfwGetGamepadState} は GLFW が持っている対応表で
 * 多くのコントローラを Xbox 系の並びへ正規化してくれるので、まずこれを試し、
 * 対応表に無い機器では生の軸とボタンへ落とす。</p>
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

    private static final Map<ControllerAction, ControllerBinding> BINDINGS =
            new EnumMap<>(ControllerAction.class);
    private static boolean loaded;

    /** 使うコントローラ。-1 なら未接続。 */
    private static int joystick = -1;
    private static boolean enabled = true;

    /** 使い回すゲームパッドの状態。解放してはいけないので手放さない。 */
    private static GLFWGamepadState gamepadState;

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
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
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
        } catch (RuntimeException e) {
            axes = new float[0];
            buttons = new boolean[0];
            joystick = -1;
        }
    }

    private static void pollUnsafe() {
        load();
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
    private static GLFWGamepadState gamepadState() {
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

    public static boolean isConnected() {
        return joystick >= 0;
    }

    /** つながっているコントローラの名前。無ければ null。 */
    public static String name() {
        return joystick < 0 ? null : GLFW.glfwGetJoystickName(joystick);
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
        boolean handbrake = value(ControllerAction.HANDBRAKE) > CarInput.AXIS_THRESHOLD;
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
