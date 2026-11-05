package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.GuiRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ホットバーの層を描き終えた直後（NeoForge の {@code RenderGuiLayerEvent.Post} の {@code VanillaGuiLayers.HOTBAR}
 * にあたる場面）。チャットなど後ろの層より下に描くためのもの。
 *
 * <p>NeoForge はバニラの {@code renderHotbarAndDecorations} を細かい層に分けていて、HOTBAR の層はそのうちの
 * 道具の列だけ。素の 1.21.1 にはその切れ目が無いので、ここでは {@code renderHotbarAndDecorations} の終わり
 * （体力・満腹度・経験値を描いた後）に割り込む。チャットより下になるのは同じ。</p>
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "renderHotbarAndDecorations", at = @At("RETURN"))
    private void kuruma$afterHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        ClientHooks.renderHotbarPost(new GuiRender(graphics, delta));
    }
}
