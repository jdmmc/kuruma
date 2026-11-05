package com.jdmmc.kurumamod.client.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;

/**
 * 生き物（プレイヤーを含む）を 1 体描く前後。NeoForge の {@code RenderLivingEvent} と同じ名前のメソッドを持つ。
 * {@code LivingEntityRenderer#render} の先頭（Pre）と終わり（Post）で作られる。
 */
public final class LivingRender {

    private final LivingEntity entity;
    private final float partialTick;
    private final PoseStack poseStack;

    public LivingRender(LivingEntity entity, float partialTick, PoseStack poseStack) {
        this.entity = entity;
        this.partialTick = partialTick;
        this.poseStack = poseStack;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }
}
