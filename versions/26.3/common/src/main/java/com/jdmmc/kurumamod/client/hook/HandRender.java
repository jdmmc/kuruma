package com.jdmmc.kurumamod.client.hook;

import net.minecraft.world.InteractionHand;

/**
 * 一人称の手を 1 本描く前。NeoForge の {@code RenderHandEvent} と同じく、{@link #setCanceled(boolean)} で
 * その手を描かせないようにできる（{@code ItemInHandRenderer#submitHandsWithItems} の中で、手ごとに作られる）。
 */
public final class HandRender {

    private final InteractionHand hand;
    private boolean canceled;

    public HandRender(InteractionHand hand) {
        this.hand = hand;
    }

    /** どちらの手か。 */
    public InteractionHand getHand() {
        return hand;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
