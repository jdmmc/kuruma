package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.render.MirrorRenderer;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ミラーを描いている間は、組み直しの要るチャンクの記録の中心を鏡のカメラへ動かさない
 * （{@link MirrorRenderer}。{@link MirrorViewAreaMixin} と同じ理由）。
 */
@Mixin(SectionUpdateTracker.class)
public abstract class MirrorSectionTrackerMixin {

    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void kurumamod$keepCenter(SectionPos cameraSectionPos, CallbackInfo ci) {
        if (MirrorRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
