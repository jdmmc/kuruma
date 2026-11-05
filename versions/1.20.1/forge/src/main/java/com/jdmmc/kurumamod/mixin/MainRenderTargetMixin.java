package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ミラーのために世界を描いている間だけ、{@link Minecraft#getMainRenderTarget()} がミラーの描き先を返す。
 *
 * <p>描画の途中で「画面の描き先」へつなぎ直す所（バニラの輪郭の枝、Iris など）がどれも、この答えを使って
 * つなぎ直すので、ここで差し替えれば行き先がミラーになる。差し替えないと、ミラー用の地形や物が画面の描き先へ
 * 流れ出る。詳しくは {@link MirrorRenderer#drawingTarget()}。</p>
 */
@Mixin(Minecraft.class)
public abstract class MainRenderTargetMixin {

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void kuruma$mirrorTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = MirrorRenderer.drawingTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
