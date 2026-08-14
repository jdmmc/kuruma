package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 車のセッティング画面。
 *
 * <p>項目が 50 近くあるので<b>タブで分け、1 画面に収まる量だけ出す</b>。項目は
 * {@link Tunables} の定義から組み立てるので、この画面は項目が増えても触らなくてよい。</p>
 *
 * <p>1 行は「項目名 ／ スライダー（値と補足） ／ 個別リセット」の 3 列。名前と値を別の列に
 * 置くことで縦に読んだときに項目を追いやすくしている。既定値から変えた項目は名前の色が
 * 変わるので、どこをいじったか一目で分かる。</p>
 *
 * <p>スライダーを動かすとその場で {@link CarTuning} に反映され、運転中の車の挙動が次の
 * ティックから変わる。ゲームを止めないので、車が沈み込む様子をそのまま観察できる。</p>
 */
public class CarTuningScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int LABEL_WIDTH = 116;
    private static final int RESET_WIDTH = 18;
    private static final int GAP = 4;
    private static final int ROW_HEIGHT = 21;
    private static final int TAB_HEIGHT = 16;
    private static final int TITLE_Y = 8;
    private static final int TAB_Y = 24;
    private static final int LIST_TOP = TAB_Y + TAB_HEIGHT + 8;
    private static final int FOOTER_HEIGHT = 44;

    private static final int COLOR_PANEL = 0x60000000;
    private static final int COLOR_STRIPE = 0x24FFFFFF;
    private static final int COLOR_NAME = 0xFFC8C8C8;
    private static final int COLOR_NAME_CHANGED = 0xFFFFD26A;
    private static final int COLOR_HINT = 0xFF909090;

    /** 開き直したときに同じタブへ戻れるよう覚えておく。 */
    private static int selectedGroup;

    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private int visibleRows;
    private int panelLeft;

    public CarTuningScreen() {
        super(Component.translatable("screen.kurumamod.tuning.title"));
    }

    private static List<Tunables.Group> groups() {
        return Tunables.GROUPS;
    }

    @Override
    protected void init() {
        rows.clear();
        panelLeft = (width - PANEL_WIDTH) / 2;
        selectedGroup = Math.max(0, Math.min(selectedGroup, groups().size() - 1));

        addTabs();

        List<TunableParameter> parameters = groups().get(selectedGroup).parameters();
        int available = height - LIST_TOP - FOOTER_HEIGHT;
        visibleRows = Math.max(1, Math.min(parameters.size(), available / ROW_HEIGHT));
        for (TunableParameter parameter : parameters) {
            rows.add(createRow(parameter));
        }

        int footerY = LIST_TOP + visibleRows * ROW_HEIGHT + 8;
        int buttonWidth = (PANEL_WIDTH - GAP * 2) / 3;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.presets"),
                        button -> minecraft.setScreen(new CarPresetScreen(this)))
                .bounds(panelLeft, footerY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.tuning.reset_all"),
                        button -> {
                            CarTuning.reset();
                            rows.forEach(Row::syncSlider);
                            syncToServer();
                        })
                .bounds(panelLeft + buttonWidth + GAP, footerY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + (buttonWidth + GAP) * 2, footerY, buttonWidth, 20)
                .build());

        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        layout();
    }

    /** 上端のタブ。選択中のものは押せない状態にして、押し込まれた見た目にする。 */
    private void addTabs() {
        int count = groups().size();
        int tabWidth = (PANEL_WIDTH - (count - 1) * 2) / count;
        for (int i = 0; i < count; i++) {
            Tunables.Group group = groups().get(i);
            int index = i;
            Button tab = Button.builder(Component.translatable(group.shortKey()), button -> {
                        selectedGroup = index;
                        scroll = 0;
                        rebuildWidgets();
                    })
                    .tooltip(Tooltip.create(Component.translatable(group.translationKey())))
                    .bounds(panelLeft + i * (tabWidth + 2), TAB_Y, tabWidth, TAB_HEIGHT)
                    .build();
            tab.active = i != selectedGroup;
            addRenderableWidget(tab);
        }
    }

    private Row createRow(TunableParameter parameter) {
        int sliderLeft = panelLeft + LABEL_WIDTH;
        int sliderWidth = PANEL_WIDTH - LABEL_WIDTH - RESET_WIDTH - GAP;
        ParameterSlider slider = addRenderableWidget(
                new ParameterSlider(sliderLeft, 0, sliderWidth, 18, parameter));
        // ウィジェットを作り直すのではなくスライダーの値だけ差し替える。
        // 作り直すと、クリックの伝播中にウィジェット一覧を入れ替えることになって危うい
        Button reset = addRenderableWidget(Button.builder(
                        Component.literal("R"),
                        button -> {
                            CarTuning.resetParameter(parameter);
                            slider.syncFromTuning();
                            syncToServer();
                        })
                .tooltip(Tooltip.create(Component.translatable("screen.kurumamod.tuning.reset_one")))
                .bounds(sliderLeft + sliderWidth + GAP, 0, RESET_WIDTH, 18)
                .build());
        return new Row(parameter, slider, reset);
    }

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

        for (Row row : rows) {
            row.refresh();
        }

        int listHeight = visibleRows * ROW_HEIGHT;
        graphics.fill(panelLeft - 5, LIST_TOP - 5, panelLeft + PANEL_WIDTH + 5,
                LIST_TOP + listHeight + 3, COLOR_PANEL);
        // 1 行おきの縞。横に長い行でも目線がずれない
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
            String name = Component.translatable(row.parameter.translationKey()).getString();
            boolean changed = !row.parameter.isDefault(CarTuning.spec(), CarTuning.base());
            graphics.drawString(font, font.plainSubstrByWidth(name, LABEL_WIDTH - GAP),
                    panelLeft, row.y + 5, changed ? COLOR_NAME_CHANGED : COLOR_NAME, false);
        }

        renderScrollBar(graphics, listHeight);

        graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.tuning.hint"),
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

    @Override
    public void removed() {
        // キーボードで動かした場合は onRelease が来ないので、閉じるときにも送っておく
        syncToServer();
    }

    /** ゲームを止めない。止めると挙動の変化を見ながら調整できないため。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void syncToServer() {
        CarClientDriver.sendTuningToServer();
    }

    /** 項目 1 つぶんの行。 */
    private static final class Row {

        private final TunableParameter parameter;
        private final ParameterSlider slider;
        private final Button reset;

        private int y;
        private boolean visible;

        private Row(TunableParameter parameter, ParameterSlider slider, Button reset) {
            this.parameter = parameter;
            this.slider = slider;
            this.reset = reset;
        }

        void setVisible(boolean visible) {
            this.visible = visible;
            slider.visible = visible;
            slider.active = visible;
            slider.setY(y);
            reset.visible = visible;
            reset.setY(y);
        }

        /** スライダーのつまみの位置を今の設定値に合わせる。 */
        void syncSlider() {
            slider.syncFromTuning();
        }

        /** 表示中の行の状態を今の設定値に合わせる。 */
        void refresh() {
            if (!visible) {
                return;
            }
            reset.active = !parameter.isDefault(CarTuning.spec(), CarTuning.base());
            // 減衰比のように他項目の影響を受ける補足表示があるので、毎フレーム作り直す
            slider.refreshLabel();
        }
    }

    /** {@link TunableParameter} 1 項目ぶんのスライダー。出すのは値と補足だけで、名前は左の列。 */
    private static class ParameterSlider extends AbstractSliderButton {

        private final TunableParameter parameter;

        ParameterSlider(int x, int y, int width, int height, TunableParameter parameter) {
            super(x, y, width, height, Component.empty(), parameter.sliderPosition(CarTuning.spec()));
            this.parameter = parameter;
            updateMessage();
        }

        void refreshLabel() {
            updateMessage();
        }

        /** つまみの位置を設定値から作り直す。リセットで呼ばれる。 */
        void syncFromTuning() {
            value = parameter.sliderPosition(CarTuning.spec());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    parameter.valueKey(), parameter.labelArguments(CarTuning.spec())));
        }

        @Override
        protected void applyValue() {
            CarTuning.set(parameter, parameter.displayFromSlider(value));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            syncToServer();
        }
    }
}
