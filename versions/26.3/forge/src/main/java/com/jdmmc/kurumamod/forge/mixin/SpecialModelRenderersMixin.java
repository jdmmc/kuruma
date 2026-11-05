package com.jdmmc.kurumamod.forge.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * アイテムの特別な描画の種類を足す（{@link ClientHooks#registerSpecialModelRenderers}）。
 * Forge 26.3 には受け口が無いので、素の Minecraft が自分の種類を登録し終えたところに割り込む。
 * NeoForge 版は {@code RegisterSpecialModelRendererEvent}、Fabric 版は同じ形の mixin。
 */
@Mixin(SpecialModelRenderers.class)
public abstract class SpecialModelRenderersMixin {

    @Shadow
    @Final
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> ID_MAPPER;

    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void kuruma$register(CallbackInfo ci) {
        ClientHooks.registerSpecialModelRenderers(ID_MAPPER::put);
    }
}
