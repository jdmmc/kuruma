package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/** この MOD が追加するキーバインド。 */
@EventBusSubscriber(modid = Kurumamod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class KurumaKeys {

    /** 調整画面を開く。既定は G。 */
    public static final KeyMapping OPEN_TUNING = new KeyMapping(
            "key.kurumamod.tuning",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.kurumamod");

    /**
     * MOD メニューを開く。既定は H。
     *
     * <p>以前はテレメトリ表示のその場切替だったが、画面の好みは 1 か所へ集めた
     * （{@code KurumaMenuScreen}）。テレメトリの表示もその中の 1 項目。</p>
     */
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.kurumamod.menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.kurumamod");

    /** カメラモードを切り替える（{@code CameraMode}）。既定は V。 */
    public static final KeyMapping TOGGLE_CAMERA = new KeyMapping(
            "key.kurumamod.camera",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.kurumamod");

    /**
     * シフトアップ。既定は R。
     *
     * <p>マニュアルのときだけ効く。</p>
     */
    public static final KeyMapping SHIFT_UP = new KeyMapping(
            "key.kurumamod.shift_up",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.kurumamod");

    /**
     * シフトダウン。既定は F。
     *
     * <p><b>F はバニラの「手に持つ物を入れ替える」と重なる。</b>運転中しか使わないので
     * 実害は小さいが、気になるならどちらかを設定画面で変更する。</p>
     */
    public static final KeyMapping SHIFT_DOWN = new KeyMapping(
            "key.kurumamod.shift_down",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            "key.categories.kurumamod");

    /**
     * 追跡カメラを近づける。既定は PageUp。
     *
     * <p>運転中に何度も触る値なので、設定画面ではなくキーで刻む。
     * 変えた値はそのまま {@code kurumamod-client.toml} へ書かれる。</p>
     */
    public static final KeyMapping CAMERA_CLOSER = new KeyMapping(
            "key.kurumamod.camera_closer",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_UP,
            "key.categories.kurumamod");

    /** 追跡カメラを遠ざける。既定は PageDown。 */
    public static final KeyMapping CAMERA_FARTHER = new KeyMapping(
            "key.kurumamod.camera_farther",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_DOWN,
            "key.categories.kurumamod");

    /** ヘッドライトの切替。既定は L。 */
    public static final KeyMapping TOGGLE_HEADLIGHTS = new KeyMapping(
            "key.kurumamod.headlights",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.kurumamod");

    /**
     * ヘッドライトの調整画面を開く。既定は Ctrl+L。
     *
     * <p>点灯（L）と同じキーに修飾キーを付けてある。Forge は修飾キーが押されていれば
     * 修飾付きの割り当てを先に探し、見つかればそちらだけを動かすので、Ctrl+L で
     * ライトが切り替わることはない。</p>
     */
    public static final KeyMapping OPEN_HEADLIGHT_SETTINGS = new KeyMapping(
            "key.kurumamod.headlight_settings",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.kurumamod");

    /**
     * 座席の位置を合わせるデバッグ画面を開く。既定は Ctrl+G。
     * セッティング（G）と同じキーに修飾キーを付けてある（{@link #OPEN_HEADLIGHT_SETTINGS} と同じ）。
     */
    public static final KeyMapping OPEN_SEAT_DEBUG = new KeyMapping(
            "key.kurumamod.seat_debug",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.kurumamod");

    /** コントローラの割り当て画面を開く。既定は J。 */
    public static final KeyMapping OPEN_CONTROLLER = new KeyMapping(
            "key.kurumamod.controller",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.kurumamod");

    private KurumaKeys() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TUNING);
        event.register(OPEN_MENU);
        event.register(TOGGLE_CAMERA);
        event.register(SHIFT_UP);
        event.register(SHIFT_DOWN);
        event.register(TOGGLE_HEADLIGHTS);
        event.register(OPEN_HEADLIGHT_SETTINGS);
        event.register(OPEN_SEAT_DEBUG);
        event.register(CAMERA_CLOSER);
        event.register(CAMERA_FARTHER);
        event.register(OPEN_CONTROLLER);
    }
}
