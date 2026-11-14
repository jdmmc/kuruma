package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.hook.OverlayMessageFront;
import com.jdmmc.kurumamod.client.hud.CarGaugeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * アクションバーを、メーターの中央の小窓より上へ持ち上げる（{@link CarGaugeHud#overlayMessageY}）。
 *
 * <p><b>割り込むのは {@code Hud#extractOverlayMessage} の中の {@code translate} の Y。</b>素の Minecraft と
 * Forge は画面下端から 68px、NeoForge は体力バーの段数から決めた高さをここで渡す。3 つとも
 * この呼び出しは 1 回だけ（それぞれの jar を javap で確認）なので、渡された値を頭打ちにすれば同じに効く。</p>
 *
 * <p><b>バニラの順番では描かせない。</b>HUD を描き終えた最後に {@link OverlayMessageFront} が描き直す
 * （26.3 の GUI は描いた順で重なり、メーターやヘルプの下に隠れていた）。</p>
 */
@Mixin(Hud.class)
public abstract class OverlayMessageMixin {

    @ModifyArg(method = "extractOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lorg/joml/Matrix3x2fStack;translate(FF)Lorg/joml/Matrix3x2f;"),
            index = 1)
    private float kurumamod$liftAboveGauges(float y) {
        return CarGaugeHud.overlayMessageY(y);
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void kurumamod$drawInFront(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!OverlayMessageFront.drawing()) {
            ci.cancel();
        }
    }
}
