package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
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
     * 光を<b>足す</b>もの（加算合成）。ヘッドライトの光の筋・レンズの光芒・路面の照り返しが使う。
     *
     * <p>{@link #TRANSLUCENT_OVERLAY}（混ぜる）で光を描くと、後ろの景色を<b>白い色で塗る</b>
     * ことになり、霧か板のように見える。光は後ろを明るくするものなので足すのが正しい。
     * 足し算は順序に依らないので並べ替えも要らない。深度は書かない（理由は同上）。</p>
     */
    public static final RenderType LIGHT_ADDITIVE = create(
            "kurumamod_light_additive",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            8192,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    /**
     * ヘッドライトに照らされたブロックの面を、<b>そのブロックのテクスチャで明るさ最大に描き直して</b>
     * 重ねるためのもの。濃さ（α）が「どれだけ光が届いているか」になる。
     *
     * <p><b>後ろの色に掛け算しても夜の路面は明るくならない。</b>夜の地面は昼の 3% ほどしかなく、
     * 掛け算の合成は 2 倍が上限なので 6% にしかならない（実際にそうして「うっすらしか見えない」になった）。
     * 加算だけだと模様の上に一様な色が乗って光った膜に見える。照らされた面は
     * <b>その面の昼の姿に近づく</b>ものなので、昼の姿そのものを描いて混ぜるのが正しい。</p>
     *
     * <p>深度は書かない（{@link #TRANSLUCENT_OVERLAY} と同じ理由）。z ファイティングは
     * 視点方向へのずらし（{@code VIEW_OFFSET_Z_LAYERING}）で避ける。</p>
     */
    public static final RenderType LIT_SURFACE = create(
            "kurumamod_lit_surface",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            8192,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(
                            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS, false, true))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
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
     * ゴーストの 1 回目。<b>深度だけ</b>書いて色は書かない。
     *
     * <p>半透明の車をそのまま描くと、車内・タイヤの裏・反対側の面まで全部透けて重なり、
     * 濁った塊に見える。先に深度だけ埋めておけば、2 回目（{@link #ghostColor}）は
     * <b>いちばん手前の面だけ</b>が深度テストを通るので、外側の殻だけが透けて見える。
     * テクスチャの抜き（グリルの穴など）は cutout のシェーダが捨てるのでそのまま抜ける。</p>
     */
    public static RenderType ghostDepth(ResourceLocation texture) {
        return GHOST_DEPTH.computeIfAbsent(texture, location -> create(
                "kurumamod_ghost_depth",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new TextureStateShard(location, false, false))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    /**
     * ゴーストの 2 回目。色を半透明で重ねる。深度は書かない（1 回目で書いてある）。
     *
     * <p>カリングを切るのは右側のタイヤを鏡像で描くため（面の巻き方向が裏返る）。
     * 裏の面が出ても、1 回目の深度に弾かれるので重ならない。</p>
     */
    public static RenderType ghostColor(ResourceLocation texture) {
        return GHOST_COLOR.computeIfAbsent(texture, location -> create(
                "kurumamod_ghost_color",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                false,
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

    private static final Map<ResourceLocation, RenderType> GHOST_DEPTH = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> GHOST_COLOR = new HashMap<>();

    /**
     * GPU に置いた車のメッシュを描くときの状態（{@link CarMeshes}）。<b>頂点は積まない</b>——
     * 描く直前に {@code setupRenderState} で状態だけ整えて、頂点バッファを直接流す。
     *
     * <p>中身は {@code entityCutoutNoCull} と同じで、シェーダーだけが自前（明るさと変換を外から渡す）。</p>
     */
    public static RenderType carMesh(ResourceLocation texture) {
        return CAR_MESHES.computeIfAbsent(texture, location -> create(
                "kurumamod_car_mesh",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                false,
                CompositeState.builder()
                        .setShaderState(new ShaderStateShard(CarMeshes::shader))
                        .setTextureState(new TextureStateShard(location, false, false))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(NO_OVERLAY)
                        .createCompositeState(true)));
    }

    /** テクスチャごとに 1 つ。 */
    private static final Map<ResourceLocation, RenderType> CAR_MESHES = new HashMap<>();

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
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getPositionTexColorShader))
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
