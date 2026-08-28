package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * 車のガラス。<b>バニラの {@code entityTranslucent} を使ってはいけない。深度を書く。</b>
     *
     * <p>半透明なのに深度を書くと、<b>その後に描かれるものが「手前に何かある」と判定されて
     * 弾かれる</b>。ゲートと光の筋で踏んだのと同じ地雷（{@link #TRANSLUCENT_OVERLAY}）だが、
     * ガラスは画面を大きく覆うぶん被害が桁違いに大きい。実際に出た症状:</p>
     *
     * <ul>
     *   <li><b>一人称で窓越しに他の車が見えない。</b>フロントガラスが目の前で画面をほぼ
     *       覆うので、後から描かれるエンティティが全部消える。地形はエンティティより先に
     *       描かれているので残る——「ガラスの向こうの<b>物</b>だけ消える」という形で出た</li>
     *   <li><b>自分のタイヤが描画されない。</b>タイヤは車体より後に描かれるので、
     *       同じ理由でガラスに弾かれる</li>
     * </ul>
     *
     * <p>{@code COLOR_WRITE}（色だけ書いて深度は書かない）にすれば起きない。深度<b>テスト</b>は
     * 残してあるので、壁の向こうのガラスが透けて見えることはない。</p>
     *
     * <p><b>深度を書かなくてもガラス同士の前後は合う。</b>{@code sortOnUpload} が積んだ
     * 四角形を奥から手前へ並べ直すので、重なった半透明は正しい順で混色される——
     * そもそも深度書き込みは半透明の前後を解く道具ではない。</p>
     *
     * <p>引き換えに、<b>ガラスより後に描かれたものにはガラスの色が乗らない</b>。だから
     * {@code CarObjRenderer} は<b>車体もタイヤも描き終えた最後に</b>ガラスを描く。</p>
     */
    public static RenderType glass(ResourceLocation texture) {
        return GLASS.computeIfAbsent(texture, location -> create(
                "kurumamod_glass",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                // 奥から手前へ並べ直す。深度を書かない代わりにこれが前後を解く
                true,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(location, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false)));
    }

    /** テクスチャごとに 1 つ。<b>毎フレーム作ってはいけない</b>——別物として扱われて束ねられない。 */
    private static final Map<ResourceLocation, RenderType> GLASS = new HashMap<>();

    /**
     * 鏡面に景色を貼るためのもの。<b>テクスチャは {@link MirrorRenderer} が持っている
     * フレームバッファ</b>で、{@code ResourceLocation} では指せない。</p>
     *
     * <p>そこで {@code EmptyTextureStateShard} に「描く直前に GL のテクスチャ id を挿す」
     * 仕事をさせている。<b>id は毎フレーム変わりうる</b>（描き先を作り直したとき）ので、
     * 作るときに焼き込まず、流すときに聞きにいくこと。</p>
     *
     * <p><b>明るさを掛けない</b>（{@code POSITION_COLOR_TEX}）。映っている絵はすでに
     * ライトを通って描かれているので、ここで陰影を重ねると二重に暗くなる。</p>
     *
     * <p>鏡 1 枚につき 1 つ作る。{@code RenderType} は同じものどうしでまとめて流されるので、
     * <b>共用すると全部の鏡が同じテクスチャになる</b>。</p>
     */
    public static RenderType mirror(int slot) {
        RenderType type = MIRRORS[slot];
        if (type == null) {
            type = create(
                    "kurumamod_mirror_" + slot,
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_TEX_SHADER)
                            .setTextureState(new EmptyTextureStateShard(
                                    () -> RenderSystem.setShaderTexture(0, MirrorRenderer.textureId(slot)),
                                    () -> {
                                    }))
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));
            MIRRORS[slot] = type;
        }
        return type;
    }

    private static final RenderType[] MIRRORS = new RenderType[MirrorRenderer.MAX_MIRRORS];

    private KurumaRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        throw new UnsupportedOperationException("定数を置くためだけのクラス");
    }
}
