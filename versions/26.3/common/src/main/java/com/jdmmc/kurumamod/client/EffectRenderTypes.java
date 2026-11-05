package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.client.hook.ShaderRegistrar;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * ワールドに描く効果（タイヤ痕・コースのゲート・灯火の光）のための {@link RenderType}。
 *
 * <p>どれも {@code SubmitNodeCollector#submitCustomGeometry} で出す。描く順・深度・合成は
 * Minecraft の半透明の段がまとめて面倒を見るので、OpenGL でも Vulkan でも同じに動く。</p>
 *
 * <p><b>半透明の RenderType には必ず OIT のパイプラインを付けること。</b>ビデオ設定の
 * 「改良された透明度」（OIT）が入っていると、半透明の面は OIT の段で描かれ、
 * OIT のパイプラインが無い RenderType は {@code IllegalStateException} で落ちる
 * （{@code PreparedRenderType#drawFromBufferOit}）。シェーダーはバニラの
 * {@code core/position_color}・{@code core/entity} が OIT の切り替え（{@code #ifdef OIT_...}）を
 * 持っているので、そのまま借りる。</p>
 *
 * <p>深度は<b>テストするだけで書かない</b>。書くと、重なった跡や光の筋が互いを消し合う。
 * 26.3 の深度は逆向き（手前ほど大きい、消去値 0）なので、比較は {@code GREATER_THAN_OR_EQUAL}。</p>
 */
public final class EffectRenderTypes {

    /** 深度は見るが書かない。 */
    private static final DepthStencilState TEST_ONLY = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    /** 位置と色だけの面。バニラの debug_quads と同じ形で、深度を書かない。 */
    private static final RenderPipeline.Snippet COLORED_SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(TEST_ONLY)
            .withCull(false)
            .buildSnippet();

    /** OIT の段で使う、位置と色だけの面（バニラの OIT_DEBUG_FILLED_SNIPPET と同じ）。 */
    private static final RenderPipeline.Snippet OIT_COLORED_SNIPPET = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .buildSnippet();

    private static final RenderPipeline TRANSLUCENT_OVERLAY_PIPELINE = RenderPipeline.builder(COLORED_SNIPPET)
            .withLocation(id("pipeline/translucent_overlay"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();
    private static final OitPipelineSet OIT_TRANSLUCENT_OVERLAY = OitPipelineSet.builder(
            "kurumamod_translucent_overlay", RenderPipeline.builder(OIT_COLORED_SNIPPET)).build();

    private static final RenderPipeline LIGHT_ADDITIVE_PIPELINE = RenderPipeline.builder(COLORED_SNIPPET)
            .withLocation(id("pipeline/light_additive"))
            // 色 × α を足す（バニラの稲妻と同じ）
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .build();
    /** 加算の面は後ろを隠さないので、OIT の透過率には何も残さない（{@code OIT_ADDITIVE}）。 */
    private static final OitPipelineSet OIT_LIGHT_ADDITIVE = OitPipelineSet.builder(
            "kurumamod_light_additive", RenderPipeline.builder(OIT_COLORED_SNIPPET).withShaderDefine("OIT_ADDITIVE")).build();

    /** 照らされたブロックの面をテクスチャごと描き直す。バニラの entity_translucent から深度の書き込みを抜いたもの。 */
    private static final RenderPipeline LIT_SURFACE_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(id("pipeline/lit_surface"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(TEST_ONLY)
            .withCull(false)
            .build();
    private static final OitPipelineSet OIT_LIT_SURFACE = OitPipelineSet.builder(
                    "kurumamod_lit_surface", RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET).withCull(false))
            .withAccumulateModifier(accumulate -> accumulate.withShaderDefine("PER_FACE_LIGHTING")
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER2))
            .build();

    /**
     * 深度を書かない半透明（位置と色）。タイヤ痕・ゲート。積んだ四角形は奥から並べ直す
     * （頂点はカメラ相対なので、並べ替えの原点がそのままカメラになる）。
     */
    public static final RenderType TRANSLUCENT_OVERLAY = RenderType.create("kurumamod_translucent_overlay",
            RenderSetup.builder(TRANSLUCENT_OVERLAY_PIPELINE)
                    .setOitPipelines(OIT_TRANSLUCENT_OVERLAY)
                    .sortOnUpload()
                    .createRenderSetup());

    /**
     * 光を<b>足す</b>もの（加算合成）。光の筋・光芒・路面の照り返しの色味。
     * 混ぜる合成で描くと後ろの景色を白く塗ることになり、霧か板に見える。足し算は順序に依らないので並べ替えない。
     */
    public static final RenderType LIGHT_ADDITIVE = RenderType.create("kurumamod_light_additive",
            RenderSetup.builder(LIGHT_ADDITIVE_PIPELINE)
                    .setOitPipelines(OIT_LIGHT_ADDITIVE)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    /**
     * ヘッドライトに照らされたブロックの面を、そのブロックのテクスチャで明るさ最大に描き直して重ねる。
     * 濃さ（α）が「どれだけ光が届いているか」。頂点の形式はエンティティと同じ。
     */
    public static final RenderType LIT_SURFACE = RenderType.create("kurumamod_lit_surface",
            RenderSetup.builder(LIT_SURFACE_PIPELINE)
                    .setOitPipelines(OIT_LIT_SURFACE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    .useLightmap()
                    .useOverlay()
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    private EffectRenderTypes() {
    }

    /** 照らされた面のパイプライン（シェーダーパックに割り当てるため。{@link IrisScene#assignPrograms()}）。 */
    static RenderPipeline litSurfacePipeline() {
        return LIT_SURFACE_PIPELINE;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Kurumamod.MODID, path);
    }

    /**
     * パイプラインの登録。<b>登録しなくても初めて使うときにコンパイルされる</b>（バニラの
     * {@code PipelineCache} がその場でコンパイルする）ので、ここは読み込み画面のうちに済ませておくためのもの。
     */
    public static final class Registration {
        private Registration() {
        }

        public static void onRegisterShaders(ShaderRegistrar registrar) {
            registrar.registerPipeline(TRANSLUCENT_OVERLAY_PIPELINE);
            registrar.registerPipeline(LIGHT_ADDITIVE_PIPELINE);
            registrar.registerPipeline(LIT_SURFACE_PIPELINE);
            for (OitPipelineSet set : new OitPipelineSet[]{OIT_TRANSLUCENT_OVERLAY, OIT_LIGHT_ADDITIVE, OIT_LIT_SURFACE}) {
                registrar.registerPipeline(set.depthBoundsPipeline());
                registrar.registerPipeline(set.transmittancePipeline());
                registrar.registerPipeline(set.accumulatePipeline());
            }
        }
    }
}
