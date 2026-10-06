package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.CarSkidMarks;
import com.jdmmc.kurumamod.client.HeadlightVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * シェーダーパック（Oculus / Iris）が仕上げ（composite）を流す<b>直前</b>に、ヘッドライトの光を
 * パックの絵へ入れる（{@link HeadlightVolume#renderBeforePackComposite()}）。
 *
 * <p>Forge の {@code AFTER_LEVEL} は仕上げの後に飛ぶので、そこで描くと霧・トーンマップ済みの絵を
 * 明るくするしかなく、ブロックの色が褪せる。ここなら仕上げ前の絵（ブロックの色が残っている）に
 * 光を入れ、パックに一度だけ仕上げてもらえる。</p>
 *
 * <p>Oculus / Iris が無ければ割り込み先が無いので何もしない（{@code @Pseudo}・{@code require = 0}）。
 * 中身が変わって割り込めなくなっても、光は {@code AFTER_LEVEL} で今までどおり描かれる。</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {

    @Inject(method = "finalizeLevelRendering", require = 0, remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void kurumamod$headlightsBeforeComposite(CallbackInfo ci) {
        // タイヤ痕を先に入れる。光がその上から路面を照らす
        CarSkidMarks.renderBeforePackComposite();
        HeadlightVolume.renderBeforePackComposite();
    }
}
