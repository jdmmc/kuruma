package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import net.minecraft.client.gui.GuiGraphics;
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
}
