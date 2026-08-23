package com.jdmmc.kurumamod;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 見た目と操作に関する設定。<b>クライアントだけが持つ</b>ので {@code ModConfig.Type.CLIENT} で
 * 登録し、{@code config/kurumamod-client.toml} へ書く。
 *
 * <p>{@link Config}（COMMON）と分けてあるのは、こちらが<b>その人の画面の好み</b>だから。
 * サーバーに配る必要も、ワールドと一緒に保存する必要もない。</p>
 *
 * <p>項目を足すときは「spec 定数」「public static フィールド」「{@link #onLoad} での代入」の
 * 3 か所を揃える。あわせて {@code KurumaMenuScreen}（H キーのメニュー）にも 1 行足す。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SHOW_HELP = BUILDER
            .comment("運転中に操作ヘルプ（キーと機能の一覧）を画面右下へ出すかどうか")
            .define("showHelp", true);

    public static final ForgeConfigSpec.BooleanValue SHOW_TELEMETRY = BUILDER
            .comment("運転中に左下のテレメトリ（横G・ドリフト角・路面・タイヤの荷重）を出すかどうか。",
                    "セッティングを詰めるための数字なので、走るだけなら切ってよい")
            .define("showTelemetry", true);

    public static final ForgeConfigSpec.BooleanValue SHOW_GAUGES = BUILDER
            .comment("運転中に速度計と回転計（円形のメーター）を出すかどうか。",
                    "H で切り替わるテレメトリの数字とは別物で、こちらは常時出る")
            .define("showGauges", true);

    public static final ForgeConfigSpec.DoubleValue GAUGE_SCALE = BUILDER
            .comment("メーターの大きさの倍率。1.0 が既定。",
                    "画面の空きに収まるところまでは自動で縮むので、これはその上での好み")
            .defineInRange("gaugeScale", 1.0, 0.5, 2.0);

    public static final ForgeConfigSpec.DoubleValue GAUGE_OPACITY = BUILDER
            .comment("メーターの下敷き（文字盤と中央パネル）の濃さ。1.0 で不透明。",
                    "薄くすると後ろのチャットが透けて見える。目盛りと針は薄くならない")
            .defineInRange("gaugeOpacity", 0.6, 0.15, 1.0);

    public static final ForgeConfigSpec.DoubleValue CAMERA_DISTANCE = BUILDER
            .comment("追跡カメラの距離 [ブロック]。運転中は PageUp / PageDown でも刻める。",
                    "バニラの三人称は 4 固定だが、カメラの位置を自分で置いているので縛られない")
            .defineInRange("cameraDistance", 5.0, 1.5, 24.0);

    public static final ForgeConfigSpec.DoubleValue CAMERA_HEIGHT = BUILDER
            .comment("追跡カメラの高さ [ブロック]。0 で車と同じ高さ、上げると見下ろす")
            .defineInRange("cameraHeight", 1.2, 0.0, 8.0);

    public static final ForgeConfigSpec.DoubleValue CAMERA_SPEED_PULL = BUILDER
            .comment("最高速でさらに引く距離 [ブロック]。0 で常に一定。",
                    "速度感が出そうに見えるが逆で、引くほど景色の流れが遅くなるので既定は 0。",
                    "速さを見せるのは視野の広がり（speedFov）のほう")
            .defineInRange("cameraSpeedPull", 0.0, 0.0, 12.0);

    public static final ForgeConfigSpec.DoubleValue SPEED_FOV = BUILDER
            .comment("速度で視野をどれだけ広げるか [度]。0 で広げない。",
                    "この車が出せる速度（最高段でレブまで回した速度）に対する割合で決まるので、",
                    "遅い車でも全開なら同じだけ広がる")
            .defineInRange("speedFov", 25.0, 0.0, 50.0);

    public static final ForgeConfigSpec.EnumValue<GaugeTheme> GAUGE_THEME = BUILDER
            .comment("メーターの配色。CLASSIC=90 年代の純正メーター（黒い縁・白い数字・橙の針）、",
                    "MODERN=銀の縁に平らな文字盤と赤い針。形は同じで色だけ変わる")
            .defineEnum("gaugeTheme", GaugeTheme.CLASSIC);

    public static final ForgeConfigSpec.EnumValue<GateDisplay> GATE_DISPLAY = BUILDER
            .comment("コースのゲートの見せ方。FULL=面と柱、POSTS=左右のポールのみ、HIDDEN=描かない")
            .defineEnum("gateDisplay", GateDisplay.FULL);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /** 運転中に操作ヘルプを出すか。 */
    public static boolean showHelp = true;

    /** 左下のテレメトリを出すか。 */
    public static boolean showTelemetry = true;

    /** 速度計と回転計を出すか。 */
    public static boolean showGauges = true;

    /** メーターの大きさの倍率。 */
    public static double gaugeScale = 1.0;

    /** メーターの下敷きの濃さ。 */
    public static double gaugeOpacity = 0.6;

    /** 追跡カメラの距離 [ブロック]。 */
    public static double cameraDistance = 5.0;

    /** 追跡カメラの高さ [ブロック]。 */
    public static double cameraHeight = 1.2;

    /** 最高速でさらに引く距離 [ブロック]。 */
    public static double cameraSpeedPull = 0.0;

    /** 速度で視野をどれだけ広げるか [度]。 */
    public static double speedFov = 25.0;

    /** メーターの配色。 */
    public static GaugeTheme gaugeTheme = GaugeTheme.CLASSIC;

    /** コースのゲートの見せ方。 */
    public static GateDisplay gateDisplay = GateDisplay.FULL;

    private ClientConfig() {
    }

    /**
     * spec の値を static フィールドへ写す。
     *
     * <p><b>設定を書き換えた側が自分で呼ぶこと。</b>{@link ModConfigEvent} が飛んでくるのを
     * 当てにしてはいけない——ゲーム内から {@code SPEC.save()} でファイルを書いたときにこの
     * イベントを起こすのは <b>Forge のファイル監視スレッド</b>で、OS の通知が届いてからに
     * なるため、遅れるうえ届かないこともある。</p>
     *
     * <p>実際、以前の設定画面（Cloth Config で組んでいたもの）は spec へ書いて保存するだけ
     * だったので、<b>ゲートの表示を変えても再起動するまで反映されなかった</b>。
     * カメラの距離だけ効いていたのは、PageUp / PageDown の側が static フィールドも
     * 一緒に書いていたから。いまの {@code KurumaMenuScreen} は値を変えるたびに
     * これを呼んでいる。</p>
     */
    public static void refresh() {
        showHelp = SHOW_HELP.get();
        showTelemetry = SHOW_TELEMETRY.get();
        showGauges = SHOW_GAUGES.get();
        gaugeScale = GAUGE_SCALE.get();
        gaugeOpacity = GAUGE_OPACITY.get();
        cameraDistance = CAMERA_DISTANCE.get();
        cameraHeight = CAMERA_HEIGHT.get();
        cameraSpeedPull = CAMERA_SPEED_PULL.get();
        speedFov = SPEED_FOV.get();
        gaugeTheme = GAUGE_THEME.get();
        gateDisplay = GATE_DISPLAY.get();
    }

    /**
     * 値を読み直す。
     *
     * <p><b>自分の spec のイベントだけを拾うこと。</b>{@link ModConfigEvent} は COMMON の
     * 読み込みでも飛んでくるので、素通しにすると別の設定の読み込みでこちらまで
     * 読み直すことになる（今は害が無いが、値がずれる元になる）。</p>
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.CLIENT) {
            return;
        }
        refresh();
    }
}
