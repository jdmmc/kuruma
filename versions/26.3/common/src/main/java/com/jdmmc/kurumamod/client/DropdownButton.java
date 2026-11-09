package com.jdmmc.kurumamod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * ドロップダウンを開くボタン（「トラック ▼」「クラス ▼」）。<b>「▼」は右端に固定し、名前だけをその左で流す。</b>
 *
 * <p>名前の後ろに「▼」を付けた字にすると、長い名前を流している間は「▼」も一緒に流れて見えなくなる
 * （狭い画面ではほとんどの時間見えない）。</p>
 */
final class DropdownButton extends Button {

    /** 右端の「▼」の列の幅。 */
    private static final int ARROW = 12;

    private final BooleanSupplier open;

    DropdownButton(int x, int y, int width, int height, BooleanSupplier open, OnPress onPress) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.open = open;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(graphics);
        int right = getX() + getWidth() - ARROW - 2;
        ActiveTextCollector text = graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE);
        text.acceptScrollingWithDefaultCenter(getMessage(), getX() + 4, right, getY(), getY() + getHeight());
        var font = Minecraft.getInstance().font;
        String arrow = open.getAsBoolean() ? "▲" : "▼";
        graphics.text(font, arrow, getX() + getWidth() - ARROW + (ARROW - font.width(arrow)) / 2 - 2,
                getY() + (getHeight() - 8) / 2, active ? 0xFFFFFFFF : 0xFFA0A0A0, false);
    }
}
