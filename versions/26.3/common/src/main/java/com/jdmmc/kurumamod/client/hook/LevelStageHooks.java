package com.jdmmc.kurumamod.client.hook;

import com.jdmmc.kurumamod.client.CarSkidMarks;
import com.jdmmc.kurumamod.client.CourseRenderer;
import com.jdmmc.kurumamod.client.PartAnchors;
import com.jdmmc.kurumamod.client.RiderPoseRenderer;
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
 *   <li>{@link #levelEnd} … ワールドを描き終えたところ（1.21.1 の {@code AFTER_LEVEL} の代わり）</li>
 * </ul>
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

    private LevelStageHooks() {
    }

    public static void levelStart(CameraRenderState camera) {
        LevelRenderStage event = stage(LevelRenderStage.Stage.AFTER_SKY, camera);
        try {
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

    public static void levelEnd(CameraRenderState camera) {
        LevelRenderStage event = stage(LevelRenderStage.Stage.AFTER_LEVEL, camera);
        try {
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
