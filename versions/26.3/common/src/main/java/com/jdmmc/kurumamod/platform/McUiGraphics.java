package com.jdmmc.kurumamod.platform;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import java.util.List;
import org.joml.Matrix3x2f;
import com.jdmmc.kurumamod.render.GaugePainter;

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

    /**
     * 不透明度の無い色（{@code 0xFFFFFF}）を不透明にする。1.21.1 までの {@code Font} は自分でそうしていたが、
     * 26.3 はそのまま描くので見えなくなる。core の画面は 1.21.1 の書き方のまま。
     */
    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    @Override
    public void drawString(UiFont font, String text, int x, int y, int color, boolean shadow) {
        graphics.text(McUiFont.font(), text, x, y, opaque(color), shadow);
    }

    @Override
    public void drawString(UiFont font, RaceText text, int x, int y, int color, boolean shadow) {
        graphics.text(McUiFont.font(), RaceTexts.of(text), x, y, opaque(color), shadow);
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

    @Override
    public void fillQuad(int color, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        GaugePainter.quad(graphics, color, x0, y0, x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void flush() {
        GaugePainter.flush(graphics);
    }

    @Override
    public void blit(CarId texture, int x, int y, int width, int height, float u, float v,
                     int uWidth, int vHeight, int textureWidth, int textureHeight) {
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, McIds.mc(texture), x, y, u, v,
                width, height, uWidth, vHeight, textureWidth, textureHeight);
    }

    @Override
    public void renderTooltip(UiFont font, List<RaceText> lines, int mouseX, int mouseY) {
        graphics.setTooltipForNextFrame(McUiFont.font(),
                lines.stream().map(line -> RaceTexts.of(line).getVisualOrderText()).toList(), mouseX, mouseY);
    }
}
