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
import net.minecraft.resources.Identifier;

/** この MOD が追加するキーバインド。 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class KurumaKeys {

    /**
     * 操作設定の画面での見出し。26.3 では見出しも登録するもの（{@code KeyMapping.Category}）になった。
     * 表示名は lang の {@code key.category.kurumamod.main}。3 つのローダーで同じ ID にしてある。
     */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(Kurumamod.MODID, "main"));

    /** 調整画面を開く。既定は G。 */
    public static final KeyMapping OPEN_TUNING = new KeyMapping(
            "key.kurumamod.tuning",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G,
            CATEGORY);

    /**
     * MOD メニューを開く。既定は H。
     *
     * <p>以前はテレメトリ表示のその場切替だったが、画面の好みは 1 か所へ集めた
     * （{@code KurumaMenuScreen}）。テレメトリの表示もその中の 1 項目。</p>
     */
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.kurumamod.menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_H,
            CATEGORY);

    /** カメラモードを切り替える（{@code CameraMode}）。既定は V。 */
    public static final KeyMapping TOGGLE_CAMERA = new KeyMapping(
            "key.kurumamod.camera",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_V,
            CATEGORY);

    /**
     * シフトアップ。既定は R。
     *
     * <p>マニュアルのときだけ効く。</p>
     */
    public static final KeyMapping SHIFT_UP = new KeyMapping(
            "key.kurumamod.shift_up",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R,
            CATEGORY);

    /**
     * シフトダウン。既定は F。
     *
     * <p><b>F はバニラの「手に持つ物を入れ替える」と重なる。</b>運転中しか使わないので
     * 実害は小さいが、気になるならどちらかを設定画面で変更する。</p>
     */
    public static final KeyMapping SHIFT_DOWN = new KeyMapping(
            "key.kurumamod.shift_down",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F,
            CATEGORY);

    /**
     * 追跡カメラを近づける。既定は PageUp。
     *
     * <p>運転中に何度も触る値なので、設定画面ではなくキーで刻む。
     * 変えた値はそのまま {@code kurumamod-client.toml} へ書かれる。</p>
     */
    public static final KeyMapping CAMERA_CLOSER = new KeyMapping(
            "key.kurumamod.camera_closer",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_PAGEUP,
            CATEGORY);

    /** 追跡カメラを遠ざける。既定は PageDown。 */
    public static final KeyMapping CAMERA_FARTHER = new KeyMapping(
            "key.kurumamod.camera_farther",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_PAGEDOWN,
            CATEGORY);

    /** ヘッドライトの切替。既定は L。 */
    public static final KeyMapping TOGGLE_HEADLIGHTS = new KeyMapping(
            "key.kurumamod.headlights",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_L,
            CATEGORY);

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
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_L,
            CATEGORY);

    /**
     * 座席の位置を合わせるデバッグ画面を開く。既定は Ctrl+G。
     * セッティング（G）と同じキーに修飾キーを付けてある（{@link #OPEN_HEADLIGHT_SETTINGS} と同じ）。
     */
    public static final KeyMapping OPEN_SEAT_DEBUG = new KeyMapping(
            "key.kurumamod.seat_debug",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G,
            CATEGORY);

    /**
     * 車の音（{@code sounds} の高さ・大きさ・エンジンの種類）を動かすデバッグ画面を開く。既定は Ctrl+H。
     * カーパックを作る人が、JSON を書き換えて F3+T で読み直すのを繰り返さずに決めるため。
     * MOD メニュー（H）と同じキーに修飾キーを付けてある（{@link #OPEN_HEADLIGHT_SETTINGS} と同じ）。
     * 以前の Ctrl+H（接地の記録）と Ctrl+J（地面の補間の切り替え）は消した。
     */
    public static final KeyMapping OPEN_SOUND_DEBUG = new KeyMapping(
            "key.kurumamod.sound_debug",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_H,
            CATEGORY);

    /** コントローラの割り当て画面を開く。既定は J。 */
    public static final KeyMapping OPEN_CONTROLLER = new KeyMapping(
            "key.kurumamod.controller",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_J,
            CATEGORY);

    /**
     * レース（参加・主催の入口）。既定は K。
     *
     * <p>誘いが出ていれば参加、参加していればそのレースの画面、主催していれば主催者画面、
     * レースが無ければ主催者画面（＝開く入口）。</p>
     */
    public static final KeyMapping OPEN_RACE = new KeyMapping(
            "key.kurumamod.race",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_K,
            CATEGORY);

    private KurumaKeys() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
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
        event.register(OPEN_SOUND_DEBUG);
        event.register(OPEN_RACE);
    }
}
