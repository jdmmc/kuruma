package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ミラーの景色を描く口（{@link MirrorRenderer}）。
 *
 * <ul>
 *   <li>{@code update(DeltaTracker)} の頭 … <b>1 フレームの本編の抽出（extract）より前</b>。ここで鏡のカメラから
 *       抽出と描画を 1 回ぶん済ませておけば、そのあと本編が抽出し直すので、本編の状態を壊さない。
 *       3 つのローダーとも {@code Minecraft#renderFrame} が {@code update → extract → render} の順に呼ぶ
 *       （NeoForge のパッチ入りのソースと、Fabric・Forge の jar のバイトコードで確認）。
 *       {@code RenderFrameEvent.Pre} などの「フレームの頭」のイベントは<b>抽出の後</b>に飛ぶので使えない</li>
 *   <li>{@code mainRenderTarget()} … 鏡を描いている間だけ鏡の描き先を返す。{@code LevelRenderer} は
 *       描き先をこのメソッドで引く（フレームグラフの {@code targets.main}、OIT の描き先の大きさなど）。
 *       1.21.1 の {@code MainRenderTargetMixin} の代わり</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class MirrorGameRendererMixin {

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void kurumamod$renderMirrors(DeltaTracker deltaTracker, CallbackInfo ci) {
        MirrorRenderer.beforeFrame(deltaTracker);
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void kurumamod$mirrorTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = MirrorRenderer.drawingTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
