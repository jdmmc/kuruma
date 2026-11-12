package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * この MOD が使う {@link RenderType} と {@link RenderPipeline}。
 *
 * <h2>26.3 での作り</h2>
 *
 * <p>1.21.1 では {@code RenderType} を継承して {@code RenderStateShard} の protected な状態を組み合わせていたが、
 * 26.3 では<b>描き方（シェーダー・混色・深度・カリング）は {@link RenderPipeline}、テクスチャと明るさは
 * {@link RenderSetup}</b> に分かれ、どちらも公開の組み立て口で作れる。シェーダーはバニラのもの
 * （{@code core/entity}・{@code core/position_color}・{@code core/rendertype_lightning}・
 * {@code core/position_tex_color}）をそのまま使っていて、自前のシェーダーは無い。</p>
 *
 * <h2>半透明のものは OIT の組も渡すこと</h2>
 *
 * <p>ビデオ設定の「改良された半透明」（OIT）を入れると、混色する {@code RenderType} はすべて
 * OIT の段で描かれる。そのとき {@code setOitPipelines} が無いと
 * {@code PreparedRenderType#drawFromBufferOit} が<b>例外を投げて落ちる</b>。ここの半透明のものは
 * どれも、同じシェーダーを使うバニラの OIT の組を渡してある。</p>
 *
 * <h2>深度は「大きいほど手前」</h2>
 *
 * <p>26.3 は深度が逆向き（Reversed-Z）で、バニラの既定は {@code GREATER_THAN_OR_EQUAL}。
 * 1.21.1 の {@code LEQUAL_DEPTH_TEST} にあたるのはこちら。</p>
 */
public final class KurumaRenderTypes {

    private KurumaRenderTypes() {
    }

    /** 深度を<b>読むが書かない</b>（1.21.1 の {@code COLOR_WRITE}）。 */
    private static final DepthStencilState TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    private static Identifier pipelineId(String name) {
        return Identifier.fromNamespaceAndPath(Kurumamod.MODID, "pipeline/" + name);
    }

    /**
     * 半透明の板を世界に重ねるためのもの。コースのゲートと、タイヤ痕が使う。
     *
     * <p><b>深度を書いてはいけない。</b>半透明なのに深度を書くと、後から描くものが
     * 「手前に何かある」と判定されて弾かれる（1.21.1 で、ヘッドライトの光の筋と重なった
     * ゲートだけが消えた）。深度<b>テスト</b>は残してあるので、壁の向こうへ突き抜けて見えることはない。
     * 26.3 のバニラの {@code debug_quads} はもう深度を書かないので、そのパイプラインを借りている。</p>
     *
     * <p>積んだ四角形は奥から並べ直す（{@code sortOnUpload}）。半透明どうしが重なっても順序が合う。</p>
     */
    public static final RenderType TRANSLUCENT_OVERLAY = RenderType.create(
            "kurumamod_translucent_overlay",
            RenderSetup.builder(RenderPipelines.DEBUG_QUADS)
                    .setOitPipelines(RenderPipelines.OIT_DEBUG_QUADS)
                    .sortOnUpload()
                    .createRenderSetup());

    /** 光を足すパイプライン（{@link #LIGHT_ADDITIVE}）。 */
    public static final RenderPipeline LIGHT_ADDITIVE_PIPELINE = RenderPipeline.builder(RenderPipelines.LIGHTNING_SNIPPET)
            .withLocation(pipelineId("light_additive"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(TEST_NO_WRITE)
            .withCull(false)
            .build();

    /**
     * 光を<b>足す</b>もの（加算合成）。ヘッドライトの光の筋・レンズの光芒・路面の照り返しが使う。
     *
     * <p>{@link #TRANSLUCENT_OVERLAY}（混ぜる）で光を描くと、後ろの景色を<b>白い色で塗る</b>
     * ことになり、霧か板のように見える。光は後ろを明るくするものなので足すのが正しい。
     * 足し算は順序に依らないので並べ替えも要らない。深度は書かない（理由は同上）。</p>
     */
    public static final RenderType LIGHT_ADDITIVE = RenderType.create(
            "kurumamod_light_additive",
            RenderSetup.builder(LIGHT_ADDITIVE_PIPELINE)
                    .setOitPipelines(RenderPipelines.OIT_LIGHTNING)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    /**
     * 半透明の車体用（{@code core/entity} を混色・深度を書かない・カリング無しで）。
     * ガラス・ゴーストの 2 回目・照らされた面が使う。
     */
    public static final RenderPipeline ENTITY_GLASS_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(pipelineId("entity_glass"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(TEST_NO_WRITE)
            .withCull(false)
            .build();

    /**
     * ヘッドライトに照らされたブロックの面を、<b>そのブロックのテクスチャで明るさ最大に描き直して</b>
     * 重ねるためのもの。濃さ（α）が「どれだけ光が届いているか」になる。
     *
     * <p><b>後ろの色に掛け算しても夜の路面は明るくならない。</b>夜の地面は昼の 3% ほどしかなく、
     * 掛け算の合成は 2 倍が上限なので 6% にしかならない。照らされた面は
     * <b>その面の昼の姿に近づく</b>ものなので、昼の姿そのものを描いて混ぜるのが正しい。</p>
     *
     * <p>深度は書かない。z ファイティングは視点方向へのずらし（{@code VIEW_OFFSET_Z_LAYERING}）で避ける。</p>
     */
    public static final RenderType LIT_SURFACE = RenderType.create(
            "kurumamod_lit_surface",
            RenderSetup.builder(ENTITY_GLASS_PIPELINE)
                    .setOitPipelines(RenderPipelines.OIT_ENTITY)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    .useLightmap()
                    .useOverlay()
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    /**
     * HUD のメーターを描くパイプライン。<b>バニラの {@code RenderPipelines.GUI} との違いはカリングだけ。</b>
     *
     * <p>GUI はカリング有りなので、四角形を積む向きを間違えると<b>何も描かれない</b>。
     * 円弧のセグメントは角度によって向きが変わるように見えて紛らわしいうえ、
     * 表裏を間違えても「消える」としか出ないので原因が分かりにくい。切っておく。</p>
     *
     * <p>26.3 の画面は {@code GuiGraphicsExtractor} が描くものを集めてから流すので、
     * 画面に描くならこちら（パイプライン）を使う。</p>
     */
    public static final RenderPipeline GUI_SHAPE_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(pipelineId("gui_shape"))
            .withCull(false)
            .build();

    /** {@link #GUI_SHAPE_PIPELINE} を {@code RenderType} にしたもの（POSITION_COLOR の四角形）。 */
    public static final RenderType GUI_SHAPE = RenderType.create(
            "kurumamod_gui_shape",
            RenderSetup.builder(GUI_SHAPE_PIPELINE).createRenderSetup());

    /**
     * 車のガラス。<b>バニラの {@code entityTranslucent} を使ってはいけない。深度を書く。</b>
     *
     * <p>半透明なのに深度を書くと、<b>その後に描かれるものが「手前に何かある」と判定されて
     * 弾かれる</b>。ガラスは画面を大きく覆うぶん被害が桁違いに大きい（1.21.1 で、一人称で窓越しに
     * 他の車が見えない・自分のタイヤが描かれない、が出た）。深度<b>テスト</b>は残してあるので、
     * 壁の向こうのガラスが透けて見えることはない。</p>
     *
     * <p><b>深度を書かなくてもガラス同士の前後は合う。</b>{@code sortOnUpload} が積んだ
     * 四角形を奥から手前へ並べ直すので、重なった半透明は正しい順で混色される。</p>
     *
     * <p>26.3 では混色するものは不透明なものの<b>後の段</b>（translucent の段）でまとめて描かれるので、
     * 「車体とタイヤを描き終えてからガラス」は描画の仕組みのほうが保証する。</p>
     */
    public static RenderType glass(Identifier texture) {
        return GLASS.apply(texture);
    }

    /** テクスチャごとに 1 つ。<b>毎フレーム作ってはいけない</b>——別物として扱われて束ねられない。 */
    private static final Function<Identifier, RenderType> GLASS = memoize(texture -> RenderType.create(
            "kurumamod_glass",
            RenderSetup.builder(ENTITY_GLASS_PIPELINE)
                    .setOitPipelines(RenderPipelines.OIT_ENTITY)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .sortOnUpload()
                    .createRenderSetup()));

    /** ゴーストの 1 回目のパイプライン。色を 1 画素も書かない（深度だけ）。 */
    public static final RenderPipeline GHOST_DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(pipelineId("ghost_depth"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
            .withCull(false)
            .build();

    /**
     * ゴーストの 1 回目。<b>深度だけ</b>書いて色は書かない。
     *
     * <p>半透明の車をそのまま描くと、車内・タイヤの裏・反対側の面まで全部透けて重なり、
     * 濁った塊に見える。先に深度だけ埋めておけば、2 回目（{@link #ghostColor}）は
     * <b>いちばん手前の面だけ</b>が深度テストを通るので、外側の殻だけが透けて見える。
     * 混色しないので不透明の段で描かれ、2 回目（半透明の段）より必ず先になる。</p>
     */
    public static RenderType ghostDepth(Identifier texture) {
        return GHOST_DEPTH.apply(texture);
    }

    /**
     * ゴーストの 2 回目。色を半透明で重ねる。深度は書かない（1 回目で書いてある）。
     *
     * <p>カリングを切るのは右側のタイヤを鏡像で描くため。裏の面が出ても、1 回目の深度に弾かれるので重ならない。</p>
     */
    public static RenderType ghostColor(Identifier texture) {
        return GHOST_COLOR.apply(texture);
    }

    private static final Function<Identifier, RenderType> GHOST_DEPTH = memoize(texture -> RenderType.create(
            "kurumamod_ghost_depth",
            RenderSetup.builder(GHOST_DEPTH_PIPELINE)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup()));
    private static final Function<Identifier, RenderType> GHOST_COLOR = memoize(texture -> RenderType.create(
            "kurumamod_ghost_color",
            RenderSetup.builder(ENTITY_GLASS_PIPELINE)
                    .setOitPipelines(RenderPipelines.OIT_ENTITY)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup()));

    // ------------------------------------------------------------------
    // ミラー
    // ------------------------------------------------------------------

    /**
     * 鏡面に景色を貼るパイプライン。明るさを掛けない・混色しない・深度は普通に読み書きする。
     *
     * <p><b>画素の捨て方だけ自前のシェーダー（{@code kurumamod:core/mirror}）にしてある。</b>バニラの
     * {@code position_tex_color} は不透明度が 0 の画素を捨てるが、26.3 のワールドの描画は描き先を
     * <b>不透明度 0 の霧の色</b>で塗ってから始まり、空はその不透明度を書き換えない。そのまま貼ると
     * 鏡に映った空の部分がまるごと捨てられ、鏡が透けた（1.21.1 では描いた後に不透明度を 1 に塗り直していた）。</p>
     */
    public static final RenderPipeline MIRROR_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(pipelineId("mirror"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Kurumamod.MODID, "core/mirror"))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build();

    /**
     * 鏡面に景色を貼るためのもの（POSITION_TEX_COLOR）。テクスチャは {@link #mirrorTexture(int)} の名前で
     * テクスチャの一覧に置いたもの（{@link #setMirrorTexture} で差し込む）。
     *
     * <p><b>明るさを掛けない</b>。映っている絵はすでにライトを通って描かれているので、
     * ここで陰影を重ねると二重に暗くなる。</p>
     *
     * <p>鏡 1 枚につき 1 つ作る。{@code RenderType} は同じものどうしでまとめて流されるので、
     * <b>共用すると全部の鏡が同じテクスチャになる</b>。</p>
     *
     * <p><b>{@link #setMirrorTexture} で絵を差し込む前に使ってはいけない。</b>名前の先にテクスチャが無いと、
     * テクスチャの一覧が同じ名前の画像ファイルを探しにいき、見つからずに紫と黒の模様になる。
     * 絵が無いときは、普通の暗い面（{@code ObjModel#renderMirrorPlain}）で描くこと。</p>
     */
    public static RenderType mirror(int slot) {
        RenderType type = MIRRORS[slot];
        if (type == null) {
            type = RenderType.create(
                    "kurumamod_mirror_" + slot,
                    RenderSetup.builder(MIRROR_PIPELINE)
                            .withTexture("Sampler0", mirrorTexture(slot))
                            .createRenderSetup());
            MIRRORS[slot] = type;
        }
        return type;
    }

    private static final RenderType[] MIRRORS = new RenderType[MirrorRenderer.MAX_MIRRORS];

    /** {@code slot} 番目の鏡の絵を置く名前。 */
    public static Identifier mirrorTexture(int slot) {
        return Identifier.fromNamespaceAndPath(Kurumamod.MODID, "dynamic/mirror_" + slot);
    }

    /** {@code slot} 番目の鏡に、いま絵が差し込まれているか（{@link #mirror} を使ってよいか）。 */
    public static boolean hasMirrorTexture(int slot) {
        return MIRROR_TEXTURES[slot] != null;
    }

    /**
     * {@code slot} 番目の鏡の絵を差し込む。null で外す（描き先を捨てるとき）。
     *
     * <p>描き先（{@code GpuTextureView}）を作り直したら、そのたびに呼び直すこと。テクスチャの一覧へは
     * 中身を持たない入れ物として置くだけで、<b>描き先を閉じるのは呼ぶ側</b>。</p>
     */
    public static void setMirrorTexture(int slot, @Nullable GpuTextureView view) {
        ExternalTexture current = MIRROR_TEXTURES[slot];
        if (view == null) {
            if (current != null) {
                current.detach();
                Minecraft.getInstance().getTextureManager().release(mirrorTexture(slot));
                MIRROR_TEXTURES[slot] = null;
            }
            return;
        }
        if (current == null) {
            current = new ExternalTexture();
            MIRROR_TEXTURES[slot] = current;
            current.attach(view);
            Minecraft.getInstance().getTextureManager().register(mirrorTexture(slot), current);
        } else {
            current.attach(view);
        }
    }

    private static final ExternalTexture[] MIRROR_TEXTURES = new ExternalTexture[MirrorRenderer.MAX_MIRRORS];

    /**
     * よそが持っている描き先を、テクスチャの一覧に名前で置くための入れ物。
     * <b>閉じても描き先そのものは閉じない</b>（持ち主が閉じる）。
     */
    private static final class ExternalTexture extends AbstractTexture {
        void attach(GpuTextureView view) {
            this.texture = view.texture();
            this.textureView = view;
        }

        void detach() {
            this.texture = null;
            this.textureView = null;
        }

        @Override
        public void close() {
            detach();
        }
    }

    private static <K, V> Function<K, V> memoize(Function<K, V> factory) {
        Map<K, V> cache = new HashMap<>();
        return key -> cache.computeIfAbsent(key, factory);
    }
}
