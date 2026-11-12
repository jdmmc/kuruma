package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import net.minecraft.client.gui.GuiGraphics;
import java.util.List;
import org.joml.Matrix4f;

/** core の {@link UiGraphics} を Minecraft の {@link GuiGraphics} で描く。 */
public final class McUiGraphics extends UiGraphics {

    final GuiGraphics graphics;

    public McUiGraphics(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y1, color);
    }

    @Override
    public void drawString(UiFont font, String text, int x, int y, int color, boolean shadow) {
        graphics.drawString(McUiFont.font(), text, x, y, color, shadow);
    }

    @Override
    public void drawString(UiFont font, RaceText text, int x, int y, int color, boolean shadow) {
        graphics.drawString(McUiFont.font(), RaceTexts.of(text), x, y, color, shadow);
    }

    @Override
    public void renderOutline(int x, int y, int width, int height, int color) {
        graphics.renderOutline(x, y, width, height, color);
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
        graphics.pose().pushPose();
    }

    @Override
    public void popPose() {
        graphics.pose().popPose();
    }

    @Override
    public void translate(float x, float y) {
        graphics.pose().translate(x, y, 0.0F);
    }

    @Override
    public void translateZ(float z) {
        graphics.pose().translate(0.0F, 0.0F, z);
    }

    @Override
    public void scale(float x, float y) {
        graphics.pose().scale(x, y, 1.0F);
    }

    @Override
    public void shearX(float slant) {
        Matrix4f shear = new Matrix4f();
        shear.m10(-slant);
        graphics.pose().last().pose().mul(shear);
    }

    @Override
    public void renderScrollingString(UiFont font, RaceText text, int left, int top, int right, int bottom, int color) {
        McUiScreen.WidgetButton.scrolling(graphics, RaceTexts.of(text), left, top, right, bottom, color);
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
        graphics.blit(McIds.mc(texture), x, y, width, height, u, v, uWidth, vHeight, textureWidth, textureHeight);
    }

    @Override
    public void renderTooltip(UiFont font, List<RaceText> lines, int mouseX, int mouseY) {
        graphics.renderTooltip(McUiFont.font(),
                lines.stream().map(line -> RaceTexts.of(line).getVisualOrderText()).toList(), mouseX, mouseY);
    }
}
