package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.HandRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一人称の手（NeoForge の {@code RenderHandEvent} にあたる場面）。NeoForge と同じく、手ごとに
 * {@code renderArmWithItem} を呼ぶ手前で聞き、止められたらその手を描かない。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void kuruma$renderHand(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                                   float swingProgress, ItemStack stack, float equipProgress, PoseStack pose,
                                   MultiBufferSource buffers, int light, CallbackInfo ci) {
        HandRender event = new HandRender();
        ClientHooks.renderHand(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
