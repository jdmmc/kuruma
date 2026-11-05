package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.hook.LevelStageHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * シェーダーパック（Iris）が仕上げ（composite）を流す<b>直前</b>の割り込み口
 * （{@link LevelStageHooks#beforePackComposite()}）。ここでパックの仕上げ前の絵へタイヤ痕・ヘッドライトの路面・
 * 煙・ゲートを入れる（1.21.1 と同じ位置）。
 *
 * <p>Iris 26.3（1.11.7）の {@code IrisRenderingPipeline#finalizeLevelRendering} は
 * {@code compositeRenderer.renderAll()} → {@code finalPassRenderer.renderFinalPass()} の順で、
 * {@code LevelRenderer#render} の終わり近く（{@code popMatrix} の前）から呼ばれる（jar のバイトコードで確認）。
 * KurumaMod の {@code levelEnd}（{@code render} の TAIL）はその後なので、そこでは仕上がった絵に重ねることになる。</p>
 *
 * <p>Iris が無ければ割り込み先のクラスが無いので何もしない（{@code @Pseudo}）。中身が変わって割り込めなくても
 * 読み込みで落ちない（{@code require = 0}）。そのときは {@code levelEnd} が仕上がった絵へ全部を重ねる。
 * NeoForge・Forge でも同じ mixin が読まれるが、Iris が無ければ何も起きない。</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {

    @Inject(method = "finalizeLevelRendering", require = 0, remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void kurumamod$beforePackComposite(CallbackInfo ci) {
        LevelStageHooks.beforePackComposite();
    }
}
