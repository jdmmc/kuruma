package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.CameraAngles;
import com.jdmmc.kurumamod.client.hook.ClientHooks;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * カメラの向き（NeoForge の {@code ViewportEvent.ComputeCameraAngles} にあたる場面）。
 *
 * <p>NeoForge と同じく、{@code Camera#alignWithEntity} で乗っている実体の向きにカメラを回した直後（位置を決める前）に
 * {@link ClientHooks} を呼び、書き換えられた向きで回し直す。<b>roll（画面の傾き）は素の Minecraft には無い</b>
 * ので、NeoForge の {@code Camera#setRotation(yaw, pitch, roll)} と同じ計算でここで足す。後ろ向きの三人称
 * （{@code isMirrored}）は、NeoForge と同じく roll を逆にする。</p>
 *
 * <p>{@code alignWithEntity} の中の {@code setRotation} は 3 回。0 番目はトロッコ（新しい動き）に乗っているとき、
 * 1 番目がふつう、2 番目が後ろ向きの三人称。NeoForge もトロッコのときはイベントを飛ばさない。</p>
 */
@Mixin(Camera.class)
public abstract class CameraSetupMixin {

    @Shadow
    private float xRot;
    @Shadow
    private float yRot;
    @Shadow
    @Final
    private Quaternionf rotation;
    @Shadow
    @Final
    private Vector3f forwards;
    @Shadow
    @Final
    private Vector3f up;
    @Shadow
    @Final
    private Vector3f left;
    @Shadow
    @Final
    private static Vector3fc FORWARDS;
    @Shadow
    @Final
    private static Vector3fc UP;
    @Shadow
    @Final
    private static Vector3fc LEFT;
    @Shadow
    private int matrixPropertiesDirty;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /** この回で決まった roll。後ろ向きの三人称で逆にするために覚えておく。 */
    @Unique
    private float kuruma$roll;

    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1, shift = At.Shift.AFTER))
    private void kuruma$computeAngles(float partialTick, CallbackInfo ci) {
        CameraAngles angles = new CameraAngles((Camera) (Object) this, partialTick, yRot, xRot, 0.0F);
        ClientHooks.computeCameraAnglesNormal(angles);
        ClientHooks.computeCameraAnglesLow(angles);
        kuruma$roll = angles.getRoll();
        kuruma$rotate(angles.getYaw(), angles.getPitch(), kuruma$roll);
    }

    /** 後ろ向きの三人称。バニラが yaw + 180・-pitch で回し直した後に、roll も逆にして足す（NeoForge と同じ）。 */
    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 2, shift = At.Shift.AFTER))
    private void kuruma$mirrorRoll(float partialTick, CallbackInfo ci) {
        if (kuruma$roll != 0.0F) {
            kuruma$rotate(yRot, xRot, -kuruma$roll);
        }
    }

    /** NeoForge の {@code Camera#setRotation(float, float, float)} と同じ計算。roll が 0 ならバニラのまま。 */
    @Unique
    private void kuruma$rotate(float yaw, float pitch, float roll) {
        if (roll == 0.0F) {
            setRotation(yaw, pitch);
            return;
        }
        xRot = pitch;
        yRot = yaw;
        float toRad = (float) (Math.PI / 180.0);
        rotation.rotationYXZ((float) Math.PI - yaw * toRad, -pitch * toRad, -roll * toRad);
        FORWARDS.rotate(rotation, forwards);
        UP.rotate(rotation, up);
        LEFT.rotate(rotation, left);
        // 行列の作り直しの印（バニラの setRotation と同じ。ビュー回転と、投影を掛けたものの 2 つ）
        matrixPropertiesDirty |= 3;
    }
}
