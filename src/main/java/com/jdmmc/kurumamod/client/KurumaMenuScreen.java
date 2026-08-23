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
import net.minecraft.network.chat.Component;
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
    private static final int ROW_HEIGHT = 21;
    private static final int TITLE_Y = 8;
    private static final int LIST_TOP = 28;
    private static final int FOOTER_HEIGHT = 44;

    private static final int COLOR_PANEL = 0x60000000;
    private static final int COLOR_STRIPE = 0x24FFFFFF;
    private static final int COLOR_NAME = 0xFFC8C8C8;
    private static final int COLOR_HEADER = 0xFFFFD26A;
    private static final int COLOR_HINT = 0xFF909090;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private int scroll;
    private int visibleRows;
    private int panelLeft;

    public KurumaMenuScreen(Screen parent) {
        super(Component.translatable("screen.kurumamod.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        panelLeft = (width - PANEL_WIDTH) / 2;

        header("screen.kurumamod.menu.section.display");
        toggle("show_telemetry", ClientConfig.SHOW_TELEMETRY);
        toggle("show_gauges", ClientConfig.SHOW_GAUGES);
        cycle("gauge_theme", GaugeTheme.class, ClientConfig.GAUGE_THEME,
                theme -> Component.translatable(theme.translationKey()));
        slider("gauge_scale", 0.5, 2.0, ClientConfig.GAUGE_SCALE);
        slider("gauge_opacity", 0.15, 1.0, ClientConfig.GAUGE_OPACITY);
        toggle("show_help", ClientConfig.SHOW_HELP);
        cycle("gate_display", GateDisplay.class, ClientConfig.GATE_DISPLAY,
                display -> Component.translatable(display.translationKey()));

        header("screen.kurumamod.menu.section.camera");
        slider("camera_distance", 1.5, 24.0, ClientConfig.CAMERA_DISTANCE);
        slider("camera_height", 0.0, 8.0, ClientConfig.CAMERA_HEIGHT);
        slider("camera_speed_pull", 0.0, 12.0, ClientConfig.CAMERA_SPEED_PULL);
        slider("speed_fov", 0.0, 50.0, ClientConfig.SPEED_FOV);

        int available = height - LIST_TOP - FOOTER_HEIGHT;
        visibleRows = Math.max(1, Math.min(rows.size(), available / ROW_HEIGHT));

        int footerY = LIST_TOP + visibleRows * ROW_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + PANEL_WIDTH / 2 - 60, footerY, 120, 20)
                .build());

        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        layout();
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
        addRow(name, label, button);
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
        addRow(name, label, button);
    }

    private void slider(String name, double min, double max, ForgeConfigSpec.DoubleValue value) {
        Component label = Component.translatable("config.kurumamod." + name);
        OptionSlider slider = new OptionSlider(widgetLeft(), widgetWidth(), min, max, value.get(),
                set -> {
                    value.set(set);
                    ClientConfig.refresh();
                });
        addRow(name, label, slider);
    }

    private void addRow(String name, Component label, AbstractWidget widget) {
        widget.setTooltip(Tooltip.create(
                Component.translatable("config.kurumamod." + name + ".tooltip")));
        addRenderableWidget(widget);
        rows.add(new Row(label, widget, false));
    }

    private int widgetLeft() {
        return panelLeft + LABEL_WIDTH;
    }

    private int widgetWidth() {
        return PANEL_WIDTH - LABEL_WIDTH - GAP;
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

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        for (Row row : rows) {
            if (!row.visible) {
                continue;
            }
            String name = row.label.getString();
            graphics.drawString(font,
                    font.plainSubstrByWidth(name, LABEL_WIDTH - GAP),
                    panelLeft, row.y + 5, row.isHeader ? COLOR_HEADER : COLOR_NAME, false);
        }

        renderScrollBar(graphics, listHeight);

        graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.menu.hint"),
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
        ClientConfig.SPEC.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
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

        private int y;
        private boolean visible;

        private Row(Component label, AbstractWidget widget, boolean isHeader) {
            this.label = label;
            this.widget = widget;
            this.isHeader = isHeader;
        }

        void setVisible(boolean visible) {
            this.visible = visible;
            if (widget != null) {
                widget.visible = visible;
                widget.active = visible;
                widget.setY(y);
            }
        }
    }

    /**
     * 実数のスライダー。
     *
     * <p>{@link AbstractSliderButton} が持つ {@code value} は 0..1 なので、
     * 表示と設定への受け渡しで min..max へ直す。</p>
     */
    private static final class OptionSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final DoubleConsumer apply;

        private OptionSlider(int x, int width, double min, double max, double initial,
                             DoubleConsumer apply) {
            super(x, 0, width, 18, Component.empty(), (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.apply = apply;
            updateMessage();
        }

        private double actual() {
            return min + (max - min) * value;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("%.2f", actual())));
        }

        @Override
        protected void applyValue() {
            apply.accept(actual());
        }
    }
}
