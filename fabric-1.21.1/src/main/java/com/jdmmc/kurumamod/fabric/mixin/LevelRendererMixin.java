package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.LevelRenderStage;
import com.jdmmc.kurumamod.fabric.FabricLevelStages;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ワールドの描画の段のうち、{@code LevelRenderer} の中にあるもの（{@link LevelRenderStage}）。
 *
 * <p><b>Fabric API の {@code WorldRenderEvents} は使わない。</b>{@code AFTER_TRANSLUCENT} はパーティクルの後で、
 * NeoForge の {@code AFTER_TRANSLUCENT_BLOCKS} とずれるため。NeoForge が割り込んでいるのと同じ位置に割り込む。</p>
 * <ul>
 *   <li>{@code AFTER_SKY} … {@code renderLevel} で {@code renderSky} を呼んだ直後</li>
 *   <li>{@code AFTER_TRANSLUCENT_BLOCKS} / {@code AFTER_TRIPWIRE_BLOCKS} … {@code renderSectionLayer} の終わり、
 *       {@code clearRenderState()} の手前（NeoForge の {@code ClientHooks.dispatchRenderStage(RenderType, ...)} と同じ）</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"))
    private void kuruma$afterSky(LevelRenderer self, Matrix4f modelView, Matrix4f projection, float partialTick,
                                 Camera camera, boolean thickFog, Runnable setupFog, Operation<Void> original) {
        original.call(self, modelView, projection, partialTick, camera, thickFog, setupFog);
        FabricLevelStages.fire(LevelRenderStage.Stage.AFTER_SKY, modelView, projection, camera);
    }

    @Inject(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;clearRenderState()V"))
    private void kuruma$afterLayer(RenderType type, double x, double y, double z, Matrix4f modelView,
                                   Matrix4f projection, CallbackInfo ci) {
        LevelRenderStage.Stage stage;
        if (type == RenderType.translucent()) {
            stage = LevelRenderStage.Stage.AFTER_TRANSLUCENT_BLOCKS;
        } else if (type == RenderType.tripwire()) {
            stage = LevelRenderStage.Stage.AFTER_TRIPWIRE_BLOCKS;
        } else {
            return;
        }
        FabricLevelStages.fire(stage, modelView, projection, Minecraft.getInstance().gameRenderer.getMainCamera());
    }
}
