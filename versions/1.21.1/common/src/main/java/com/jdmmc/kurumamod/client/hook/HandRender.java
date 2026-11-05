package com.jdmmc.kurumamod.client.hook;

/**
 * 一人称の手を 1 本描く前。NeoForge の {@code RenderHandEvent} と同じく、{@link #setCanceled(boolean)} で
 * その手を描かせないようにできる（{@code ItemInHandRenderer#renderHandsWithItems} の中で、手ごとに作られる）。
 */
public final class HandRender {

    private boolean canceled;

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
