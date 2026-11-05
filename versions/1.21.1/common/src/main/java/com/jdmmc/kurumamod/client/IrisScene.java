package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * シェーダーパック（Oculus / Iris）が<b>仕上げる前</b>の絵（colortex0）を読み書きする。
 *
 * <h2>なぜ</h2>
 * <p>Oculus / Iris は世界を描き終えた後（{@code renderLevel} の最後）に、霧・ブルーム・トーンマップなどの
 * 仕上げ（composite と final）をまとめて流す。<b>Forge の {@code AFTER_LEVEL} はその後</b>
 * （{@code GameRenderer} から）に飛ぶので、そこで描くと仕上がった絵を明るくするしかない。暗い所には
 * ブロックの色がほとんど残っていない（月明かり・霧・トーンマップ済み）ので、照らすと白く色褪せた。</p>
 *
 * <p>そこで仕上げの直前（{@code IrisPipelineMixin}）に、colortex0 を読んで光を入れ、colortex0 へ書き戻す。
 * ブロックの色が残った仕上げ前の絵を明るくしてから、パックに一度だけ仕上げてもらえる。</p>
 *
 * <ul>
 *   <li><b>{@code AFTER_LEVEL} で colortex0 に書いても何も映らない。</b>仕上げはもう終わっていて、
 *       次のフレームで消される（一度これで「まったく見えない」になった）</li>
 *   <li><b>Iris は主画面への書き込みを colortex0 へ振り替えない。</b>{@code MixinRenderTarget} は
 *       主画面が結ばれているかを覚えているだけ。書き込み先は自分で付け替える</li>
 *   <li>Complementary の仕上げ前の colortex0 は<b>ガンマのまま</b>（composite1 で {@code pow(color, 2.2)}
 *       して線形に戻す）。掛け算で明るくする路面は残るが、薄い足し算（霧の中の光線）は 2.2 乗で潰れて
 *       ほとんど見えなくなる。パックによって中身の約束が違うので、足し算の効果はパックしだいになる</li>
 * </ul>
 *
 * <p>どれも Iris のコンパイル時の依存を持たないようにリフレクションで聞く。聞けなければ
 * {@link #colorTexture()} が -1 を返し、呼ぶ側は今までの描き方に戻る。</p>
 *
 * <p><b>ピンポン</b>: Iris は書き込みのたびに表と裏（main / alt）を入れ替える。半透明まで描いた後の
 * 向きは {@code getFlippedAfterTranslucent()} に入っていて、0 が入っていれば裏が今の絵。</p>
 */
final class IrisScene {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /** 写しを置く浮動小数のテクスチャ（明るさが 1 を超えていても潰さないため）。 */
    private static int copyTexture = -1;
    private static int copyFramebuffer = -1;
    private static int copyWidth;
    private static int copyHeight;
    /** パックの絵を読み書きするために付け替える枠。 */
    private static int sceneFramebuffer = -1;

    private static int width;
    private static int height;

    private IrisScene() {
    }

    /** いまのパックの絵（colortex0）のテクスチャ。読めなければ -1。 */
    static int colorTexture() {
        if (!resolved) {
            resolve();
        }
        if (broken) {
            return -1;
        }
        try {
            Object manager = getPipelineManager.invoke(null);
            Object pipeline = getPipelineNullable.invoke(manager);
            if (pipeline == null || !irisPipelineClass.isInstance(pipeline)) {
                return -1;
            }
            Object targets = renderTargetsField.get(pipeline);
            Object target = renderTargetsGet.invoke(targets, 0);
            if (target == null) {
                return -1;
            }
            Set<?> flipped = (Set<?>) getFlippedAfterTranslucent.invoke(pipeline);
            boolean alt = flipped.contains(0);
            width = (Integer) getWidth.invoke(target);
            height = (Integer) getHeight.invoke(target);
            int texture = (Integer) (alt ? getAltTexture : getMainTexture).invoke(target);
            if (!announced) {
                announced = true;
                LOGGER.info("シェーダーパックの仕上げ前の絵に光を入れる（colortex0 の{}、{}x{}）",
                        alt ? "裏" : "表", width, height);
            }
            return texture;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("シェーダーパックの絵が読めなかった。以後は仕上がった画面に光を重ねる", e);
            broken = true;
            return -1;
        }
    }

    static int width() {
        return width;
    }

    static int height() {
        return height;
    }

    /** パックの絵を浮動小数のテクスチャへ写し、そのテクスチャを返す（シェーダーはこちらを読む）。 */
    static int copy(int sceneTexture) {
        if (copyTexture < 0 || copyWidth != width || copyHeight != height) {
            if (copyTexture >= 0) {
                GlStateManager._deleteTexture(copyTexture);
                GlStateManager._glDeleteFramebuffers(copyFramebuffer);
            }
            copyTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(copyTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            copyFramebuffer = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, copyFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, copyTexture, 0);
            copyWidth = width;
            copyHeight = height;
        }
        bindScene(GL30.GL_READ_FRAMEBUFFER, sceneTexture);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFramebuffer);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        return copyTexture;
    }

    /** パックの絵を書き込み先にする（光を書き戻すため）。 */
    static void bindForWrite(int sceneTexture) {
        bindScene(GL30.GL_FRAMEBUFFER, sceneTexture);
        GlStateManager._viewport(0, 0, width, height);
    }

    /**
     * パックの絵を書き込み先にし、深度に主画面の深度（Iris もこれをそのまま使う）を付ける。
     * 深度テストをして描くもの（タイヤ痕）のため。描き終えたら {@link #detachDepth()} で外すこと
     * （同じ枠を光の書き戻しにも使うので、付けたままにしない）。
     */
    static void bindForWriteWithDepth(int sceneTexture, int depthTexture) {
        bindScene(GL30.GL_FRAMEBUFFER, sceneTexture);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);
        GlStateManager._viewport(0, 0, width, height);
    }

    static void detachDepth() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFramebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, 0, 0);
    }

    private static void bindScene(int target, int sceneTexture) {
        if (sceneFramebuffer < 0) {
            sceneFramebuffer = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(target, sceneFramebuffer);
        GlStateManager._glFramebufferTexture2D(target, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sceneTexture, 0);
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
            // Iris でない（OptiFine など）か、中身が変わった。仕上がった画面に重ねる描き方に戻る
            LOGGER.info("シェーダーパックの仕上げ前の絵には触れない（{}）", e.toString());
            broken = true;
        }
    }
}
