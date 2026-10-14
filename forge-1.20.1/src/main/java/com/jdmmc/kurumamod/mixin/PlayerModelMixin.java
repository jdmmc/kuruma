package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.SteeringHands;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 運転者の腕をハンドルへ向ける（三人称）。姿勢を決め終えた<b>最後</b>に上書きする。
 *
 * <p>イベントでは間に合わない。{@code RenderLivingEvent.Pre} は姿勢を決める前に飛ぶので、そこで腕を向けても
 * {@code setupAnim} に上書きされる。ここで腕を向ければ、防具の袖（モデルの姿勢を写す）と手に持った物
 * （腕の位置から置く）も一緒に付いてくる。</p>
 *
 * <p><b>{@code @Shadow} でモデルのフィールドを引かないこと。</b>Forge 版の refmap にフィールドの対応が出ず、
 * 配布 jar（SRG 名で走る）で見つからなくなる。細い腕かどうかはプレイヤーのスキンから引く。</p>
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin<T extends LivingEntity> {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void kurumamod$holdSteeringWheel(T entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float netHeadYaw, float headPitch, CallbackInfo ci) {
        SteeringHands.poseModel((PlayerModel<?>) (Object) this, entity, ageInTicks - entity.tickCount);
    }
}
