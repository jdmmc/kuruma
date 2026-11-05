package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link GuiGraphicsExtractor} が溜めている GUI の描画状態。メーターの円弧のような自前の形
 * （{@code GuiElementRenderState}）を足すのに使う。素の Minecraft には足す口が無い
 * （{@code submitGuiElementRenderState} は NeoForge の追加）ので、mixin で取る。
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {

    @Accessor("guiRenderState")
    GuiRenderState kuruma$getGuiRenderState();
}
