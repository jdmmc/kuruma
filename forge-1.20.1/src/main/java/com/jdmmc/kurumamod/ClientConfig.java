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

    public static final ForgeConfigSpec.DoubleValue COCKPIT_LOOK_AHEAD = BUILDER
            .comment("一人称で、グリップで曲がるときにどれだけ先の進路を見るか [秒]。0 で先読みしない。",
                    "ドリフト中（スリップ角 3〜10 度以上）は効かない（CockpitLook）")
            .defineInRange("cockpitLookAhead", 0.0, 0.0, 1.6);

    public static final ForgeConfigSpec.DoubleValue COCKPIT_DRIFT_LOOK = BUILDER
            .comment("一人称で、横滑りしているときに進行方向をどれだけ見るか。0 で車の正面、1 で進行方向そのもの")
            .defineInRange("cockpitDriftLook", 0.7, 0.0, 1.0);

    public static final ForgeConfigSpec.EnumValue<GaugeTheme> GAUGE_THEME = BUILDER
            .comment("メーターの配色。CLASSIC=90 年代の純正メーター（黒い縁・白い数字・橙の針）、",
                    "MODERN=銀の縁に平らな文字盤と赤い針。形は同じで色だけ変わる")
            .defineEnum("gaugeTheme", GaugeTheme.CLASSIC);

    public static final ForgeConfigSpec.BooleanValue LIVE_MIRRORS = BUILDER
            .comment("ルームミラー・ドアミラーに実際の後方の景色を映すかどうか。",
                    "映すには世界をもう 1 回描くことになるので、そのぶん重くなる（フレームあたり 1 枚ずつ",
                    "順繰りに更新するので、ミラーが何枚あっても描き直しは 1 回）。",
                    "車のモデルが mirror_* のオブジェクトを持っていないと何も起きない。",
                    "描画設定「最高」とシェーダーパックのときは自動で切れる")
            .define("liveMirrors", false);

    public static final ForgeConfigSpec.BooleanValue GPU_CAR_MESHES = BUILDER
            .comment("車体・ホイール・タイヤを GPU に置いたまま描くかどうか（軽くなる）。",
                    "切ると、毎フレーム頂点を送り直す以前の描き方になる。見た目は同じ。",
                    "シェーダーパックを使っている間は、この設定に関係なく以前の描き方になる")
            .define("gpuCarMeshes", true);

    public static final ForgeConfigSpec.BooleanValue TIME_ATTACK_GHOST = BUILDER
            .comment("タイムアタックで、そのコースの自己ベストのゴーストを一緒に走らせるかどうか。",
                    "ゴーストは周回ごとに自動で記録され、速い周が出たら置き換わる（自分のパソコンにだけ保存）")
            .define("timeAttackGhost", true);

    public static final ForgeConfigSpec.BooleanValue SHOW_DUST = BUILDER
            .comment("タイヤの煙と土煙を出すかどうか。",
                    "舗装路で滑ったときの白煙と、未舗装路（土・砂・砂利・雪）で舞い上げる土煙の両方")
            .define("showDust", true);

    public static final ForgeConfigSpec.DoubleValue DUST_DENSITY = BUILDER
            .comment("タイヤの煙と土煙の量の倍率。1.0 が既定。粒の数がそのまま増えるので、重いと感じたら下げる。",
                    "0 は無い——消すのは showDust の役目。入り口が 2 つあると、",
                    "どちらが効いているのか分からなくなる")
            .defineInRange("dustDensity", 1.0, 0.25, 2.0);

    public static final ForgeConfigSpec.BooleanValue SHOW_OWN_SKID_MARKS = BUILDER
            .comment("自分が運転している車が舗装路で滑ったときに、タイヤ痕を残すかどうか")
            .define("showOwnSkidMarks", true);

    public static final ForgeConfigSpec.BooleanValue SHOW_OTHER_SKID_MARKS = BUILDER
            .comment("他の人の車（無人の車や、自分が助手席に乗っている車を含む）のタイヤ痕を描くかどうか")
            .define("showOtherSkidMarks", true);

    public static final ForgeConfigSpec.DoubleValue SKID_MARK_SECONDS = BUILDER
            .comment("タイヤ痕が消えるまでの秒数。最後の 4 割で薄れていく。",
                    "長くするほど同時に残る跡が増えて重くなる")
            .defineInRange("skidMarkSeconds", 5.0, 1.0, 300.0);

    public static final ForgeConfigSpec.EnumValue<CameraMode> FIRST_PERSON_CAMERA = BUILDER
            .comment("一人称のカメラモード（V で切り替え）。COCKPIT_FOLLOW=追従（車体と一緒に揺れる）、",
                    "COCKPIT_LEVEL=水平維持（視線は水平を保つ）、COCKPIT_FREE=自由（車体と一緒に揺れ、視線は追従しない）")
            .defineEnum("firstPersonCamera", CameraMode.COCKPIT_FOLLOW,
                    CameraMode.COCKPIT_FOLLOW, CameraMode.COCKPIT_LEVEL, CameraMode.COCKPIT_FREE);

    public static final ForgeConfigSpec.EnumValue<CameraMode> THIRD_PERSON_CAMERA = BUILDER
            .comment("三人称のカメラモード（V で切り替え）。FOLLOW=追従、HEADING=車体の向きに固定、FREE=自由")
            .defineEnum("thirdPersonCamera", CameraMode.FOLLOW,
                    CameraMode.FOLLOW, CameraMode.HEADING, CameraMode.FREE);

    public static final ForgeConfigSpec.EnumValue<GateDisplay> GATE_DISPLAY = BUILDER
            .comment("コースのゲートの見せ方。FULL=面と柱、POSTS=左右のポールのみ、HIDDEN=描かない")
            .defineEnum("gateDisplay", GateDisplay.FULL);

    // ---- ヘッドライト（L で点灯、Ctrl+L で調整画面） ----

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_AIM = BUILDER
            .comment("ヘッドライトの光軸の下向き [度]。大きいほど手前を照らす。実車のロービームは 0.6 度前後")
            .defineInRange("headlightAim", 0.0, 0.0, 4.0);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_LEVELING = BUILDER
            .comment("オートレベライザーの効き。ブレーキや加速でサスが沈んだぶんの傾きを、どれだけ光軸で打ち消すか。",
                    "0 で打ち消さない（ブレーキで光が下を向く）、1 で全部打ち消す。坂道の傾きは打ち消さない")
            .defineInRange("headlightLeveling", 0.85, 0.0, 1.0);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_FOG = BUILDER
            .comment("晴れているときの霧の濃さ [1/m]。濃いほど光線がはっきり見えるが奥まで届かない。雨と雷ではこれに足される")
            .defineInRange("headlightFog", 0.025, 0.0, 0.15);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_BRIGHTNESS = BUILDER
            .comment("ヘッドライトの明るさ（路面を照らす光とビームの両方に掛かる倍率）。1.0 で既定")
            .defineInRange("headlightBrightness", 1.0, 0.0, 2.5);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_SMOKE = BUILDER
            .comment("光の中のタイヤの煙・土煙の光り方")
            .defineInRange("headlightSmoke", 4.0, 0.0, 10.0);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_CARS = BUILDER
            .comment("路面を照らす光と霧の中の光線を描く車の台数（近い順・128 ブロック以内）。多いほど重い。1 台 = ヘッドライト 2 灯。",
                    "11 で無制限（天井は GPU のテクスチャの大きさで、普通は 64 台）")
            .defineInRange("headlightCars", 11.0, 1.0, 11.0);

    // シェーダーパック使用中だけ効く強さ（仮置き。値を詰めたら定数へ戻すかもしれない）
    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_PACK_CONTRAST = BUILDER
            .comment("シェーダーパック使用中: 照らされた所のコントラストの上げ幅（明るさだけ。色の比は変えない）")
            .defineInRange("headlightPackContrast", 0.0, 0.0, 1.5);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_PACK_TINT = BUILDER
            .comment("シェーダーパック使用中: 月明かりの色かぶり（画面の平均色）を照らされた所から取る強さ")
            .defineInRange("headlightPackTint", 0.0, 0.0, 1.0);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_PACK_ADD = BUILDER
            .comment("シェーダーパック使用中: 照らされた所に光の色をそのまま足す量（0 で足さない）")
            .defineInRange("headlightPackAdd", 0.0, 0.0, 1.0);

    public static final ForgeConfigSpec.BooleanValue HEADLIGHT_PACK_PRE_COMPOSITE = BUILDER
            .comment("シェーダーパック使用中: 路面を照らす光を、パックの仕上げ（霧・トーンマップ）の前に入れるか。",
                    "入れるとブロックの色が残る。パックによっては仕上げの前に照らされた絵が無く光が見えなくなる（Bliss など）ので、そのときは切る",
                    "（切ると仕上がった画面に重ねる。色は褪せるが、どのパックでも見える）")
            .define("headlightPackPreComposite", true);

    public static final ForgeConfigSpec.EnumValue<HeadlightColor> HEADLIGHT_COLOR = BUILDER
            .comment("ヘッドライトが照らす光の色（路面・光線・光芒）")
            .defineEnum("headlightColor", HeadlightColor.HALOGEN);

    public static final ForgeConfigSpec.BooleanValue HEADLIGHT_WITH_SHADER_PACKS = BUILDER
            .comment("シェーダーパック（Oculus / Iris / OptiFine）を使っている間も、ヘッドライトの光（路面の照り返し・光線・光芒）を描くか。",
                    "パックによっては合成が崩れるので、そのときは切る（レンズが光るだけになる）")
            .define("headlightWithShaderPacks", true);

    public static final ForgeConfigSpec.DoubleValue HEADLIGHT_RANGE = BUILDER
            .comment("光の届く距離 [m]。これより遠い路面・光線・影は計算しない（手前 30% でなめらかに消す）。",
                    "短いほど軽い。光は距離の 2 乗で弱まるので、遠くはもともと暗い")
            .defineInRange("headlightRange", 150.0, 16.0, 192.0);

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

    /** 一人称の先読み [秒]。 */
    public static double cockpitLookAhead = 0.0;

    /** 一人称で、横滑り中に進行方向を見る割合。 */
    public static double cockpitDriftLook = 0.7;

    /** メーターの配色。 */
    public static GaugeTheme gaugeTheme = GaugeTheme.CLASSIC;

    /** ミラーに実際の景色を映すか。 */
    public static boolean liveMirrors = false;

    /** 車のメッシュを GPU に置いたまま描くか（{@code CarMeshes}）。 */
    public static boolean gpuCarMeshes = true;

    /** タイムアタックで自己ベストのゴーストを走らせるか。 */
    public static boolean timeAttackGhost = true;

    /** タイヤの煙と土煙を出すか。キーの名前（showDust）は保存済みの設定のために据え置いている。 */
    public static boolean showDust = true;

    /** タイヤの煙と土煙の量の倍率。消すのは {@link #showDust} の役目なので 0 にはならない。 */
    public static double dustDensity = 1.0;

    /** 自分が運転している車のタイヤ痕を残すか。 */
    public static boolean showOwnSkidMarks = true;

    /** 他の人の車のタイヤ痕を描くか。 */
    public static boolean showOtherSkidMarks = true;

    /** タイヤ痕が消えるまでの秒数。 */
    public static double skidMarkSeconds = 5.0;

    /** コースのゲートの見せ方。 */
    public static GateDisplay gateDisplay = GateDisplay.FULL;

    /** 一人称のカメラモード。 */
    public static CameraMode firstPersonCamera = CameraMode.COCKPIT_FOLLOW;
    /** 三人称のカメラモード。 */
    public static CameraMode thirdPersonCamera = CameraMode.FOLLOW;

    /** ヘッドライトの光軸の下向き [度]。 */
    public static double headlightAim = 0.0;
    /** オートレベライザーの効き 0..1。 */
    public static double headlightLeveling = 0.85;
    /** 晴れのときの霧の濃さ [1/m]。 */
    public static double headlightFog = 0.025;
    /** ヘッドライトの明るさ（路面とビームに掛かる倍率）。 */
    public static double headlightBrightness = 1.0;
    /** 光の中の煙の光り方。 */
    public static double headlightSmoke = 4.0;
    /** 画素ごとの光を描く車の台数。{@link #UNLIMITED_HEADLIGHT_CARS} 以上で無制限。 */
    public static int headlightCars = 11;
    /** 光の届く距離 [m]。 */
    public static double headlightRange = 150.0;
    /** シェーダーパックを使っている間も光を描くか。 */
    public static boolean headlightWithShaderPacks = true;
    /** シェーダーパック使用中の強さ。 */
    public static double headlightPackContrast = 0.0;
    public static double headlightPackTint = 0.0;
    public static double headlightPackAdd = 0.0;
    /** シェーダーパックの仕上げの前に路面の光を入れるか。 */
    public static boolean headlightPackPreComposite = true;
    /** 照らす光の色。 */
    public static HeadlightColor headlightColor = HeadlightColor.HALOGEN;
    /** 「光を描く台数」のこの値は無制限を意味する（スライダーの右端）。 */
    public static final int UNLIMITED_HEADLIGHT_CARS = 11;

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
        cockpitLookAhead = COCKPIT_LOOK_AHEAD.get();
        cockpitDriftLook = COCKPIT_DRIFT_LOOK.get();
        gaugeTheme = GAUGE_THEME.get();
        liveMirrors = LIVE_MIRRORS.get();
        gpuCarMeshes = GPU_CAR_MESHES.get();
        timeAttackGhost = TIME_ATTACK_GHOST.get();
        showDust = SHOW_DUST.get();
        dustDensity = DUST_DENSITY.get();
        showOwnSkidMarks = SHOW_OWN_SKID_MARKS.get();
        showOtherSkidMarks = SHOW_OTHER_SKID_MARKS.get();
        skidMarkSeconds = SKID_MARK_SECONDS.get();
        gateDisplay = GATE_DISPLAY.get();
        firstPersonCamera = FIRST_PERSON_CAMERA.get();
        thirdPersonCamera = THIRD_PERSON_CAMERA.get();
        headlightAim = HEADLIGHT_AIM.get();
        headlightLeveling = HEADLIGHT_LEVELING.get();
        headlightFog = HEADLIGHT_FOG.get();
        headlightBrightness = HEADLIGHT_BRIGHTNESS.get();
        headlightSmoke = HEADLIGHT_SMOKE.get();
        headlightCars = (int) Math.round(HEADLIGHT_CARS.get());
        headlightRange = HEADLIGHT_RANGE.get();
        headlightWithShaderPacks = HEADLIGHT_WITH_SHADER_PACKS.get();
        headlightPackContrast = HEADLIGHT_PACK_CONTRAST.get();
        headlightPackTint = HEADLIGHT_PACK_TINT.get();
        headlightPackAdd = HEADLIGHT_PACK_ADD.get();
        headlightPackPreComposite = HEADLIGHT_PACK_PRE_COMPOSITE.get();
        headlightColor = HEADLIGHT_COLOR.get();
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
