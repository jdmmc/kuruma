package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.GateDisplay;
import com.jdmmc.kurumamod.GaugeTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import com.jdmmc.kurumamod.sound.KurumaSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

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
public class KurumaMenuScreen extends Screen {

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

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private static final int SIDEBAR_WIDTH = 110;
    private static final int SIDEBAR_GAP = 12;

    /**
     * 左の一覧で選ぶ区切り。項目が増えて 1 本の長い一覧では探しにくくなったので、区切りごとの
     * ページにした。最後に開いたページを覚えておく（走りながら同じ項目を何度も触るので）。
     */
    private enum Page {
        DISPLAY("display"), GAUGES("gauges"), DRIVING("driving"), SOUND("sound"), CAMERA("camera"),
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
    private ResourceLocation carId;
    /** いまのページの項目を既定値へ戻す手順（「元に戻す」）。行を足すときに一緒に積む。 */
    private final List<Runnable> resets = new ArrayList<>();

    /** 車の音（パック用）だけを出すデバッグ画面か（Ctrl+H）。設定ではないので H の一覧には並べない。 */
    private final boolean soundDebug;

    public KurumaMenuScreen(Screen parent) {
        this(parent, false);
    }

    private KurumaMenuScreen(Screen parent, boolean soundDebug) {
        super(Component.translatable(soundDebug ? "screen.kurumamod.sound_debug.title" : "screen.kurumamod.menu.title"));
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
            Button tab = Button.builder(Component.translatable(each.translationKey()), button -> {
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
                toggle("show_help", ClientConfig.SHOW_HELP);
                toggle("show_telemetry", ClientConfig.SHOW_TELEMETRY);
                header("screen.kurumamod.menu.group.race");
                toggle("time_attack_ghost", ClientConfig.TIME_ATTACK_GHOST);
                cycle("gate_display", GateDisplay.class, ClientConfig.GATE_DISPLAY,
                        display -> Component.translatable(display.translationKey()));
                header("screen.kurumamod.menu.group.mirror");
                toggle("live_mirrors", ClientConfig.LIVE_MIRRORS);
                header("screen.kurumamod.menu.group.effects");
                toggle("show_dust", ClientConfig.SHOW_DUST);
                slider("dust_density", 0.25, 2.0, ClientConfig.DUST_DENSITY);
                toggle("show_own_skid_marks", ClientConfig.SHOW_OWN_SKID_MARKS);
                toggle("show_other_skid_marks", ClientConfig.SHOW_OTHER_SKID_MARKS);
                // 1 秒から 5 分まで（既定は 5 分）。線形だと数秒の短い値が左端 1% に潰れるので対数で割り当てる
                slider("skid_mark_seconds", 1.0, 300.0, true, ClientConfig.SKID_MARK_SECONDS);
            }
            case GAUGES -> {
                toggle("show_gauges", ClientConfig.SHOW_GAUGES);
                cycle("gauge_theme", GaugeTheme.class, ClientConfig.GAUGE_THEME,
                        theme -> Component.translatable(theme.translationKey()));
                slider("gauge_scale", 0.5, 2.0, ClientConfig.GAUGE_SCALE);
                slider("gauge_opacity", 0.15, 1.0, ClientConfig.GAUGE_OPACITY);
            }
            case DRIVING -> toggle("virtual_walls", ClientConfig.VIRTUAL_WALLS);
            case SOUND -> {
                percent("sound_master", ClientConfig.SOUND_MASTER);
                percent("sound_engine", ClientConfig.SOUND_ENGINE);
                percent("sound_turbo", ClientConfig.SOUND_TURBO);
                percent("sound_tires", ClientConfig.SOUND_TIRES);
                percent("sound_surface", ClientConfig.SOUND_SURFACE);
                percent("sound_road", ClientConfig.SOUND_ROAD);
                percent("sound_wind", ClientConfig.SOUND_WIND);
                percent("sound_shift", ClientConfig.SOUND_SHIFT);
                percent("sound_crash", ClientConfig.SOUND_CRASH);
            }
            case CAMERA -> {
                slider("camera_distance", 1.5, 24.0, ClientConfig.CAMERA_DISTANCE);
                slider("camera_height", 0.0, 8.0, ClientConfig.CAMERA_HEIGHT);
                slider("camera_speed_pull", 0.0, 12.0, ClientConfig.CAMERA_SPEED_PULL);
                slider("speed_fov", 0.0, 50.0, ClientConfig.SPEED_FOV);
                slider("cockpit_look_ahead", 0.0, 1.6, ClientConfig.COCKPIT_LOOK_AHEAD);
                slider("cockpit_drift_look", 0.0, 1.0, ClientConfig.COCKPIT_DRIFT_LOOK);
            }
            case CAR_SOUND -> carSoundPage();
        }

        int available = height - LIST_TOP - FOOTER_HEIGHT;
        visibleRows = Math.max(1, Math.min(rows.size(), available / ROW_HEIGHT));

        int footerY = LIST_TOP + Math.max(visibleRows * ROW_HEIGHT, tabs * (ROW_HEIGHT + 3)) + 8;
        if (carId != null) {
            // パック用の音: 決まった値を JSON で取り出す・動かした値を捨てる
            ResourceLocation id = carId;
            addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.menu.car_sound.copy"),
                            button -> copySoundsJson(id))
                    .bounds(panelLeft + PANEL_WIDTH / 2 - 190, footerY, 120, 20)
                    .build());
        }
        // 元に戻す。車の音のページは動かした値を捨ててパックの定義へ、ほかのページはそのページの項目を既定値へ
        ResourceLocation soundCar = carId;
        if (soundCar != null || !resets.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.menu.reset"),
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
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + PANEL_WIDTH / 2 - 60, footerY, 120, 20)
                .build());

        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        layout();
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
            rows.add(new Row(Component.translatable("screen.kurumamod.menu.car_sound.need_car"), null, false));
            return;
        }
        ResourceLocation id = carId;
        com.jdmmc.kurumamod.entity.CarEntity car = ridingCar();
        // 鳴っている側だけを出す。合成したエンジン音の車で exhaust を動かしても何も変わらない
        // （exhaust はパックかマフラーが音源を書いた車の排気音）。逆もそう
        boolean engine = car == null || CarSound.soundsOf(car).usesEngine();
        if (engine) {
            engineRows(id);
        }
        for (String key : SOUND_KEYS) {
            boolean exhaust = key.startsWith("exhaust");
            if (exhaust == engine) {
                continue;
            }
            Component title = soundLabel("key", key);
            // 部品が付いていないと鳴らない音は、見出しで知らせる（動かしても聞こえない理由が分かるように）
            boolean silent = car != null && (("turbo".equals(key) && !car.hasTurbo())
                    || ("blowoff".equals(key) && !car.hasBlowoff()));
            if (silent) {
                title = soundLabel("key", key).copy().append("  ")
                        .append(Component.translatable("screen.kurumamod.sound_debug.no_part"));
            }
            rows.add(new Row(title, null, true));
            tuningRows(id, key, false);
        }
    }

    /** 合成したエンジン音の行（種類・高さ・大きさ・踏んだときの出方）。 */
    private void engineRows(ResourceLocation id) {
        CarSoundSet sounds = CarSoundSet.of(id);
        rows.add(new Row(soundLabel("key", CarSoundSet.ENGINE), null, true));
        Component typeLabel = soundLabel("field", "type");
        CycleButton<String> type = CycleButton.<String>builder(Component::literal)
                .withValues(KurumaSounds.ENGINE_TYPES)
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

    private void tuningRows(ResourceLocation id, String key, boolean engine) {
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
        Component label = soundLabel("field", name);
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
    private static Component soundLabel(String kind, String name) {
        return Component.translatable("screen.kurumamod.sound_debug." + kind + "." + name)
                .append(" (" + name + ")");
    }

    private static void retune(ResourceLocation id, String key,
                               java.util.function.UnaryOperator<CarSoundSet.Tuning> change) {
        CarSoundSet now = CarSoundSet.of(id);
        CarSoundSet.setDebugOverride(id, now.withTuning(key, change.apply(now.tuningOf(key))));
    }

    /** {@code vehicles/<車>.json} へそのまま貼れる {@code "sounds": {...}} をクリップボードへ。 */
    private void copySoundsJson(ResourceLocation id) {
        String body = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(CarSoundSet.of(id).toJson());
        minecraft.keyboardHandler.setClipboard("\"sounds\": " + body);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.kurumamod.menu.car_sound.copied"), true);
        }
    }

    private ResourceLocation ridingCarId() {
        com.jdmmc.kurumamod.entity.CarEntity car = ridingCar();
        return car != null ? car.getCarId() : null;
    }

    private com.jdmmc.kurumamod.entity.CarEntity ridingCar() {
        if (minecraft != null && minecraft.player != null
                && minecraft.player.getVehicle() instanceof com.jdmmc.kurumamod.entity.CarEntity car) {
            return car;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 行を足す
    // ------------------------------------------------------------------

    private void header(String key) {
        rows.add(new Row(Component.translatable(key), null, true));
    }

    private void toggle(String name, ForgeConfigSpec.BooleanValue value) {
        Component label = Component.translatable("config.kurumamod." + name);
        CycleButton<Boolean> button = CycleButton.onOffBuilder(value.get())
                .displayOnlyValue()
                .create(widgetLeft(), 0, widgetWidth(), 18, label, (widget, on) -> {
                    value.set(on);
                    ClientConfig.refresh();
                });
        addRow(name, label, button, () -> {
            value.set(value.getDefault());
            button.setValue(value.getDefault());
        }, () -> !value.get().equals(value.getDefault()));
    }

    private <T extends Enum<T>> void cycle(String name, Class<T> type,
                                           ForgeConfigSpec.EnumValue<T> value,
                                           java.util.function.Function<T, Component> text) {
        Component label = Component.translatable("config.kurumamod." + name);
        CycleButton<T> button = CycleButton.<T>builder(text::apply)
                .withValues(type.getEnumConstants())
                .withInitialValue(value.get())
                .displayOnlyValue()
                .create(widgetLeft(), 0, widgetWidth(), 18, label, (widget, chosen) -> {
                    value.set(chosen);
                    ClientConfig.refresh();
                });
        addRow(name, label, button, () -> {
            value.set(value.getDefault());
            button.setValue(value.getDefault());
        }, () -> !value.get().equals(value.getDefault()));
    }

    private void slider(String name, double min, double max, ForgeConfigSpec.DoubleValue value) {
        slider(name, min, max, false, value);
    }

    /** @param logarithmic 2 倍が意味を持つ量（秒数など）は対数で割り当てる */
    private void slider(String name, double min, double max, boolean logarithmic,
                        ForgeConfigSpec.DoubleValue value) {
        Component label = Component.translatable("config.kurumamod." + name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), min, max, logarithmic,
                value.get(),
                set -> {
                    value.set(set);
                    ClientConfig.refresh();
                });
        addRow(name, label, slider, () -> {
            value.set(value.getDefault());
            slider.show(value.getDefault());
        }, () -> Math.abs(value.get() - value.getDefault()) > 1e-9);
    }

    /** 0〜200% の音量の倍率。値は 0..2 で持ち、% で見せる。 */
    private void percent(String name, ForgeConfigSpec.DoubleValue value) {
        Component label = Component.translatable("config.kurumamod." + name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), 0.0, 2.0, false,
                value.get(),
                set -> {
                    value.set(set);
                    ClientConfig.refresh();
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
    private void addRow(String name, Component label, AbstractWidget widget, Runnable resetOne,
                        java.util.function.BooleanSupplier changed) {
        widget.setTooltip(Tooltip.create(
                Component.translatable("config.kurumamod." + name + ".tooltip")));
        addRenderableWidget(widget);
        Runnable apply = () -> {
            resetOne.run();
            ClientConfig.refresh();
        };
        resets.add(apply);
        rows.add(new Row(label, widget, false, resetButton(apply), changed));
    }

    /** 1 項目だけ戻す R ボタン。 */
    private Button resetButton(Runnable apply) {
        return addRenderableWidget(Button.builder(Component.literal("R"), button -> apply.run())
                .tooltip(Tooltip.create(Component.translatable("screen.kurumamod.menu.reset_one")))
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, rows.size() - visibleRows);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            layout();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

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
            String name = row.label.getString();
            graphics.drawString(font,
                    font.plainSubstrByWidth(name, row.widget == null ? PANEL_WIDTH : LABEL_WIDTH - GAP),
                    panelLeft, row.y + 5,
                    row.isHeader ? COLOR_HEADER : row.isChanged() ? ScreenStyle.ACCENT : COLOR_NAME, false);
        }

        renderScrollBar(graphics, listHeight);

        graphics.drawCenteredString(font, Component.translatable(shownPage() == Page.CAR_SOUND
                        ? "screen.kurumamod.menu.car_sound.hint" : "screen.kurumamod.menu.hint"),
                width / 2, LIST_TOP + listHeight + 32, COLOR_HINT);
    }

    private void renderScrollBar(GuiGraphics graphics, int listHeight) {
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
        ClientConfig.SPEC.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /**
     * 運転に使うキーは画面へ渡さない。開いたまま運転できるので（{@link CarClientDriver#isDrivableScreen}）、
     * 渡すとスペース（サイドブレーキ）でフォーカス中のボタンが押されたり、
     * 矢印キーに割り当てた人のハンドルでフォーカスやスライダーが動いたりする。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CarClientDriver.isDrivingKey(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** ゲームを止めない。止めると、変えた結果を後ろで見ながら決められない。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 項目 1 つぶんの行。見出しの行はウィジェットを持たない。 */
    private static final class Row {

        private final Component label;
        private final AbstractWidget widget;
        private final boolean isHeader;
        /** 1 項目だけ戻す R ボタン。見出しと案内の行には無い。 */
        private final Button reset;
        /** 既定（車の音ならパックの値）から変えているか。名前を黄色にする。無ければ変えようがない行 */
        private final java.util.function.BooleanSupplier changed;

        private int y;
        private boolean visible;

        private Row(Component label, AbstractWidget widget, boolean isHeader) {
            this(label, widget, isHeader, null);
        }

        private Row(Component label, AbstractWidget widget, boolean isHeader, Button reset) {
            this(label, widget, isHeader, reset, null);
        }

        private Row(Component label, AbstractWidget widget, boolean isHeader, Button reset,
                    java.util.function.BooleanSupplier changed) {
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
            for (AbstractWidget each : new AbstractWidget[]{widget, reset}) {
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
     * <p>{@link AbstractSliderButton} が持つ {@code value} は 0..1 なので、
     * 表示と設定への受け渡しで min..max へ直す。対数のときは min > 0 であること。</p>
     */
    private static final class OptionSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final boolean logarithmic;
        private final DoubleConsumer apply;
        /** % で見せる（0..2 → 0〜200%）。 */
        private boolean percent;

        private OptionSlider(int x, int width, double min, double max, boolean logarithmic,
                             double initial, DoubleConsumer apply) {
            super(x, 0, width, 18, Component.empty(), toPosition(min, max, logarithmic, initial));
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
                setMessage(Component.literal(String.format("%.0f%%", v * 100.0)));
                return;
            }
            // 対数の目盛りは桁が大きく動くので、大きい値で小数を並べない
            String format = !logarithmic ? "%.2f" : v < 10.0 ? "%.1f" : "%.0f";
            setMessage(Component.literal(String.format(format, v)));
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
