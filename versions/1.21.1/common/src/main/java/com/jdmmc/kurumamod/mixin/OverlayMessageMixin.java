package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.hud.CarGaugeHud;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * アクションバーを、メーターの中央の小窓より上へ持ち上げる（{@link CarGaugeHud#overlayMessageY}）。
 *
 * <p><b>割り込むのは {@code renderOverlayMessage} の中の {@code translate} の Y。</b>素の Minecraft は
 * 画面下端から 68px、NeoForge は体力バーの段数から決めた高さをここで渡す。どちらもこの呼び出しは
 * 1 回だけなので、渡された値を頭打ちにすれば両方で同じに効く。</p>
 */
@Mixin(Gui.class)
public abstract class OverlayMessageMixin {

    @ModifyArg(method = "renderOverlayMessage",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            index = 1)
    private float kurumamod$liftAboveGauges(float y) {
        return CarGaugeHud.overlayMessageY(y);
    }
}
