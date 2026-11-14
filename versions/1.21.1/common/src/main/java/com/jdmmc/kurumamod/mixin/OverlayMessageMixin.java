package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.hook.OverlayMessageFront;
import com.jdmmc.kurumamod.client.hud.CarGaugeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * アクションバーを、メーターの中央の小窓より上へ持ち上げる（{@link CarGaugeHud#overlayMessageY}）。
 *
 * <p><b>割り込むのは {@code renderOverlayMessage} の中の {@code translate} の Y。</b>素の Minecraft は
 * 画面下端から 68px、NeoForge は体力バーの段数から決めた高さをここで渡す。どちらもこの呼び出しは
 * 1 回だけなので、渡された値を頭打ちにすれば両方で同じに効く。</p>
 *
 * <p><b>バニラの層では描かせない。</b>HUD を描き終えた最後に {@link OverlayMessageFront} が描き直す
 * （メーターやヘルプの下に隠れないように）。</p>
 */
@Mixin(Gui.class)
public abstract class OverlayMessageMixin {

    @ModifyArg(method = "renderOverlayMessage",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            index = 1)
    private float kurumamod$liftAboveGauges(float y) {
        return CarGaugeHud.overlayMessageY(y);
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void kurumamod$drawInFront(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!OverlayMessageFront.drawing()) {
            ci.cancel();
        }
    }
}
