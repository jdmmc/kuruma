package com.jdmmc.kurumamod.client.hook;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * 描画のパイプライン（シェーダーと描き方の組）の登録口。NeoForge 版は {@code RegisterRenderPipelinesEvent} を、
 * ほかのローダーは手元の受け口を、この形に包んで渡す。
 *
 * <p><b>26.3 ではシェーダーを自分で読まない。</b>シェーダーの本文は {@code assets/<ns>/shaders/} から
 * Minecraft の {@code ShaderManager} が読み、パイプライン（{@link RenderPipeline}）が名前で引く。
 * 登録しなかったパイプラインも初めて使うときにその場でコンパイルされるので、ここは
 * 読み込み画面のうちにコンパイルを済ませておくためのもの（受け口の無いローダーは何もしなくてよい）。</p>
 */
@FunctionalInterface
public interface ShaderRegistrar {

    /** パイプラインを 1 つ登録する。 */
    void registerPipeline(RenderPipeline pipeline);
}
