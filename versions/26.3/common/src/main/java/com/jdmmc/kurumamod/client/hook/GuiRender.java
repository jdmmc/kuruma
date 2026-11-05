package com.jdmmc.kurumamod.client.hook;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 画面（HUD）を描く場面。NeoForge の {@code RenderGuiEvent} / {@code RenderGuiLayerEvent} と同じ名前のメソッドを持つ。
 */
public final class GuiRender {

    private final GuiGraphicsExtractor guiGraphics;
    private final DeltaTracker partialTick;

    public GuiRender(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }
}
