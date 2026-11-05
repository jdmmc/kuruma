package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.FrameRender;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1 フレームを描き始める前（NeoForge の {@code RenderFrameEvent.Pre} と同じく、{@code GameRenderer#render} の直前）。
 *
 * <p>素の 26.3 では {@code GameRenderer#render} を呼ぶのは {@code renderFrame}（NeoForge はパッチで {@code runTick} へ
 * 畳み込んでいるので、そちらのソースを見て書くと当たらない）。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void kuruma$renderFramePre(boolean advanceGameTime, CallbackInfo ci) {
        ClientHooks.renderFramePre(new FrameRender(((Minecraft) (Object) this).getDeltaTracker()));
    }
}
