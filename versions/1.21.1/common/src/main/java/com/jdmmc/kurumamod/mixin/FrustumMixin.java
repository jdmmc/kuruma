package com.jdmmc.kurumamod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link Frustum#offsetToFullyIncludeCameraCube} が抜けられなくなるのを止める安全網。
 *
 * <p>このメソッドは、カメラを囲む箱が視錐台にすっぽり入るまで、カメラを視線の逆へ 4 ブロックずつ下げ続ける。
 * <b>行列が NaN を含む、視線の向きが 0、箱が遠くの面より先にしか入らない</b>といったときは条件を満たせず、
 * 描画のスレッドがそこで回り続けて画面が固まる（ミラーの景色を描いている最中に Fabric 版で実際に起きた）。
 * 普通の描画では数回で抜けるので、{@value #MAX_STEPS} 回を超えたら諦めて抜け、そのときの値を 1 回だけ記録する。</p>
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin {

    @Unique
    private static final Logger KURUMA$LOGGER = LogUtils.getLogger();
    /** これだけ下げても入らなければ、もう入らない。普通は数回、狭い画角のミラーでも 100 回に届かない。 */
    @Unique
    private static final int MAX_STEPS = 1000;
    @Unique
    private static boolean kuruma$reported;

    @Shadow
    private double camX;
    @Shadow
    private double camY;
    @Shadow
    private double camZ;
    @Shadow
    private Vector4f viewVector;
    @Shadow
    @Final
    private Matrix4f matrix;

    @Unique
    private int kuruma$steps;
    @Unique
    private double kuruma$startX;
    @Unique
    private double kuruma$startY;
    @Unique
    private double kuruma$startZ;

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"))
    private void kuruma$start(int size, CallbackInfoReturnable<Frustum> cir) {
        kuruma$steps = 0;
        kuruma$startX = camX;
        kuruma$startY = camY;
        kuruma$startZ = camZ;
    }

    @WrapOperation(method = "offsetToFullyIncludeCameraCube",
            // JOML は難読化されていないので、この割り込み先の名前は直さない
            at = @At(value = "INVOKE", target = "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I", remap = false))
    private int kuruma$limit(FrustumIntersection intersection, float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ, Operation<Integer> original) {
        if (++kuruma$steps <= MAX_STEPS) {
            return original.call(intersection, minX, minY, minZ, maxX, maxY, maxZ);
        }
        if (!kuruma$reported) {
            kuruma$reported = true;
            KURUMA$LOGGER.error("視錐台がカメラの箱を含められない（{} 回下げても入らない）。描画が止まらないよう打ち切った。"
                            + "カメラの位置 ({}, {}, {})、視線 {}、行列 {}。以後この記録は出さない",
                    MAX_STEPS, kuruma$startX, kuruma$startY, kuruma$startZ, viewVector, matrix);
        }
        // 位置を下げる前へ戻す（打ち切ったぶん遠くへ行ったままだと、そのフレームの描画がずれる）
        camX = kuruma$startX;
        camY = kuruma$startY;
        camZ = kuruma$startZ;
        // -2 は「すっぽり入っている」。これで繰り返しが終わる
        return FrustumIntersection.INSIDE;
    }
}
