package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.CarChaseCamera;
import com.jdmmc.kurumamod.client.CarCockpitCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 三人称のカメラを {@code Camera#setup} の<b>最後</b>で車の後ろへ置き直す（{@link CarChaseCamera#placement}）。
 * 一人称では目を車体の姿勢に乗せて置く（{@link CarCockpitCamera#placement}）。
 *
 * <p><b>Forge 1.20.1 版には無い。</b>あちらは {@code ViewportEvent.ComputeCameraAngles} が
 * {@code setup} の後に飛ぶので、イベントの中で位置を置けた。NeoForge 1.21.1 ではこのイベントが
 * {@code setup} の中で位置を決める<b>前</b>に飛び、置いた位置がすぐ上書きされる。</p>
 *
 * <p><b>26.3 では {@code setup} が無くなり、{@code update(DeltaTracker)} から呼ばれる private な
 * {@code alignWithEntity(float)} が向きと位置を決める。</b>その最後に割り込む（このあと視錐台が
 * この位置で組まれるので、{@code update} の最後では遅い）。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void kurumamod$placeChaseCamera(float partialTick, CallbackInfo ci) {
        Vec3 position = CarChaseCamera.placement((Camera) (Object) this, partialTick);
        if (position == null) {
            // 一人称は目を車体の姿勢に乗せて置く
            position = CarCockpitCamera.placement((Camera) (Object) this, partialTick);
        }
        if (position != null) {
            setPosition(position);
        }
    }
}
