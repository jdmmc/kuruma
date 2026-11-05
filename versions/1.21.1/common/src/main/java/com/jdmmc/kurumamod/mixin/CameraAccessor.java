package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link Camera} の protected な {@code setPosition} / {@code setRotation} を外から呼ぶ口。ミラーがカメラを
 * 鏡の位置へ置くのに使う（{@code MirrorRenderer}）。
 *
 * <p><b>リフレクションで名前を引いてはいけない。</b>NeoForge は配布環境でも Mojang の名前で走るので
 * {@code "setPosition"} で引けるが、Fabric は配布環境では intermediary 名で走るので見つからない。
 * mixin の {@code @Invoker} なら、名前の対応表（refmap）を通るのでどちらでも同じに動く。</p>
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void kuruma$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void kuruma$setRotation(float yaw, float pitch);
}
