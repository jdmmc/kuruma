package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.LivingRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 生き物の描画の前後（NeoForge の {@code RenderLivingEvent.Pre} / {@code Post} にあたる場面）。
 * NeoForge と同じく {@code LivingEntityRenderer#render} の先頭と終わり。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void kuruma$pre(LivingEntity entity, float yaw, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light, CallbackInfo ci) {
        ClientHooks.renderLivingPreLowest(new LivingRender(entity, partialTick, pose));
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void kuruma$post(LivingEntity entity, float yaw, float partialTick, PoseStack pose,
                             MultiBufferSource buffers, int light, CallbackInfo ci) {
        ClientHooks.renderLivingPostHighest(new LivingRender(entity, partialTick, pose));
    }
}
