package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * ヘッドライトの光を<b>画面の画素ごとに</b>解く仕組み（1.21.1 版）の、26.3 での受け口。
 *
 * <h2>26.3 では画素ごとの光を描いていない</h2>
 *
 * <p>1.21.1 では、ワールドを描き終えたところで画面の色と深度を OpenGL で写し取り、自前のシェーダー
 * （{@code headlight_volume.fsh}。路面の照り返しと霧の中の光線、ランプから見た影の地図）で画面いっぱいに
 * 描き足していた。26.3 は描画が {@code com.mojang.renderpearl}（OpenGL と Vulkan を切り替えられる）に
 * 変わり、写し取り・フレームバッファ・テクスチャ ID を直接触る作りがそのままでは動かない。
 * <b>作り直すまでは {@link #isActive()} が常に false</b>で、{@link CarLights} が形を積む代用
 * （ブロックの面を照らし直す路面と、加算の光の筋）で描く。光芒はどちらの場合も {@link CarLights}。</p>
 *
 * <p>作り直すときの手本は {@code versions/1.21.1/common/.../client/HeadlightVolume.java}・
 * {@code HeadlightShadows.java} と {@code core/src/main/resources/assets/kurumamod/shaders/core/headlight_*}。
 * 26.3 では、{@code LevelRenderer} の {@code executeOutline} の頭（主の描画パスが閉じた後）に割り込み、
 * 主画面の深度を {@code RenderPass#setUniform("...", 深度のビュー, サンプラー)} で読む自前の
 * {@code RenderPipeline} を流す形になる（深度は逆向き。消去値 0、手前ほど大きい）。</p>
 */
public final class HeadlightVolume {

    private static final Logger LOGGER = LogUtils.getLogger();

    private HeadlightVolume() {
    }

    /** 画素ごとの光が使えるか。<b>26.3 ではまだ作っていないので常に false</b>（{@link CarLights} が代用する）。 */
    public static boolean isActive() {
        return false;
    }

    /**
     * シェーダーパックのために光を止めているか。<b>止めるのは、パックが使われていて、かつ設定で
     * 「使用中も光を描く」を切っているときだけ</b>（{@link com.jdmmc.kurumamod.ClientConfig#headlightWithShaderPacks}）。
     *
     * <p>以前はパックが使われていれば無条件に止めていた（Oculus で描画がおかしくなったため）。
     * 2026-10-01 に Oculus 1.8.0 ＋ Embeddium で試すと、路面の照り返しも光芒も深度どおりに乗ったので、
     * 既定で描くようにした。合わないパックのときだけプレイヤーが切れる。パックが無ければ何も変わらない。</p>
     */
    public static boolean suppressedByShaderPack() {
        return !com.jdmmc.kurumamod.ClientConfig.headlightWithShaderPacks && ShaderPacks.inUse();
    }

    /**
     * シェーダーパックが<b>いま実際に使われているか</b>。
     *
     * <p>Oculus / Iris / OptiFine のシェーダーは世界の描画そのものを差し替えるので、そこへ
     * 画面を写し取って重ねると壊れる（Oculus で実際に描画がおかしくなった）。<b>MOD が
     * 入っているかではなく、パックが有効か</b>で見る——入っているだけで止めると、シェーダーを
     * 切って遊んでいる人まで光を失う。パックは遊んでいる途中に切り替えられるので毎回聞く。</p>
     *
     * <p>どれもコンパイル時には依存しないのでリフレクションで聞く。<b>聞けなかったら
     * 「使われている」とみなす</b>（壊れた画面より、控えめな代用の方がまし）。</p>
     */
    static final class ShaderPacks {
        private static boolean resolved;
        private static java.lang.reflect.Method irisInUse;
        private static java.lang.reflect.Method irisShadowPass;
        private static Object irisApi;
        private static java.lang.reflect.Method optifineInUse;
        /** 聞き方が分からなかった（API が変わった）。安全側に倒す。 */
        private static boolean unknown;

        private ShaderPacks() {
        }

        static boolean inUse() {
            if (!resolved) {
                resolve();
            }
            if (unknown) {
                return true;
            }
            boolean inUse;
            try {
                inUse = (irisInUse != null && (Boolean) irisInUse.invoke(irisApi))
                        || (optifineInUse != null && (Boolean) optifineInUse.invoke(null));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("シェーダーパックが使われているか聞けなかった。以後は使われているものとして扱う", e);
                unknown = true;
                return true;
            }
            if (last == null || last != inUse) {
                // 切り替わったときだけ残す。光が出ない・おかしいときに、どちらの描き方かを切り分けるため
                LOGGER.info(inUse
                        ? "シェーダーパックが使われている（光の効果を描くかは設定 headlightWithShaderPacks しだい）"
                        : "シェーダーパックは使われていない");
                last = inUse;
            }
            return inUse;
        }

        private static Boolean last;

        /**
         * Oculus / Iris が<b>影を描いている最中</b>か。パックは影のために光源の視点からもエンティティを
         * 描くので、その間に光芒を記録すると、光源の視点の座標が混ざって変な場所に描かれる。
         * 聞けなければ false（影のパスが無い OptiFine やパック無しと同じ扱い）。
         */
        static boolean renderingShadowPass() {
            if (!resolved) {
                resolve();
            }
            if (irisShadowPass == null) {
                return false;
            }
            try {
                return (Boolean) irisShadowPass.invoke(irisApi);
            } catch (ReflectiveOperationException | RuntimeException e) {
                irisShadowPass = null;
                return false;
            }
        }

        private static void resolve() {
            resolved = true;
            boolean iris = Platform.isModLoaded("oculus") || Platform.isModLoaded("iris");
            if (iris) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    irisInUse = api.getMethod("isShaderPackInUse");
                    irisShadowPass = api.getMethod("isRenderingShadowPass");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOGGER.warn("Oculus / Iris の API が見つからない。シェーダーパックが使われているものとして扱う", e);
                    unknown = true;
                }
            }
            try {
                // OptiFine は Forge の MOD 一覧に載らないので、クラスがあるかで見る
                Class<?> config = Class.forName("net.optifine.Config");
                optifineInUse = config.getMethod("isShaders");
            } catch (ClassNotFoundException e) {
                // OptiFine は入っていない
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("OptiFine のシェーダーの状態が聞けない。使われているものとして扱う", e);
                unknown = true;
            }
        }
    }

    /** シェーダーパックの仕上げの直前（{@code IrisPipelineMixin}）。26.3 では何も描かない。 */
    public static void renderBeforePackComposite() {
    }
}
