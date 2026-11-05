package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.RiderPoseRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 車に乗っている人を描くときに、車体の傾きを行列へ掛ける（{@link RiderPoseRenderer}）。
 *
 * <p>頭で積み、どの return でも戻す（{@code RETURN} はメソッドのすべての return に入る。NeoForge が
 * {@code RenderLivingEvent.Pre} で打ち切る早い return も含む）。描画は 1 本のスレッドで入れ子にならないので、
 * 積んだかどうかは 1 つの印で足りる。</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class RiderRenderMixin {

    @Unique
    private boolean kurumamod$pushed;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    private void kurumamod$tiltRider(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                     CameraRenderState camera, CallbackInfo ci) {
        kurumamod$pushed = RiderPoseRenderer.beforeSubmit(state, pose);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"))
    private void kurumamod$untiltRider(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                       CameraRenderState camera, CallbackInfo ci) {
        if (kurumamod$pushed) {
            kurumamod$pushed = false;
            RiderPoseRenderer.afterSubmit(pose);
        }
    }
}
