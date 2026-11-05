package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.HandRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一人称の手を 1 本描く前（NeoForge の {@code RenderHandEvent} と同じ場所）。取り消されたらその手を描かない。
 * 26.3 の一人称の手は {@code FirstPersonHandsAndItemsRenderer#submitArmWithItem} が手ごとに出す。
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void kuruma$renderHand(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
                                   float partialTick, float xRot, InteractionHand hand, float attack, ItemStack stack,
                                   float inverseArmHeight, PoseStack pose, SubmitNodeCollector collector, int light,
                                   CallbackInfo ci) {
        HandRender event = new HandRender(hand);
        ClientHooks.renderHand(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
