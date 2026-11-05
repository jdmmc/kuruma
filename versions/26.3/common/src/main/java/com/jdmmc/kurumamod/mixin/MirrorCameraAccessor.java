package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ミラーの描画が画面のカメラを鏡の位置へ一時的に置くための口（{@code MirrorRenderer}）。
 * 位置と向きは {@link CameraAccessor} で置き、ここでは {@code update} の中でしか決まらない
 * 射影・視錐台・三人称かどうかを差し替える。描き終えたら元へ戻す。
 */
@Mixin(Camera.class)
public interface MirrorCameraAccessor {

    @Accessor("projection")
    Projection kuruma$projection();

    @Accessor("cullFrustum")
    Frustum kuruma$cullFrustum();

    @Accessor("cullFrustum")
    void kuruma$setCullFrustum(Frustum frustum);

    @Accessor("detached")
    void kuruma$setDetached(boolean detached);

    @Accessor("depthFar")
    float kuruma$depthFar();
}
