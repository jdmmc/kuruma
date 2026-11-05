package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.FovModifier;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 視野の倍率（NeoForge の {@code ComputeFovModifierEvent} にあたる場面）。
 *
 * <p>NeoForge は最後の {@code return} を、「バニラの倍率に FOV 効果の設定を掛けた値（{@code Mth.lerp}）」を
 * 初期値にしたイベントに通す。ここでも同じ {@code Mth.lerp} の呼び出しを包み、その結果を初期値として渡す。
 * スコープを覗いているときの早い {@code return 0.1F} は、NeoForge と同じく通さない。</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    @WrapOperation(method = "getFieldOfViewModifier", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float kuruma$fov(float delta, float start, float end, Operation<Float> original) {
        float lerped = original.call(delta, start, end);
        FovModifier fov = new FovModifier((Player) (Object) this, end);
        fov.setNewFovModifier(lerped);
        ClientHooks.computeFov(fov);
        return fov.getNewFovModifier();
    }
}
