package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.render.MirrorRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ミラーを描いている間は、チャンクの区画の並び（{@code ViewArea}）の中心を鏡のカメラへ動かさない
 * （{@link MirrorRenderer}）。
 *
 * <p>中心を動かすと、描画距離の端の列が付け替えられて組み直しになり、遮蔽グラフも捨てられる。
 * 目と鏡が 16 ブロックの区画の境を挟んでいる間は、本編と鏡で毎フレーム行ったり来たりして、
 * 遠くの端のチャンクが消えたり出たりする。鏡は本編の並びのまま描く。</p>
 */
@Mixin(ViewArea.class)
public abstract class MirrorViewAreaMixin {

    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void kurumamod$keepCenter(SectionPos cameraSectionPos, CallbackInfoReturnable<Boolean> cir) {
        if (MirrorRenderer.isRendering()) {
            cir.setReturnValue(false);
        }
    }
}
