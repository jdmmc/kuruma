package com.jdmmc.kurumamod.client.hook;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 画面（HUD）を描く場面。NeoForge の {@code RenderGuiEvent} / {@code RenderGuiLayerEvent} と同じ名前のメソッドを持つ。
 */
public final class GuiRender {

    private final GuiGraphics guiGraphics;
    private final DeltaTracker partialTick;

    public GuiRender(GuiGraphics guiGraphics, DeltaTracker partialTick) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }
}
