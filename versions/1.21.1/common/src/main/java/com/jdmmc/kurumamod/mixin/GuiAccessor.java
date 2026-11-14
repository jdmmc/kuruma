package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** アクションバーをこの MOD の HUD より手前に描き直すための口（{@code OverlayMessageFront}）。 */
@Mixin(Gui.class)
public interface GuiAccessor {

    @Invoker("renderOverlayMessage")
    void kurumamod$renderOverlayMessage(GuiGraphics guiGraphics, DeltaTracker deltaTracker);
}
