package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.render.CarMeshes;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 不透明なエンティティを描く段の終わりで、GPU に置いた車を描く（{@link CarMeshes}）。 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class CarMeshFeatureMixin {

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void kurumamod$drawCarMeshes(RenderPass renderPass, CallbackInfo ci) {
        CarMeshes.flush(renderPass);
    }
}
