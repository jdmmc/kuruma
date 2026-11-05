package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 空を鏡の描き先へ描かせる（{@link MirrorRenderer}）。
 *
 * <p>{@code SkyRenderer} は<b>作られたときの描き先（画面）を持ち続けて</b>そこへ描くので、
 * {@code mainRenderTarget()} を差し替えても空だけは画面へ流れ、鏡には霧の色しか残らない。
 * 描くときに引く描き先を {@link MirrorRenderer#skyTarget} に通す。鏡を描いている最中に空が作り直された
 * ときは鏡の描き先を持ってしまうので、そのときも画面へ戻す。</p>
 */
@Mixin(SkyRenderer.class)
public abstract class MirrorSkyRendererMixin {

    @WrapOperation(method = "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getColorTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
    private GpuTextureView kurumamod$skyColor(RenderTarget target, Operation<GpuTextureView> original) {
        return original.call(MirrorRenderer.skyTarget(target));
    }

    @WrapOperation(method = "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getDepthTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
    private GpuTextureView kurumamod$skyDepth(RenderTarget target, Operation<GpuTextureView> original) {
        return original.call(MirrorRenderer.skyTarget(target));
    }
}
