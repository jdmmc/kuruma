package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.joml.Matrix3x2f;

/**
 * core の {@link UiGraphics} を Minecraft 26.3 の {@link GuiGraphicsExtractor} で描く。
 *
 * <p>26.3 の {@code pose()} は 2D の行列（{@code Matrix3x2fStack}）で、Z が無い。重なりは描いた順で決まる。</p>
 */
public final class McUiGraphics extends UiGraphics {

    final GuiGraphicsExtractor graphics;
    /** いま描いている部品（ボタンの字を流すのに要る）。部品の外では null。 */
    private final AbstractWidget widget;

    public McUiGraphics(GuiGraphicsExtractor graphics) {
        this(graphics, null);
    }

    McUiGraphics(GuiGraphicsExtractor graphics, AbstractWidget widget) {
        this.graphics = graphics;
        this.widget = widget;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    @Override
    public void drawString(UiFont font, String text, int x, int y, int color, boolean shadow) {
        graphics.text(McUiFont.font(), text, x, y, color, shadow);
    }

    @Override
    public void drawString(UiFont font, RaceText text, int x, int y, int color, boolean shadow) {
        graphics.text(McUiFont.font(), RaceTexts.of(text), x, y, color, shadow);
    }

    @Override
    public void renderOutline(int x, int y, int width, int height, int color) {
        graphics.outline(x, y, width, height, color);
    }

    @Override
    public void enableScissor(int x0, int y0, int x1, int y1) {
        graphics.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void disableScissor() {
        graphics.disableScissor();
    }

    @Override
    public void pushPose() {
        graphics.pose().pushMatrix();
    }

    @Override
    public void popPose() {
        graphics.pose().popMatrix();
    }

    @Override
    public void translate(float x, float y) {
        graphics.pose().translate(x, y);
    }

    @Override
    public void scale(float x, float y) {
        graphics.pose().scale(x, y);
    }

    @Override
    public void shearX(float slant) {
        // x' = x - slant·y（1.21.1 の Matrix4f#m10 と同じ位置の成分）
        graphics.pose().mul(new Matrix3x2f(1.0F, 0.0F, -slant, 1.0F, 0.0F, 0.0F));
    }

    @Override
    public void renderScrollingString(UiFont font, RaceText text, int left, int top, int right, int bottom, int color) {
        if (widget == null) {
            drawString(font, text, left, top + (bottom - top - 8) / 2, color, false);
            return;
        }
        // 26.3 は字を流す口が部品に付いている（色は部品の状態で決まる）
        graphics.textRendererForWidget(widget, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                .acceptScrollingWithDefaultCenter(RaceTexts.of(text), left, right, top, bottom);
    }
}
