package com.jdmmc.kurumamod.client.hook;

import net.minecraft.client.DeltaTracker;

/**
 * 1 フレームを描き始める前。NeoForge の {@code RenderFrameEvent.Pre} と同じ名前のメソッドを持つ。
 * {@code Minecraft#runTick} で {@code GameRenderer#render} を呼ぶ直前に作られる。
 */
public final class FrameRender {

    private final DeltaTracker partialTick;

    public FrameRender(DeltaTracker partialTick) {
        this.partialTick = partialTick;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }
}
