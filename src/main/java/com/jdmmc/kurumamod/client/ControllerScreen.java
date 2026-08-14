package com.jdmmc.kurumamod.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * コントローラの割り当て画面。
 *
 * <p>1 行が 1 操作。行を押すと待ち受けに入り、<b>次に動かした軸か押したボタンをそのまま割り当てる</b>。
 * 番号を選ばせる形にすると、どの軸がどれか分からないので使えない。</p>
 *
 * <p>右側に今の入力値を出しているのは、割り当てた直後に<b>正しく動いているかその場で
 * 確かめられる</b>ようにするため。向きが逆なら「反転」で直せる。</p>
 */
public class ControllerScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 44;

    /** 待ち受け中に「動いた」と見なす軸の変化量。触っていない軸の揺れを拾わないだけの幅が要る。 */
    private static final double DETECT_THRESHOLD = 0.5;

    private static final int COLOR_LABEL = 0xFFC8C8C8;
    private static final int COLOR_VALUE = 0xFF7FD8FF;
    private static final int COLOR_LISTENING = 0xFFFFD26A;
    private static final int COLOR_HINT = 0xFF909090;
    private static final int COLOR_PANEL = 0x60000000;

    private final List<Row> rows = new ArrayList<>();
    private int panelLeft;

    /** 割り当て待ちの操作。null なら待っていない。 */
    private ControllerAction listening;
    /** 待ち受けを始めた瞬間の軸。ここからの変化で判定する。 */
    private float[] baseline = new float[0];

    public ControllerScreen() {
        super(Component.translatable("screen.kurumamod.controller.title"));
    }

    @Override
    protected void init() {
        rows.clear();
        panelLeft = (width - PANEL_WIDTH) / 2;

        int y = LIST_TOP;
        for (ControllerAction action : ControllerAction.values()) {
            Button assign = addRenderableWidget(Button.builder(
                            Component.translatable("screen.kurumamod.controller.assign"),
                            button -> startListening(action))
                    .bounds(panelLeft + 150, y, 70, 20)
                    .build());
            Button invert = addRenderableWidget(Button.builder(
                            Component.translatable("screen.kurumamod.controller.invert"),
                            button -> toggleInvert(action))
                    .bounds(panelLeft + 226, y, 54, 20)
                    .build());
            // ボタン割り当てには向きが無い
            invert.active = ControllerInput.binding(action).type() == ControllerBinding.Type.AXIS;
            rows.add(new Row(action, y, assign, invert));
            y += ROW_HEIGHT;
        }

        int footer = LIST_TOP + ControllerAction.values().length * ROW_HEIGHT + 10;
        addRenderableWidget(Button.builder(
                        Component.translatable(ControllerInput.isEnabled()
                                ? "screen.kurumamod.controller.on"
                                : "screen.kurumamod.controller.off"),
                        button -> {
                            ControllerInput.setEnabled(!ControllerInput.isEnabled());
                            rebuildWidgets();
                        })
                .bounds(panelLeft, footer, 100, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.controller.reset"),
                        button -> {
                            ControllerInput.resetBindings();
                            rebuildWidgets();
                        })
                .bounds(panelLeft + 106, footer, 100, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + 212, footer, 108, 20)
                .build());
    }

    private void startListening(ControllerAction action) {
        listening = action;
        // 押しっぱなしのトリガーを拾わないよう、今の値を基準にする
        baseline = ControllerInput.axes().clone();
    }

    private void toggleInvert(ControllerAction action) {
        ControllerBinding binding = ControllerInput.binding(action);
        if (binding.type() != ControllerBinding.Type.AXIS) {
            return;
        }
        ControllerInput.bind(action, ControllerBinding.axis(
                binding.index(), !binding.invert(), binding.fullAxis()));
    }

    /** 待ち受け中に、動いた軸か押されたボタンを拾う。 */
    private void detect() {
        if (listening == null) {
            return;
        }
        boolean[] buttons = ControllerInput.buttons();
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i]) {
                ControllerInput.bind(listening, ControllerBinding.button(i));
                listening = null;
                rebuildWidgets();
                return;
            }
        }
        float[] axes = ControllerInput.axes();
        for (int i = 0; i < axes.length && i < baseline.length; i++) {
            double moved = axes[i] - baseline[i];
            if (Math.abs(moved) < DETECT_THRESHOLD) {
                continue;
            }
            // スティックは静止位置が 0、トリガーは -1。基準値から見分ける
            boolean fullAxis = Math.abs(baseline[i]) < 0.5;
            ControllerInput.bind(listening, ControllerBinding.axis(i, moved < 0, fullAxis));
            listening = null;
            rebuildWidgets();
            return;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        detect();

        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);

        String device = ControllerInput.name();
        Component status = device == null
                ? Component.translatable("screen.kurumamod.controller.none")
                : Component.literal(device);
        graphics.drawCenteredString(font, status, width / 2, 26,
                device == null ? COLOR_HINT : COLOR_VALUE);

        int listHeight = ControllerAction.values().length * ROW_HEIGHT;
        graphics.fill(panelLeft - 4, LIST_TOP - 4, panelLeft + PANEL_WIDTH + 4,
                LIST_TOP + listHeight, COLOR_PANEL);

        super.render(graphics, mouseX, mouseY, partialTick);

        for (Row row : rows) {
            graphics.drawString(font, Component.translatable(row.action.translationKey()),
                    panelLeft + 4, row.y + 6, COLOR_LABEL, false);

            Component value;
            int color;
            if (listening == row.action) {
                value = Component.translatable("screen.kurumamod.controller.listening");
                color = COLOR_LISTENING;
            } else {
                value = describe(row.action);
                color = COLOR_VALUE;
            }
            graphics.drawString(font, value, panelLeft + 84, row.y + 6, color, false);
        }

        graphics.drawCenteredString(font,
                Component.translatable("screen.kurumamod.controller.hint"),
                width / 2, LIST_TOP + listHeight + 36, COLOR_HINT);
    }

    /** 割り当てと、今の値を並べて出す。動かせばその場で確かめられる。 */
    private Component describe(ControllerAction action) {
        ControllerBinding binding = ControllerInput.binding(action);
        if (!binding.isBound()) {
            return Component.translatable("screen.kurumamod.controller.unbound");
        }
        String where = binding.type() == ControllerBinding.Type.AXIS
                ? Component.translatable("screen.kurumamod.controller.axis", binding.index()).getString()
                : Component.translatable("screen.kurumamod.controller.button", binding.index()).getString();
        return Component.literal(String.format("%s  %+.2f", where, ControllerInput.value(action)));
    }

    /** 割り当て画面では車が動かないよう、ゲームを止める。 */
    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private record Row(ControllerAction action, int y, Button assign, Button invert) {
    }
}
