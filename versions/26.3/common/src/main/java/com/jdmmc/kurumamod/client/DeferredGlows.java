package com.jdmmc.kurumamod.client;

/**
 * シェーダーパック使用中の光芒の描き分け（1.21.1 版）の名残。<b>26.3 では描き分けない。</b>
 *
 * <p>1.21.1 では、シェーダーパック（Oculus / Iris）が加算の {@code RenderType} を自分のプログラムに
 * 差し替えて光芒が灰色の板に化けたので、ワールドを描き終えた後に自前のシェーダーで足し直していた。
 * 26.3 の Iris は無い前提なので、光芒もタイヤ痕もいつもの {@link EffectRenderTypes} で描く。
 * パックが出てきたら、1.21.1 の {@code versions/1.21.1/common/.../client/DeferredGlows.java} を手本に作り直す。</p>
 */
public final class DeferredGlows {

    private DeferredGlows() {
    }

    /** 光を描かずに捨てる場面か（パックが影を描いている最中）。 */
    static boolean skip() {
        return HeadlightVolume.ShaderPacks.renderingShadowPass();
    }
}
