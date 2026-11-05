package com.jdmmc.kurumamod.client.hook;

import net.minecraft.world.entity.player.Player;

/**
 * 視野の倍率を決める場面。NeoForge の {@code ComputeFovModifierEvent} と同じ名前のメソッドを持つ。
 * {@code AbstractClientPlayer#getFieldOfViewModifier} が返す直前に作られ、ハンドラが書き換えた倍率が返る。
 */
public final class FovModifier {

    private final Player player;
    private final float fovModifier;
    private float newFovModifier;

    public FovModifier(Player player, float fovModifier) {
        this.player = player;
        this.fovModifier = fovModifier;
        this.newFovModifier = fovModifier;
    }

    public Player getPlayer() {
        return player;
    }

    /** バニラが決めた倍率（ハンドラが書き換える前）。 */
    public float getFovModifier() {
        return fovModifier;
    }

    public float getNewFovModifier() {
        return newFovModifier;
    }

    public void setNewFovModifier(float newFovModifier) {
        this.newFovModifier = newFovModifier;
    }
}
