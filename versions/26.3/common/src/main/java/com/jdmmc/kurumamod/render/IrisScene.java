package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.Platform;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * シェーダーパック（Iris 26.3）と一緒に描くための口。2 つのことをする。
 *
 * <h2>1. 自前のパイプラインを Iris のプログラムに割り当てる（{@link #assignPrograms()}）</h2>
 *
 * <p>Iris はパック使用中、ワールドを描いている間だけ<b>エンティティの頂点の形式を自分の形式に差し替える</b>
 * （{@code MixinRenderPipeline#getVertexFormatBinding}。{@code DefaultVertexFormat.ENTITY} → {@code IrisVertexFormats.ENTITY}）。
 * バニラのパイプラインは Iris の一覧（{@code IrisPipelines}）に載っていてパックのプログラムに置き換わるので
 * 形式が揃うが、<b>一覧に無い自前のパイプラインは元のプログラムのまま、頂点だけが長い形式で積まれる</b>。
 * 1 頂点の幅が食い違うので、頂点が飛んで<b>画面を横切る巨大な黒い三角</b>になった（車のガラスの
 * {@code kurumamod:pipeline/entity_glass}。ログの「Missing program ... in override list」がその印）。
 * ヘッドライトを消していても出たのはこのため。Iris の公開 API（{@code IrisApi#assignPipeline}）で
 * パックのプログラムを割り当てれば、形式もプログラムも揃う。影の段（{@code assignPipelineShadow}）も同じ。</p>
 *
 * <h2>2. パックの仕上げ前の絵（colortex0）へ描く（{@link #beginPreComposite()}）</h2>
 *
 * <p>Iris は {@code LevelRenderer#render} の終わり近く（{@code IrisRenderingPipeline#finalizeLevelRendering}）で
 * 仕上げ（composite と final）を流し、その結果を主画面へ書く。そこより後（{@code LevelStageHooks#levelEnd}）で
 * 描くと、霧・トーンマップ済みの絵に重ねることになる。1.21.1 と同じく、仕上げの直前
 * （{@code mixin/IrisPipelineMixin}）で colortex0 を読み、光・煙・タイヤ痕・ゲートを入れて書き戻す。</p>
 *
 * <p><b>Iris の描き先は Minecraft のテクスチャではなく、生の OpenGL のテクスチャ</b>（{@code RenderTarget#getMainTexture}
 * が int を返す）。renderpearl の描き先にはできないので、浮動小数の作業用テクスチャ（renderpearl で作ったもの）へ
 * 写してそこへ描き、描き終えたら colortex0 へ写し戻す。写すところだけは OpenGL の枠（framebuffer）を使う
 * （renderpearl の OpenGL 実装の {@link GlStateManager} を通すので、renderpearl が覚えている結び先とずれない）。
 * <b>Iris 26.3 は OpenGL でしか動かない</b>（Iris 自身が {@code GlDevice}・{@code GlTexture} を前提にしている）ので、
 * この口が呼ばれるのはパック使用中の OpenGL だけ。Vulkan や Iris の無い環境では一度も通らない。</p>
 *
 * <p>Iris の中身はリフレクションで聞く（Minecraft の名前ではないので、名前を引く禁止には当たらない）。
 * 聞けなければ仕上げ前には描かず、呼ぶ側は仕上がった絵に重ねる描き方に戻る。</p>
 *
 * <p><b>深度について。</b>Iris 1.11.7 はパック使用中も深度を逆向き（Reversed-Z）のまま使う
 * （{@code IrisApi#isReverseZDuringShaders} が影の段以外で true。深度を戻す mixin は登録されていない）。
 * 主画面の深度がそのままパックの深度なので、こちらのシェーダーはパック無しと同じ読み方でよい。</p>
 */
public final class IrisScene {

    private static final Logger LOGGER = LogUtils.getLogger();

    // OpenGL の定数（lwjgl を引かずに済ませる）
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_NEAREST = 0x2600;

    private IrisScene() {
    }

    /** このフレームでシェーダーパックが使われているか（{@link #beginFrame()} で聞き直す）。 */
    private static boolean packActive;

    /** ワールドを描き始めるところで呼ぶ（{@code LevelStageHooks#levelStart}）。 */
    public static void beginFrame() {
        packActive = HeadlightVolume.ShaderPacks.inUse();
    }

    /**
     * このフレームでシェーダーパックが使われているか。頂点を積むたびに聞くので、リフレクションで毎回は聞かず
     * フレームの頭で覚えた値を返す（{@link ObjModel#uvDegenerate} を参照）。
     */
    public static boolean packActive() {
        return packActive;
    }

    // ------------------------------------------------------------------
    // 1. パイプラインの割り当て
    // ------------------------------------------------------------------

    private static boolean assigned;

    /**
     * 頂点の形式がエンティティの、自前のパイプラインを Iris のプログラムに割り当てる（1 回だけ）。
     * Iris が入っていなければ何もしない。ワールドを描き始めるたびに呼んでよい（2 回目からはすぐ戻る）。
     */
    public static void assignPrograms() {
        if (assigned) {
            return;
        }
        assigned = true;
        if (!Platform.isModLoaded("iris")) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            Class<?> shadowProgram = Class.forName("net.irisshaders.iris.api.v0.IrisShadowProgram");
            Method assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, program);
            Method assignShadow = apiClass.getMethod("assignPipelineShadow", RenderPipeline.class, shadowProgram);

            // ガラス・ゴーストの 2 回目（半透明の車体）
            assign(api, assign, program, KurumaRenderTypes.ENTITY_GLASS_PIPELINE, "ENTITIES_TRANSLUCENT");
            assign(api, assignShadow, shadowProgram, KurumaRenderTypes.ENTITY_GLASS_PIPELINE, "SHADOW_TRANSLUCENT");
            // ゴーストの 1 回目（深度だけ。色を書かない設定はパイプラインの側に残る）
            assign(api, assign, program, KurumaRenderTypes.GHOST_DEPTH_PIPELINE, "ENTITIES");
            assign(api, assignShadow, shadowProgram, KurumaRenderTypes.GHOST_DEPTH_PIPELINE, "SHADOW_ENTITIES");
            // 照らされた面（形を積む代用）。パック使用中は描かないが、万一流れても崩れないように
            assign(api, assign, program, EffectRenderTypes.litSurfacePipeline(), "ENTITIES_TRANSLUCENT");
            LOGGER.info("シェーダーパック（Iris）にガラス・ゴーストのパイプラインを割り当てた");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Iris にパイプラインを割り当てられなかった。パック使用中はガラスが崩れることがある", e);
        }
    }

    private static void assign(Object api, Method method, Class<?> programType, RenderPipeline pipeline, String name) {
        try {
            Object value = programType.getMethod("valueOf", String.class).invoke(null, name);
            method.invoke(api, pipeline, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 同じパイプラインを 2 回割り当てると IllegalStateException（資源の読み直しなど）。害は無い
            if (!(e.getCause() instanceof IllegalStateException)) {
                LOGGER.warn("Iris に {} を割り当てられなかった", pipeline.getLocation(), e.getCause());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Iris に {} を割り当てられなかった", pipeline.getLocation(), e);
        }
    }

    // ------------------------------------------------------------------
    // 2. 仕上げ前の絵
    // ------------------------------------------------------------------

    private static boolean resolved;
    private static boolean broken;
    private static Method getPipelineManager;
    private static Method getPipelineNullable;
    private static Class<?> irisPipelineClass;
    private static Field renderTargetsField;
    private static Method getFlippedAfterTranslucent;
    private static Method renderTargetsGet;
    private static Method getMainTexture;
    private static Method getAltTexture;
    private static Method getWidth;
    private static Method getHeight;
    private static boolean announced;

    /** 作業用の絵（RGBA16F）。colortex0 を写してここへ描き、最後に colortex0 へ写し戻す。 */
    private static GpuTexture work;
    private static GpuTextureView workView;
    /** 写しに使う OpenGL の枠（読む側・書く側）。 */
    private static int readFramebuffer = -1;
    private static int drawFramebuffer = -1;

    /** いま開いている（{@link #beginPreComposite} と {@link #endPreComposite} の間）か。 */
    private static boolean open;
    private static int sceneTexture;
    private static int width;
    private static int height;

    /**
     * 仕上げ前の絵を作業用の絵へ写す。描いてよければ true（そのあと必ず {@link #endPreComposite} を呼ぶ）。
     * パックの絵が読めない・大きさが主画面と違う（パックの描画倍率）ときは false。
     */
    public static boolean beginPreComposite() {
        if (open) {
            return true;
        }
        if (!resolved) {
            resolve();
        }
        if (broken) {
            return false;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            Object manager = getPipelineManager.invoke(null);
            Object pipeline = getPipelineNullable.invoke(manager);
            if (pipeline == null || !irisPipelineClass.isInstance(pipeline)) {
                return false;
            }
            Object targets = renderTargetsField.get(pipeline);
            Object target = renderTargetsGet.invoke(targets, 0);
            if (target == null) {
                return false;
            }
            // Iris は書くたびに表と裏（main / alt）を入れ替える。半透明まで描いた後の向きがここに入っている
            Set<?> flipped = (Set<?>) getFlippedAfterTranslucent.invoke(pipeline);
            boolean alt = flipped.contains(0);
            width = (Integer) getWidth.invoke(target);
            height = (Integer) getHeight.invoke(target);
            sceneTexture = (Integer) (alt ? getAltTexture : getMainTexture).invoke(target);
            if (width != main.width || height != main.height || main.getDepthTextureView() == null) {
                // 前後は主画面の深度で見るので、大きさが違えば重ねられない
                return false;
            }
            if (!announced) {
                announced = true;
                LOGGER.info("シェーダーパックの仕上げ前の絵に光・煙・タイヤ痕・ゲートを入れる（colortex0 の{}、{}x{}）",
                        alt ? "裏" : "表", width, height);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("シェーダーパックの絵が読めなかった。以後は仕上がった画面に重ねる", e);
            broken = true;
            return false;
        }
        try {
            ensureWork();
            int workId = glId(work);
            if (workId < 0) {
                broken = true;
                LOGGER.warn("作業用の絵が OpenGL のテクスチャではなかった。以後は仕上がった画面に重ねる");
                return false;
            }
            blit(sceneTexture, workId);
        } catch (RuntimeException e) {
            LOGGER.warn("シェーダーパックの絵を写せなかった。以後は仕上がった画面に重ねる", e);
            broken = true;
            return false;
        }
        open = true;
        return true;
    }

    /** 作業用の絵（描き先）。{@link #beginPreComposite} が true を返した後だけ使える。 */
    public static GpuTextureView target() {
        return workView;
    }

    /** 作業用の絵のテクスチャ（写しを取るため）。 */
    public static GpuTexture targetTexture() {
        return work;
    }

    /** 作業用の絵を colortex0 へ写し戻す。 */
    public static void endPreComposite() {
        if (!open) {
            return;
        }
        open = false;
        try {
            blit(glId(work), sceneTexture);
        } catch (RuntimeException e) {
            LOGGER.warn("シェーダーパックの絵へ書き戻せなかった。以後は仕上がった画面に重ねる", e);
            broken = true;
        }
    }

    private static void ensureWork() {
        if (work != null && work.getWidth(0) == width && work.getHeight(0) == height) {
            return;
        }
        if (work != null) {
            workView.close();
            work.close();
        }
        GpuDevice device = RenderSystem.getDevice();
        // 明るさが 1 を超えていても潰さないよう浮動小数で持つ（パックの colortex0 は HDR のことがある）
        work = device.createTexture("kurumamod shader pack scene", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
        workView = device.createTextureView(work);
    }

    private static int glId(@Nullable GpuTexture texture) {
        return texture instanceof GlTexture gl ? gl.glId() : -1;
    }

    /**
     * テクスチャからテクスチャへ写す（形式が違ってもよい。色の変換は OpenGL がする）。
     * 結び先は {@link GlStateManager} を通して付け替え、終わったら元へ戻す。
     */
    private static void blit(int from, int to) {
        int oldRead = GlStateManager.getFrameBuffer(GL_READ_FRAMEBUFFER);
        int oldDraw = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        if (readFramebuffer < 0) {
            readFramebuffer = GlStateManager.glGenFramebuffers();
            drawFramebuffer = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, from, 0);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, to, 0);
        // 写すのはシザーの範囲だけになるので切っておく（renderpearl は描くたびに自分で掛け直す）
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, oldRead);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldDraw);
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            getPipelineManager = iris.getMethod("getPipelineManager");
            getPipelineNullable = Class.forName("net.irisshaders.iris.pipeline.PipelineManager")
                    .getMethod("getPipelineNullable");
            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            renderTargetsField = irisPipelineClass.getDeclaredField("renderTargets");
            renderTargetsField.setAccessible(true);
            getFlippedAfterTranslucent = irisPipelineClass.getMethod("getFlippedAfterTranslucent");
            Class<?> targets = Class.forName("net.irisshaders.iris.targets.RenderTargets");
            renderTargetsGet = targets.getMethod("get", int.class);
            Class<?> target = Class.forName("net.irisshaders.iris.targets.RenderTarget");
            getMainTexture = target.getMethod("getMainTexture");
            getAltTexture = target.getMethod("getAltTexture");
            getWidth = target.getMethod("getWidth");
            getHeight = target.getMethod("getHeight");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.info("シェーダーパックの仕上げ前の絵には触れない（{}）", e.toString());
            broken = true;
        }
    }
}
