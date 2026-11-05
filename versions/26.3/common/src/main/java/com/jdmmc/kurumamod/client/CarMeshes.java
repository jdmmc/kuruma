package com.jdmmc.kurumamod.client;

/**
 * 車体・ホイール・タイヤの不透明な面を<b>GPU に置いたまま</b>描く仕組み（1.21.1 にあったもの）。
 *
 * <p><b>26.3 版ではまだ作っていない。</b>1.21.1 では {@code VertexBuffer} に頂点を 1 度だけ送り、
 * 自前のシェーダー（{@code kurumamod:car_mesh}）に変換の行列と明るさを渡して描いていた。26.3 の描画
 * （{@code com.mojang.renderpearl}）には {@code VertexBuffer} も {@code ShaderInstance} も無く、
 * 描画は「集めてから段ごとに流す」形（{@code SubmitNodeCollector}）になったので、作り直すには
 * 頂点のバッファ・バインドグループ・パイプラインを自分で持つ必要がある。</p>
 *
 * <p>それまでは<b>いつも頂点を積む描き方</b>（{@link ObjModel#render} を {@code submitCustomGeometry} の中で）
 * で描く。見た目は同じで、台数が多いと CPU が重くなるだけ。</p>
 */
public final class CarMeshes {

    private CarMeshes() {
    }

    /** GPU に置いた描き方を使えるか。26.3 版ではいつも false（頂点を積んで描く）。 */
    static boolean enabled() {
        return false;
    }

    public static final class Registration {
        private Registration() {
        }

        /**
         * 1.21.1 ではここでシェーダーを読んでいた。26.3 版では何もしない。
         * 呼ぶ側（{@code ClientHooks}）の型に縛られないよう、引数は {@code Object} で受ける。
         */
        public static void onRegisterShaders(Object event) {
            // 自前のシェーダーは使っていない
        }
    }
}
