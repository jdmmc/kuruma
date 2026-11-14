package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** アクションバーをこの MOD の HUD より手前に描き直すための口（{@code OverlayMessageFront}）。 */
@Mixin(Hud.class)
public interface HudAccessor {

    @Invoker("extractOverlayMessage")
    void kurumamod$extractOverlayMessage(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
