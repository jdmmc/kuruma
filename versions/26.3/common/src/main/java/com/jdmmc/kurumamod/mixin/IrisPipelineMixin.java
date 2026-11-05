package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.HeadlightVolume;
import com.jdmmc.kurumamod.client.TireSmokeVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * シェーダーパック（Iris）が仕上げ（composite）を流す<b>直前</b>の割り込み口（1.21.1 では、ここでヘッドライトの光と
 * 煙をパックの絵へ入れていた）。
 *
 * <p><b>26.3 の Iris は無い前提</b>で、割り込んだ先の処理もいまは何も描かない（{@link HeadlightVolume}・
 * {@link TireSmokeVolume} を参照）。Iris が無ければ割り込み先のクラスが無いので何もしない（{@code @Pseudo}）。
 * あっても中身が変わっていれば割り込まずに済ませる（{@code require = 0}）。どちらでも読み込みで落ちない。</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {

    @Inject(method = "finalizeLevelRendering", require = 0, remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void kurumamod$headlightsBeforeComposite(CallbackInfo ci) {
        HeadlightVolume.renderBeforePackComposite();
        TireSmokeVolume.renderBeforePackComposite();
    }
}
