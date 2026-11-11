package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.Slant;
import com.jdmmc.kurumamod.client.ui.UiButton;
import com.jdmmc.kurumamod.client.ui.UiCycleButton;
import com.jdmmc.kurumamod.client.ui.UiEditBox;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.client.ui.UiKey;
import com.jdmmc.kurumamod.client.ui.UiPressable;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.client.ui.UiSlider;
import com.jdmmc.kurumamod.client.ui.UiTooltip;
import com.jdmmc.kurumamod.client.ui.UiWidget;
import com.jdmmc.kurumamod.client.ui.GaugeShapes;
import com.jdmmc.kurumamod.client.ui.ScreenStyle;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.util.Mth;

import com.jdmmc.kurumamod.car.CarId;

import com.jdmmc.kurumamod.GateDisplay;
import com.jdmmc.kurumamod.GaugeTheme;
import com.jdmmc.kurumamod.SmokeQuality;
import com.jdmmc.kurumamod.SmokeStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * MOD メニュー。H キーで開く。
 *
 * <p><b>画面の好み（{@link ClientConfig}）はすべてここで変える。</b>以前は Mod 一覧の
 * 「Config」ボタンから開く別画面にあったが、<b>走りながら変えるものが混じっている</b>
 * ——メーターの大きさも濃さもテレメトリも、実際に運転している画面を見ないと決められない。
 * タイトル画面からしか開けない場所に置くのは筋が悪かった。</p>
 *
 * <p><b>背景は半透明のまま、ゲームも止めない</b>（{@link #isPauseScreen()}）。
 * {@code CarTuningScreen}（G）と同じで、<b>変えた結果がその場で後ろに見える</b>のが要点。
 * 濃さのスライダーを動かしながらメーターの見え方を決められる。</p>
 *
 * <p><b>変更はその場で効かせ、ファイルへ書くのは閉じるとき。</b>スライダーを動かすたびに
 * ファイルを書くのは無駄なので、{@code set()} と {@link ClientConfig#refresh()} だけを
 * 毎回行い、{@code SPEC.save()} は {@link #removed()} で 1 回。
 * <b>{@code refresh()} を省くと、実際に読まれている static フィールドが古いままになる</b>
 * （Forge のファイル監視は遅れるうえ届かないことがある）。</p>
 *
 * <p>Mod 一覧の「Config」ボタンからもこの画面が開く。<b>設定の置き場所は 1 つに保つこと</b>
 * ——2 か所にあると、どちらが効いているのか分からなくなる。</p>
 */
public class KurumaMenuScreen extends UiScreen {

    private static final int PANEL_WIDTH = 320;
    private static final int LABEL_WIDTH = 150;
    private static final int GAP = 6;
    private static final int RESET_WIDTH = 18;
    private static final int ROW_HEIGHT = 21;
    private static final int TITLE_Y = 8;
    private static final int LIST_TOP = 28;
    private static final int FOOTER_HEIGHT = 44;

    private static final int COLOR_PANEL = 0x60000000;
    private static final int COLOR_STRIPE = 0x24FFFFFF;
    private static final int COLOR_NAME = 0xFFC8C8C8;
    /**
     * 見出しの色。<b>黄色は「既定から変えた項目」に取ってある</b>（G の画面と同じ {@link ScreenStyle#ACCENT}）ので、
     * 見出しは薄い水色にする。見出しも黄色だと、どれを変えたのか見分けられない。
     */
    private static final int COLOR_HEADER = 0xFF8CC8FF;
    private static final int COLOR_HINT = 0xFF909090;

    /** 開く前の画面（版のバニラの画面）。閉じたらそこへ戻る。null ならゲームへ。 */
    private final Object parent;
    private final List<Row> rows = new ArrayList<>();

    private static final int SIDEBAR_WIDTH = 110;
    /** 操作説明のページのキーの列の幅。 */
    private static final int CONTROLS_KEY_WIDTH = 120;
    /** 操作説明のキーの色（右下の操作ヘルプと同じ）。 */
    private static final int COLOR_KEY = 0xFFFFD26A;
    private static final int SIDEBAR_GAP = 12;

    /**
     * 左の一覧で選ぶ区切り。項目が増えて 1 本の長い一覧では探しにくくなったので、区切りごとの
     * ページにした。最後に開いたページを覚えておく（走りながら同じ項目を何度も触るので）。
     */
    private enum Page {
        DISPLAY("display"), EFFECTS("effects"), GAUGES("gauges"), DRIVING("driving"), SOUND("sound"), CAMERA("camera"),
        /** 操作説明（キーの一覧）。右下の操作ヘルプと同じ中身。ヘルプを消した人もここで見られる */
        CONTROLS("controls"),
        /** 車の音（パック用）。左の一覧には出さず、Ctrl+H のデバッグ画面（{@link #soundDebug()}）だけで開く */
        CAR_SOUND("car_sound");

        private final String id;

        Page(String id) {
            this.id = id;
        }

        String translationKey() {
            return "screen.kurumamod.menu.section." + id;
        }
    }

    private static Page page = Page.DISPLAY;

    private int scroll;
    private int visibleRows;
    private int panelLeft;
    /** 車の音のページで動かしている車種。乗っていなければ null。 */
    private CarId carId;
    /** いまのページの項目を既定値へ戻す手順（「元に戻す」）。行を足すときに一緒に積む。 */
    private final List<Runnable> resets = new ArrayList<>();

    /** 車の音（パック用）だけを出すデバッグ画面か（Ctrl+H）。設定ではないので H の一覧には並べない。 */
    private final boolean soundDebug;

    public KurumaMenuScreen(Object parent) {
        this(parent, false);
    }

    private KurumaMenuScreen(Object parent, boolean soundDebug) {
        super(RaceText.translatable(soundDebug ? "screen.kurumamod.sound_debug.title" : "screen.kurumamod.menu.title"));
        this.parent = parent;
        this.soundDebug = soundDebug;
    }

    /**
     * 乗っている車種の音（{@code sounds}）を動かすデバッグ画面。Ctrl+H で開く。
     * 行の並べ方・スクロール・スライダーは MOD メニューと同じものを使う。
     */
    public static KurumaMenuScreen soundDebug() {
        return new KurumaMenuScreen(null, true);
    }

    /** いま出しているページ。デバッグ画面は車の音だけ。 */
    private Page shownPage() {
        return soundDebug ? Page.CAR_SOUND : page;
    }

    @Override
    protected void init() {
        rows.clear();
        resets.clear();
        carId = null;
        // 左に区切りの一覧、右に選んだ区切りの項目
        panelLeft = soundDebug ? (width - PANEL_WIDTH) / 2 : (width - PANEL_WIDTH + SIDEBAR_WIDTH + SIDEBAR_GAP) / 2;
        int sidebarLeft = panelLeft - SIDEBAR_GAP - SIDEBAR_WIDTH;
        int tabs = soundDebug ? 0 : Page.CAR_SOUND.ordinal();
        for (int i = 0; i < tabs; i++) {
            Page each = Page.values()[i];
            UiButton tab = UiButton.builder(RaceText.translatable(each.translationKey()), button -> {
                        page = each;
                        scroll = 0;
                        rebuildWidgets();
                    })
                    .bounds(sidebarLeft, LIST_TOP + i * (ROW_HEIGHT + 3), SIDEBAR_WIDTH, 20)
                    .build();
            // いま開いている区切りは押せない見た目にして、どこにいるかを示す
            tab.active = each != page;
            addRenderableWidget(tab);
        }

        switch (shownPage()) {
            case DISPLAY -> {
                // 操作ヘルプは最初に消したくなる項目なので先頭に置く（一覧の下の案内も「H で消せる」と言っている）
                header("screen.kurumamod.menu.group.hud");
                toggle("show_help", ClientOptions.get().bool("SHOW_HELP"));
                toggle("show_telemetry", ClientOptions.get().bool("SHOW_TELEMETRY"));
                header("screen.kurumamod.menu.group.race");
                toggle("time_attack_ghost", ClientOptions.get().bool("TIME_ATTACK_GHOST"));
                cycle("gate_display", GateDisplay.class, ClientOptions.get().choice("GATE_DISPLAY", GateDisplay.class),
                        display -> RaceText.translatable(display.translationKey()));
                header("screen.kurumamod.menu.group.mirror");
                toggle("live_mirrors", ClientOptions.get().bool("LIVE_MIRRORS"));
            }
            case EFFECTS -> {
                // 走りながら煙の見え方と重さを詰めるページ。描き方・品質・濃さはどれも走っている画面で決めるもの
                header("screen.kurumamod.menu.group.smoke");
                toggle("show_dust", ClientOptions.get().bool("SHOW_DUST"));
                slider("dust_density", 0.25, 2.0, ClientOptions.get().number("DUST_DENSITY"));
                cycle("smoke_style", SmokeStyle.class, ClientOptions.get().choice("SMOKE_STYLE", SmokeStyle.class),
                        style -> RaceText.translatable(style.translationKey()));
                cycle("smoke_quality", SmokeQuality.class, ClientOptions.get().choice("SMOKE_QUALITY", SmokeQuality.class),
                        quality -> RaceText.translatable(quality.translationKey()));
                slider("smoke_opacity", 0.25, 2.0, ClientOptions.get().number("SMOKE_OPACITY"));
                slider("smoke_lifetime", 0.5, 2.5, ClientOptions.get().number("SMOKE_LIFETIME"));
                header("screen.kurumamod.menu.group.smoke_packs");
                toggle("smoke_with_shader_packs", ClientOptions.get().bool("SMOKE_WITH_SHADER_PACKS"));
                slider("smoke_pack_brightness", 0.25, 3.0, ClientOptions.get().number("SMOKE_PACK_BRIGHTNESS"));
                header("screen.kurumamod.menu.group.skid_marks");
                toggle("show_own_skid_marks", ClientOptions.get().bool("SHOW_OWN_SKID_MARKS"));
                toggle("show_other_skid_marks", ClientOptions.get().bool("SHOW_OTHER_SKID_MARKS"));
                // 1 秒から 5 分まで（既定は 5 分）。線形だと数秒の短い値が左端 1% に潰れるので対数で割り当てる
                slider("skid_mark_seconds", 1.0, 300.0, true, ClientOptions.get().number("SKID_MARK_SECONDS"));
            }
            case GAUGES -> {
                toggle("show_gauges", ClientOptions.get().bool("SHOW_GAUGES"));
                cycle("gauge_theme", GaugeTheme.class, ClientOptions.get().choice("GAUGE_THEME", GaugeTheme.class),
                        theme -> RaceText.translatable(theme.translationKey()));
                slider("gauge_scale", 0.5, 2.0, ClientOptions.get().number("GAUGE_SCALE"));
                slider("gauge_opacity", 0.15, 1.0, ClientOptions.get().number("GAUGE_OPACITY"));
            }
            case DRIVING -> toggle("virtual_walls", ClientOptions.get().bool("VIRTUAL_WALLS"));
            case SOUND -> {
                percent("sound_master", ClientOptions.get().number("SOUND_MASTER"));
                percent("sound_engine", ClientOptions.get().number("SOUND_ENGINE"));
                percent("sound_turbo", ClientOptions.get().number("SOUND_TURBO"));
                percent("sound_tires", ClientOptions.get().number("SOUND_TIRES"));
                percent("sound_surface", ClientOptions.get().number("SOUND_SURFACE"));
                percent("sound_road", ClientOptions.get().number("SOUND_ROAD"));
                percent("sound_wind", ClientOptions.get().number("SOUND_WIND"));
                percent("sound_shift", ClientOptions.get().number("SOUND_SHIFT"));
                percent("sound_crash", ClientOptions.get().number("SOUND_CRASH"));
            }
            case CAMERA -> {
                slider("camera_distance", 1.5, 24.0, ClientOptions.get().number("CAMERA_DISTANCE"));
                slider("camera_height", 0.0, 8.0, ClientOptions.get().number("CAMERA_HEIGHT"));
                slider("camera_speed_pull", 0.0, 12.0, ClientOptions.get().number("CAMERA_SPEED_PULL"));
                slider("speed_fov", 0.0, 50.0, ClientOptions.get().number("SPEED_FOV"));
                slider("cockpit_look_ahead", 0.0, 1.6, ClientOptions.get().number("COCKPIT_LOOK_AHEAD"));
                slider("cockpit_drift_look", 0.0, 1.0, ClientOptions.get().number("COCKPIT_DRIFT_LOOK"));
            }
            case CONTROLS -> controlsPage();
            case CAR_SOUND -> carSoundPage();
        }

        int available = height - LIST_TOP - FOOTER_HEIGHT;
        visibleRows = Math.max(1, Math.min(rows.size(), available / ROW_HEIGHT));

        int footerY = LIST_TOP + Math.max(visibleRows * ROW_HEIGHT, tabs * (ROW_HEIGHT + 3)) + 8;
        if (carId != null) {
            // パック用の音: 決まった値を JSON で取り出す・動かした値を捨てる
            CarId id = carId;
            addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.menu.car_sound.copy"),
                            button -> copySoundsJson(id))
                    .bounds(panelLeft + PANEL_WIDTH / 2 - 190, footerY, 120, 20)
                    .build());
        }
        // 元に戻す。車の音のページは動かした値を捨ててパックの定義へ、ほかのページはそのページの項目を既定値へ
        CarId soundCar = carId;
        if (soundCar != null || !resets.isEmpty()) {
            addRenderableWidget(UiButton.builder(RaceText.translatable("screen.kurumamod.menu.reset"),
                            button -> {
                                if (soundCar != null) {
                                    CarSoundSet.clearDebugOverride(soundCar);
                                    rebuildWidgets();
                                } else {
                                    // 1 項目ずつの R と同じ手順を全部の行で回す（作り直さない）
                                    resets.forEach(Runnable::run);
                                }
                            })
                    .bounds(panelLeft + PANEL_WIDTH / 2 + 70, footerY, 120, 20)
                    .build());
        }
        addRenderableWidget(UiButton.builder(RaceText.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + PANEL_WIDTH / 2 - 60, footerY, 120, 20)
                .build());

        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        layout();
    }

    /**
     * 操作説明。右下の操作ヘルプと同じ一覧（{@link CarHelp#lines}）で、キーは割り当てから引く。
     * 変える項目は無いので、行は文字だけ（キーを左、説明を右に並べる）。
     */
    private void controlsPage() {
        for (CarClient.HelpLine line : CarClient.get().helpLines()) {
            rows.add(new Row(RaceText.translatable(line.descriptionKey()), null, false, null, null, line.key()));
        }
    }

    // ------------------------------------------------------------------
    // 車の音（パック用）
    // ------------------------------------------------------------------

    /** 動かせる音（{@code sounds} のキー）。エンジンは別に先頭へ置く。 */
    private static final String[] SOUND_KEYS = {
            "exhaust", "exhaust_high", "turbo", "blowoff", "slip", "slip_chatter",
            "gravel", "sand", "road", "wind", "shift", "crash"};

    /**
     * 乗っている車の車種の {@code sounds}（高さ・大きさ・エンジンの種類）を動かす。カーパックを作る人が、
     * JSON を書き換えて F3+T で読み直すのを繰り返さずに決めるためのページ。
     *
     * <p>値は {@link CarSoundSet#setDebugOverride} で<b>このクライアントだけ・ゲームを閉じるまで</b>効く
     * （座席の調整画面 {@code SeatScreen} と同じ）。決まったら「JSON をコピー」でパックへ貼る。
     * 高さと大きさは鳴っている音が毎ティック読み直すので、動かすとその場で聞こえる。</p>
     */
    private void carSoundPage() {
        carId = ridingCarId();
        if (carId == null) {
            rows.add(new Row(RaceText.translatable("screen.kurumamod.menu.car_sound.need_car"), null, false));
            return;
        }
        CarId id = carId;
        CarSoundSet riding = CarClient.get().ridingCarSounds();
        // 鳴っている側だけを出す。合成したエンジン音の車で exhaust を動かしても何も変わらない
        // （exhaust はパックかマフラーが音源を書いた車の排気音）。逆もそう
        boolean engine = riding == null || riding.usesEngine();
        if (engine) {
            engineRows(id);
        }
        for (String key : SOUND_KEYS) {
            boolean exhaust = key.startsWith("exhaust");
            if (exhaust == engine) {
                continue;
            }
            RaceText title = soundLabel("key", key);
            // 部品が付いていないと鳴らない音は、見出しで知らせる（動かしても聞こえない理由が分かるように）
            boolean silent = riding != null && (("turbo".equals(key) && !CarClient.get().ridingCarHasTurbo())
                    || ("blowoff".equals(key) && !CarClient.get().ridingCarHasBlowoff()));
            if (silent) {
                title = soundLabel("key", key).append("  ")
                        .append(RaceText.translatable("screen.kurumamod.sound_debug.no_part"));
            }
            rows.add(new Row(title, null, true));
            tuningRows(id, key, false);
        }
    }

    /** 合成したエンジン音の行（種類・高さ・大きさ・踏んだときの出方）。 */
    private void engineRows(CarId id) {
        CarSoundSet sounds = CarSoundSet.of(id);
        rows.add(new Row(soundLabel("key", CarSoundSet.ENGINE), null, true));
        RaceText typeLabel = soundLabel("field", "type");
        UiCycleButton<String> type = UiCycleButton.<String>builder(RaceText::literal)
                .withValues(com.jdmmc.kurumamod.sound.EngineTypes.ENGINE_TYPES)
                .withInitialValue(sounds.engineType())
                .displayOnlyValue()
                .create(widgetLeft(), 0, widgetWidth(), 18, typeLabel,
                        (widget, chosen) -> CarSoundSet.setDebugOverride(id, CarSoundSet.of(id).withEngineType(chosen)));
        addRenderableWidget(type);
        // 1 項目だけ戻すときは、パックに書いてある値へ戻す
        rows.add(new Row(typeLabel, type, false, resetButton(() -> {
            String packType = CarModel.get(id).sounds().engineType();
            CarSoundSet.setDebugOverride(id, CarSoundSet.of(id).withEngineType(packType));
            type.setValue(packType);
        }), () -> !CarSoundSet.of(id).engineType().equals(CarModel.get(id).sounds().engineType())));
        tuningRows(id, CarSoundSet.ENGINE, true);
    }

    private void tuningRows(CarId id, String key, boolean engine) {
        CarSoundSet.Tuning now = CarSoundSet.of(id).tuningOf(key);
        // 1 項目だけ戻すときは、パックに書いてある値へ戻す
        CarSoundSet.Tuning pack = CarModel.get(id).sounds().tuningOf(key);
        // 大きさはパックでは変えない（聞く人が H の「サウンド」で決める）ので、ここにも出さない
        tuningSlider("pitch", 0.5, 2.0, now.pitch(), pack.pitch(),
                () -> CarSoundSet.of(id).tuningOf(key).pitch(),
                v -> retune(id, key, t -> new CarSoundSet.Tuning(v, t.load())));
        if (engine) {
            tuningSlider("load", 0.0, 1.5, now.load(), pack.load(),
                    () -> CarSoundSet.of(id).tuningOf(key).load(),
                    v -> retune(id, key, t -> new CarSoundSet.Tuning(t.pitch(), v)));
        }
    }

    private void tuningSlider(String name, double min, double max, double initial, double packValue,
                              java.util.function.DoubleSupplier current, DoubleConsumer apply) {
        RaceText label = soundLabel("field", name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), min, max, false, initial, apply);
        addRenderableWidget(slider);
        // パックの値から動かしたら黄色
        rows.add(new Row(label, slider, false, resetButton(() -> {
            apply.accept(packValue);
            slider.show(packValue);
        }), () -> Math.abs(current.getAsDouble() - packValue) > 1e-9));
    }

    /**
     * 行の名前。メニューの項目と同じく人が読める名前に、JSON に書く名前を添える（「砂利・土の路面音 (gravel)」）。
     * 貼る先の JSON と見比べるので、名前だけにはしない。
     *
     * @param kind {@code key}（音の名前）か {@code field}（値の名前）
     */
    private static RaceText soundLabel(String kind, String name) {
        return RaceText.translatable("screen.kurumamod.sound_debug." + kind + "." + name)
                .append(" (" + name + ")");
    }

    private static void retune(CarId id, String key,
                               java.util.function.UnaryOperator<CarSoundSet.Tuning> change) {
        CarSoundSet now = CarSoundSet.of(id);
        CarSoundSet.setDebugOverride(id, now.withTuning(key, change.apply(now.tuningOf(key))));
    }

    /** {@code vehicles/<車>.json} へそのまま貼れる {@code "sounds": {...}} をクリップボードへ。 */
    private void copySoundsJson(CarId id) {
        String body = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(CarSoundSet.of(id).toJson());
        CarClient.get().setClipboard("\"sounds\": " + body);
        RaceClient.get().overlayMessage(RaceText.translatable("screen.kurumamod.menu.car_sound.copied"));
    }

    private CarId ridingCarId() {
        return CarClient.get().ridingCarId();
    }

    // ------------------------------------------------------------------
    // 行を足す
    // ------------------------------------------------------------------

    private void header(String key) {
        rows.add(new Row(RaceText.translatable(key), null, true));
    }

    private void toggle(String name, ClientOptions.Value<Boolean> value) {
        RaceText label = RaceText.translatable("config.kurumamod." + name);
        UiCycleButton<Boolean> button = UiCycleButton.onOffBuilder(value.get())
                .displayOnlyValue()
                .create(widgetLeft(), 0, widgetWidth(), 18, label, (widget, on) -> {
                    value.set(on);
                    ClientOptions.get().refresh();
                });
        addRow(name, label, button, () -> {
            value.set(value.getDefault());
            button.setValue(value.getDefault());
        }, () -> !value.get().equals(value.getDefault()));
    }

    private <T extends Enum<T>> void cycle(String name, Class<T> type,
                                           ClientOptions.Value<T> value,
                                           java.util.function.Function<T, RaceText> text) {
        RaceText label = RaceText.translatable("config.kurumamod." + name);
        UiCycleButton<T> button = UiCycleButton.<T>builder(text::apply)
                .withValues(type.getEnumConstants())
                .withInitialValue(value.get())
                .displayOnlyValue()
                .create(widgetLeft(), 0, widgetWidth(), 18, label, (widget, chosen) -> {
                    value.set(chosen);
                    ClientOptions.get().refresh();
                });
        addRow(name, label, button, () -> {
            value.set(value.getDefault());
            button.setValue(value.getDefault());
        }, () -> !value.get().equals(value.getDefault()));
    }

    private void slider(String name, double min, double max, ClientOptions.Number value) {
        slider(name, min, max, false, value);
    }

    /** @param logarithmic 2 倍が意味を持つ量（秒数など）は対数で割り当てる */
    private void slider(String name, double min, double max, boolean logarithmic,
                        ClientOptions.Number value) {
        RaceText label = RaceText.translatable("config.kurumamod." + name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), min, max, logarithmic,
                value.get(),
                set -> {
                    value.set(set);
                    ClientOptions.get().refresh();
                });
        addRow(name, label, slider, () -> {
            value.set(value.getDefault());
            slider.show(value.getDefault());
        }, () -> Math.abs(value.get() - value.getDefault()) > 1e-9);
    }

    /** 0〜200% の音量の倍率。値は 0..2 で持ち、% で見せる。 */
    private void percent(String name, ClientOptions.Number value) {
        RaceText label = RaceText.translatable("config.kurumamod." + name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), 0.0, 2.0, false,
                value.get(),
                set -> {
                    value.set(set);
                    ClientOptions.get().refresh();
                });
        slider.percent = true;
        slider.updateMessage();
        addRow(name, label, slider, () -> {
            value.set(value.getDefault());
            slider.show(value.getDefault());
        }, () -> Math.abs(value.get() - value.getDefault()) > 1e-9);
    }

    /**
     * 項目の行を足す。右端に 1 項目だけ既定へ戻す R ボタンを置く（G の画面と同じ）。
     *
     * <p><b>戻すときはウィジェットを作り直さず、見せている値だけを差し替える</b>（{@code resetOne}）。
     * 作り直すと、クリックの伝播中にウィジェット一覧を入れ替えることになって危うい。
     * ページごとの「元に戻す」も同じ手順を全部の行で回す。</p>
     */
    private void addRow(String name, RaceText label, UiWidget widget, Runnable resetOne,
                        java.util.function.BooleanSupplier changed) {
        widget.setTooltip(UiTooltip.create(
                RaceText.translatable("config.kurumamod." + name + ".tooltip")));
        addRenderableWidget(widget);
        Runnable apply = () -> {
            resetOne.run();
            ClientOptions.get().refresh();
        };
        resets.add(apply);
        rows.add(new Row(label, widget, false, resetButton(apply), changed));
    }

    /** 1 項目だけ戻す R ボタン。 */
    private UiButton resetButton(Runnable apply) {
        return addRenderableWidget(UiButton.builder(RaceText.literal("R"), button -> apply.run())
                .tooltip(UiTooltip.create(RaceText.translatable("screen.kurumamod.menu.reset_one")))
                .bounds(widgetLeft() + widgetWidth() + GAP, 0, RESET_WIDTH, 18)
                .build());
    }

    private int widgetLeft() {
        return panelLeft + LABEL_WIDTH;
    }

    private int widgetWidth() {
        return PANEL_WIDTH - LABEL_WIDTH - RESET_WIDTH - GAP * 2;
    }

    // ------------------------------------------------------------------
    // 並べる・描く
    // ------------------------------------------------------------------

    /** 表示範囲に入る行だけを並べ、外れた行は隠す。 */
    private void layout() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int index = i - scroll;
            row.y = LIST_TOP + index * ROW_HEIGHT;
            row.setVisible(index >= 0 && index < visibleRows);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        int max = Math.max(0, rows.size() - visibleRows);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            layout();
        }
        return true;
    }

    /**
     * 背景は自分で描く（または描かない）。<b>1.21 の {@code Screen#render} は先に背景（暗幕とぼかし）を
     * 描く</b>ので、ここで止めておかないと、この画面の上に描いたものが背景で塗りつぶされるうえ、
     * 走りながら見たい車がぼけて見えなくなる。ワールドの外（タイトル画面から開いたとき）だけは
     * 後ろに何も無いので、ふつうの背景を描く。
     */
    @Override
    public void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!inWorld()) {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void render(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 の renderBackground と同じ半透明の暗幕。1.21 の既定（ぼかし）だと後ろのゲームが見えない
        renderTransparentBackground(graphics);

        int listHeight = visibleRows * ROW_HEIGHT;
        graphics.fill(panelLeft - 5, LIST_TOP - 5, panelLeft + PANEL_WIDTH + 5,
                LIST_TOP + listHeight + 3, COLOR_PANEL);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.visible && ((i - scroll) & 1) == 0) {
                graphics.fill(panelLeft - 3, row.y - 2, panelLeft + PANEL_WIDTH + 3,
                        row.y + ROW_HEIGHT - 3, COLOR_STRIPE);
            }
        }

        // 変えていない行の R は押せない見た目にする（G の画面と同じ）
        for (Row row : rows) {
            if (row.reset != null) {
                row.reset.active = row.visible && row.isChanged();
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            if (row.keyName != null) {
                // 操作説明: キーを左（黄色）、説明を右に
                graphics.drawString(font, font.plainSubstrByWidth(row.keyName, CONTROLS_KEY_WIDTH - GAP), panelLeft,
                        row.y + 5, COLOR_KEY, false);
                graphics.drawString(font, font.plainSubstrByWidth(row.label.getString(), PANEL_WIDTH - CONTROLS_KEY_WIDTH),
                        panelLeft + CONTROLS_KEY_WIDTH, row.y + 5, COLOR_NAME, false);
                continue;
            }
            String name = row.label.getString();
            graphics.drawString(font,
                    font.plainSubstrByWidth(name, row.widget == null ? PANEL_WIDTH : LABEL_WIDTH - GAP),
                    panelLeft, row.y + 5,
                    row.isHeader ? COLOR_HEADER : row.isChanged() ? ScreenStyle.ACCENT : COLOR_NAME, false);
        }

        renderScrollBar(graphics, listHeight);

        graphics.drawCenteredString(font, RaceText.translatable(shownPage() == Page.CAR_SOUND
                        ? "screen.kurumamod.menu.car_sound.hint" : "screen.kurumamod.menu.hint"),
                width / 2, LIST_TOP + listHeight + 32, COLOR_HINT);
    }

    private void renderScrollBar(UiGraphics graphics, int listHeight) {
        int total = rows.size();
        if (total <= visibleRows) {
            return;
        }
        int left = panelLeft + PANEL_WIDTH + 8;
        graphics.fill(left, LIST_TOP, left + 3, LIST_TOP + listHeight, 0x40FFFFFF);
        int thumb = Math.max(12, listHeight * visibleRows / total);
        int top = LIST_TOP + (listHeight - thumb) * scroll / (total - visibleRows);
        graphics.fill(left, top, left + 3, top + thumb, 0xC0FFFFFF);
    }

    /**
     * 閉じるときにファイルへ書く。
     *
     * <p>1 回だけ書けばよい。効かせるのは {@link ClientConfig#refresh()} が済ませている。</p>
     */
    @Override
    public void removed() {
        if (soundDebug) {
            return;
        }
        ClientOptions.get().save();
    }

    @Override
    public void onClose() {
        CarClient.get().returnTo(parent);
    }

    /**
     * 運転に使うキーは画面へ渡さない。開いたまま運転できるので（{@link CarClientDriver#isDrivableScreen}）、
     * 渡すとスペース（サイドブレーキ）でフォーカス中のボタンが押されたり、
     * 矢印キーに割り当てた人のハンドルでフォーカスやスライダーが動いたりする。
     */
    @Override
    public boolean keyPressed(UiKey key) {
        if (key.driving()) {
            return true;
        }
        return super.keyPressed(key);
    }

    /** ゲームを止めない。止めると、変えた結果を後ろで見ながら決められない。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 項目 1 つぶんの行。見出しの行はウィジェットを持たない。 */
    private static final class Row {

        private final RaceText label;
        private final UiWidget widget;
        private final boolean isHeader;
        /** 1 項目だけ戻す R ボタン。見出しと案内の行には無い。 */
        private final UiButton reset;
        /** 既定（車の音ならパックの値）から変えているか。名前を黄色にする。無ければ変えようがない行 */
        private final java.util.function.BooleanSupplier changed;
        /** 操作説明の行のキー（左に黄色で出す）。ほかの行では null。 */
        private final String keyName;

        private int y;
        private boolean visible;

        private Row(RaceText label, UiWidget widget, boolean isHeader) {
            this(label, widget, isHeader, null);
        }

        private Row(RaceText label, UiWidget widget, boolean isHeader, UiButton reset) {
            this(label, widget, isHeader, reset, null);
        }

        private Row(RaceText label, UiWidget widget, boolean isHeader, UiButton reset,
                    java.util.function.BooleanSupplier changed) {
            this(label, widget, isHeader, reset, changed, null);
        }

        private Row(RaceText label, UiWidget widget, boolean isHeader, UiButton reset,
                    java.util.function.BooleanSupplier changed, String keyName) {
            this.keyName = keyName;
            this.label = label;
            this.widget = widget;
            this.isHeader = isHeader;
            this.reset = reset;
            this.changed = changed;
        }

        boolean isChanged() {
            return changed != null && changed.getAsBoolean();
        }

        void setVisible(boolean visible) {
            this.visible = visible;
            for (UiWidget each : new UiWidget[]{widget, reset}) {
                if (each != null) {
                    each.visible = visible;
                    each.active = visible;
                    each.setY(y);
                }
            }
        }
    }

    /**
     * 実数のスライダー。
     *
     * <p>{@link UiSlider} が持つ {@code value} は 0..1 なので、
     * 表示と設定への受け渡しで min..max へ直す。対数のときは min > 0 であること。</p>
     */
    private static final class OptionSlider extends UiSlider {

        private final double min;
        private final double max;
        private final boolean logarithmic;
        private final DoubleConsumer apply;
        /** % で見せる（0..2 → 0〜200%）。 */
        private boolean percent;

        private OptionSlider(int x, int width, double min, double max, boolean logarithmic,
                             double initial, DoubleConsumer apply) {
            super(x, 0, width, 18, RaceText.empty(), toPosition(min, max, logarithmic, initial));
            this.min = min;
            this.max = max;
            this.logarithmic = logarithmic;
            this.apply = apply;
            updateMessage();
        }

        private static double toPosition(double min, double max, boolean logarithmic, double v) {
            double position = logarithmic
                    ? Math.log(v / min) / Math.log(max / min)
                    : (v - min) / (max - min);
            return Math.max(0.0, Math.min(1.0, position));
        }

        private double actual() {
            return logarithmic
                    ? min * Math.pow(max / min, value)
                    : min + (max - min) * value;
        }

        @Override
        protected void updateMessage() {
            double v = actual();
            if (percent) {
                setMessage(RaceText.literal(String.format("%.0f%%", v * 100.0)));
                return;
            }
            // 対数の目盛りは桁が大きく動くので、大きい値で小数を並べない
            String format = !logarithmic ? "%.2f" : v < 10.0 ? "%.1f" : "%.0f";
            setMessage(RaceText.literal(String.format(format, v)));
        }

        @Override
        protected void applyValue() {
            apply.accept(actual());
        }

        /** 見せている値だけを差し替える（設定へは書かない。書くのは呼び出し側）。 */
        void show(double v) {
            value = toPosition(min, max, logarithmic, v);
            updateMessage();
        }
    }
}
