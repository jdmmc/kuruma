package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * この MOD が使う {@link RenderType}。
 *
 * <p><b>{@code RenderType} を継承しているのは、{@code POSITION_COLOR_SHADER} や
 * {@code COLOR_WRITE} といった状態が {@code RenderStateShard} の protected フィールドで、
 * 外からは触れないため。</b>インスタンスは作らない。</p>
 */
public final class KurumaRenderTypes extends RenderType {

    /**
     * 半透明の板を世界に重ねるためのもの。コースのゲートと、ヘッドライトの光の筋が使う。
     *
     * <p><b>バニラの {@code RenderType.debugQuads()} を使ってはいけない。</b>
     * 見た目の設定（POSITION_COLOR・半透明・カリング無し）はこれと同じだが、
     * <b>深度バッファへ書き込む</b>——{@code CompositeStateBuilder} の既定が
     * {@code COLOR_DEPTH_WRITE} で、{@code debug_quads} はそれを上書きしていない。</p>
     *
     * <p>半透明なのに深度を書くと、<b>後から描くものが「手前に何かある」と判定されて弾かれる</b>。
     * 実際に出た症状:</p>
     *
     * <ul>
     *   <li><b>ゲートとヘッドライトの光の筋が競合する。</b>筋は車の描画（エンティティの段）で
     *       14m ぶんの深度を書き、ゲートはその後（{@code AFTER_TRANSLUCENT_BLOCKS}）に描かれるので、
     *       <b>筋と重なった部分のゲートだけが消える</b></li>
     *   <li>光の筋のコーンそのものも、手前の面が奥の面を弾いて形が崩れる</li>
     *   <li>ゲート同士が重なると奥のゲートが抜ける</li>
     * </ul>
     *
     * <p>{@code COLOR_WRITE} は「色だけ書いて深度は書かない」。深度<b>テスト</b>は
     * {@code LEQUAL_DEPTH_TEST} で残してあるので、壁の向こうへ突き抜けて見えることはない。</p>
     */
    public static final RenderType TRANSLUCENT_OVERLAY = create(
            "kurumamod_translucent_overlay",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            // 積んだ四角形を奥から並べ直す。半透明どうしが重なっても順序が合う。
            // 頂点はカメラ相対で積んでいるので、既定の並べ替え原点（0,0,0）がそのままカメラになる
            true,
            CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * HUD のメーターを描くためのもの。<b>バニラの {@code RenderType.gui()} との違いはカリングだけ。</b>
     *
     * <p>gui はカリング有りなので、四角形を積む向きを間違えると<b>何も描かれない</b>。
     * 円弧のセグメントは角度によって向きが変わるように見えて紛らわしいうえ、
     * 表裏を間違えても「消える」としか出ないので原因が分かりにくい。切っておく。</p>
     *
     * <p>深度を書かないのは HUD だから。{@code GuiGraphics} が最後に描くので、
     * 後ろに何かが回り込むことはない。</p>
     */
    public static final RenderType GUI_SHAPE = create(
            "kurumamod_gui_shape",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    private KurumaRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        throw new UnsupportedOperationException("定数を置くためだけのクラス");
    }
}
