package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * アイテムの特別な描画の種類を足す（{@link ClientHooks#registerSpecialModelRenderers}）。Fabric API には受け口が無いので、
 * 素の Minecraft が自分の種類を登録し終えたところに割り込む（NeoForge 版は {@code RegisterSpecialModelRendererEvent}）。
 */
@Mixin(SpecialModelRenderers.class)
public abstract class SpecialModelRenderersMixin {

    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void kuruma$register(CallbackInfo ci) {
        ClientHooks.registerSpecialModelRenderers(SpecialModelRenderers.ID_MAPPER::put);
    }
}
