package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.RiderPoseRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 世界の生き物の状態を集め終えたところで、車に乗っている人の傾きと腕の向きを決める（{@link RiderPoseRenderer}）。
 *
 * <p>{@code extractEntity} を通るのは世界を描くときだけ。インベントリの人形は描く係から直接状態を作るので
 * ここを通らず、傾かない（1.21.1 で「世界を描いている間だけ」と見分けていたのと同じ）。</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class RiderExtractMixin {

    @Inject(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"))
    private void kurumamod$extractRider(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state != null) {
            RiderPoseRenderer.onExtract(entity, state, partialTicks);
        }
    }
}
