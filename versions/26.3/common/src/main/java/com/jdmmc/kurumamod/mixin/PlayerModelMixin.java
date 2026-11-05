package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.SteeringHands;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 運転者の腕をハンドルへ向ける（三人称）。姿勢を決め終えた<b>最後</b>に上書きする。
 *
 * <p>26.3 では {@code setupAnim} は状態（{@code HumanoidRenderState}）しか受け取らないので、腕の向きは
 * 状態を集める段で決めておき（{@code RiderPoseRenderer#onExtract}）、ここでは状態から引いて入れるだけ。
 * 人型のモデル全部（{@code HumanoidModel}）に掛けるので、プレイヤーのモデルだけでなく防具の袖も一緒に向く。
 * 袖は腕の子なので、腕を向ければ付いてくる。</p>
 *
 * <p><b>{@code @Shadow} でモデルのフィールドを引かないこと。</b>Forge 版の refmap にフィールドの対応が出ず、
 * 配布 jar で見つからなくなる。</p>
 */
@Mixin(HumanoidModel.class)
public abstract class PlayerModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void kurumamod$holdSteeringWheel(HumanoidRenderState state, CallbackInfo ci) {
        SteeringHands.poseModel((HumanoidModel<?>) (Object) this, state);
    }
}
