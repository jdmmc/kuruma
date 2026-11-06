package com.jdmmc.kurumamod.client.hook;

import com.jdmmc.kurumamod.client.CarMeshes;
import com.jdmmc.kurumamod.client.CarSkidMarks;
import com.jdmmc.kurumamod.client.CourseRenderer;
import com.jdmmc.kurumamod.client.DeferredGlows;
import com.jdmmc.kurumamod.client.HeadlightVolume;
import com.jdmmc.kurumamod.client.IrisScene;
import com.jdmmc.kurumamod.client.MirrorRenderer;
import com.jdmmc.kurumamod.client.PartAnchors;
import com.jdmmc.kurumamod.client.RiderPoseRenderer;
import com.jdmmc.kurumamod.client.TireSmokeVolume;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * ワールドの描画の段から、どのクラスをどの順に呼ぶか。<b>ここにだけ書く</b>（{@link ClientHooks} と同じ考え）。
 * 呼び出し元は common の mixin（{@code mixin/LevelRendererStagesMixin}）で、3 つのローダーで同じ位置。
 *
 * <ul>
 *   <li>{@link #levelStart} … ワールドを描き始める前（1.21.1 の {@code AFTER_SKY} の代わり）。
 *       エンティティを描くのはこの後なので、乗員の姿勢の付け替え（{@link RiderPoseRenderer}）はここから効く</li>
 *   <li>{@link #submit} … エンティティと粒を出し終えたところ。半透明の面をここで出すと、
 *       同じフレームの半透明の段（OIT のときは OIT の段）で一緒に描かれる</li>
 *   <li>{@link #beforePackComposite} … シェーダーパック（Iris）が仕上げ（composite）を流す直前
 *       （{@code mixin/IrisPipelineMixin}。パック使用中だけ。1.21.1 の {@code renderBeforePackComposite} と同じ位置）</li>
 *   <li>{@link #levelEnd} … ワールドを描き終えたところ（1.21.1 の {@code AFTER_LEVEL} の代わり）。
 *       パック使用中は仕上げが終わった後</li>
 * </ul>
 *
 * <p><b>シェーダーパック使用中の順番</b>（1.21.1 と同じ）: 仕上げの前にタイヤ痕 → ヘッドライトの路面 → 煙 → ゲート
 * をパックの仕上げ前の絵へ入れ、仕上がった後にヘッドライトの光線 → 光芒を足す。仕上げ前の絵が読めなければ、
 * 全部を仕上がった後に同じ順で重ねる。タイヤ痕・ゲート・光芒はパック使用中は記録だけしておき
 * （{@link DeferredGlows}）、ここで描く。</p>
 *
 * <p>1.21.1 の {@code AFTER_TRANSLUCENT_BLOCKS} / {@code AFTER_TRIPWIRE_BLOCKS} に当たる段は飛ばさない。
 * そこで描いていたもの（タイヤ痕・ゲート）は {@link #submit} で出す形に移した。</p>
 *
 * <p><b>ここで例外を外へ出さないこと。</b>描画の最中に投げると画面ごと落ちる。効果が 1 つ欠けるほうがまし。</p>
 */
public final class LevelStageHooks {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 同じ失敗を毎フレーム記録しないための旗。 */
    private static boolean reported;

    /** このフレームのカメラ（パックの仕上げの直前に使う）。ミラーの描画中は書き換えない。 */
    private static CameraRenderState frameCamera;
    /** このフレームのタイヤ痕とゲートをパックの仕上げ前の絵へ入れた。 */
    private static boolean packPreCompositeDone;

    private LevelStageHooks() {
    }

    public static void levelStart(CameraRenderState camera) {
        LevelRenderStage event = stage(LevelRenderStage.Stage.AFTER_SKY, camera);
        try {
            if (!MirrorRenderer.isRendering()) {
                frameCamera = camera;
                packPreCompositeDone = false;
                // 自前のパイプラインを Iris のプログラムに割り当てる（1 回だけ。Iris が無ければ何もしない）
                IrisScene.assignPrograms();
                IrisScene.beginFrame();
                DeferredGlows.beginFrame();
                HeadlightVolume.levelStart();
            }
            CarMeshes.beginLevel(camera);
            PartAnchors.onRenderLevel(event);
            RiderPoseRenderer.onRenderLevelStage(event);
        } catch (RuntimeException e) {
            report(e);
        }
    }

    /** 面を出す段。座標はカメラを原点にした世界の向き（{@code camera.pos} を引いた値）で出す。 */
    public static void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        if (!camera.initialized) {
            return;
        }
        try {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            CarSkidMarks.submit(collector, camera.pos, partialTick);
            CourseRenderer.submit(collector, camera.pos);
        } catch (RuntimeException e) {
            report(e);
        }
    }

    /**
     * シェーダーパック（Iris）が仕上げを流す直前（{@code mixin/IrisPipelineMixin}）。パックの仕上げ前の絵へ
     * タイヤ痕・ヘッドライトの路面・煙・ゲートを入れる。<b>例外は外へ出さない</b>（Iris の描画の最中）。
     */
    public static void beforePackComposite() {
        CameraRenderState camera = frameCamera;
        if (camera == null || !camera.initialized || MirrorRenderer.isRendering() || !DeferredGlows.active()) {
            return;
        }
        boolean begun = false;
        try {
            if (!IrisScene.beginPreComposite()) {
                return;
            }
            begun = true;
            var target = IrisScene.target();
            var depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
            // タイヤ痕を先に。光がその上から路面を照らす
            DeferredGlows.draw(DeferredGlows.MARKS, DeferredGlows.Blend.DARKEN, target, depth, camera.viewRotationMatrix, true);
            HeadlightVolume.renderBeforePackComposite(camera, target);
            // 煙は光の後（光が煙を路面として明るくしないように。煙は自分でランプの光を受ける）
            TireSmokeVolume.renderBeforePackComposite(camera, target);
            // ゲートは光の後（路面の照り返しで色が変わらないように）
            DeferredGlows.draw(DeferredGlows.GATES, DeferredGlows.Blend.TRANSLUCENT, target, depth, camera.viewRotationMatrix, false);
            packPreCompositeDone = true;
        } catch (RuntimeException e) {
            report(e);
        } finally {
            if (begun) {
                IrisScene.endPreComposite();
            }
        }
    }

    public static void levelEnd(CameraRenderState camera) {
        CarMeshes.endLevel();
        LevelRenderStage event = stage(LevelRenderStage.Stage.AFTER_LEVEL, camera);
        try {
            boolean deferred = DeferredGlows.active();
            var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (deferred && !packPreCompositeDone && main.getDepthTextureView() != null) {
                // パックの仕上げ前の絵へ入れられなかった。仕上がった画面へタイヤ痕を（光より先）
                DeferredGlows.draw(DeferredGlows.MARKS, DeferredGlows.Blend.DARKEN, main.getColorTextureView(),
                        main.getDepthTextureView(), camera.viewRotationMatrix, true);
            }
            // ヘッドライトの路面の照り返しと霧の光線（描き終えた主画面に足す。失敗は中で拾って代用に落ちる）
            HeadlightVolume.levelEnd(camera);
            // タイヤの煙の体積。ヘッドライトの後（先に描くと煙が路面として照らされる。ランプも同じフレームのものになる）
            TireSmokeVolume.renderAfterLevel(camera);
            if (deferred && main.getDepthTextureView() != null) {
                if (!packPreCompositeDone) {
                    DeferredGlows.draw(DeferredGlows.GATES, DeferredGlows.Blend.TRANSLUCENT, main.getColorTextureView(),
                            main.getDepthTextureView(), camera.viewRotationMatrix, false);
                }
                // 光芒は最後に足す（路面の照り返しが画面を写して描き直すので、先に足すと消される）
                DeferredGlows.draw(DeferredGlows.GLOWS, DeferredGlows.Blend.ADD, main.getColorTextureView(),
                        main.getDepthTextureView(), camera.viewRotationMatrix, false);
            }
            PartAnchors.onRenderLevel(event);
            RiderPoseRenderer.onRenderLevelStage(event);
        } catch (RuntimeException e) {
            report(e);
        }
    }

    private static LevelRenderStage stage(LevelRenderStage.Stage stage, CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        return new LevelRenderStage(stage, new Matrix4f(camera.viewRotationMatrix), new Matrix4f(camera.projectionMatrix),
                minecraft.getDeltaTracker(), minecraft.gameRenderer.mainCamera());
    }

    private static void report(RuntimeException e) {
        if (!reported) {
            reported = true;
            LOGGER.error("ワールドの効果を描けなかった。このフレームの効果を飛ばす（以後この記録は出さない）", e);
        }
    }
}
