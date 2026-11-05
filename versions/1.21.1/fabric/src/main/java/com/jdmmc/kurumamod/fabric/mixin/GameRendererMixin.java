package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.LevelRenderStage;
import com.jdmmc.kurumamod.fabric.FabricLevelStages;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ワールドの描画の段の {@code AFTER_LEVEL}。NeoForge と同じく {@code GameRenderer#renderLevel} で、
 * {@code levelRenderer.renderLevel(...)} が終わった直後（手を描く前）。行列も NeoForge と同じく、
 * {@code renderLevel} に渡したカメラの回転と投影。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void kuruma$afterLevel(LevelRenderer levelRenderer, DeltaTracker delta, boolean renderBlockOutline,
                                   Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                   Matrix4f modelView, Matrix4f projection, Operation<Void> original) {
        original.call(levelRenderer, delta, renderBlockOutline, camera, gameRenderer, lightTexture, modelView, projection);
        FabricLevelStages.fire(LevelRenderStage.Stage.AFTER_LEVEL, modelView, projection, camera);
    }
}
