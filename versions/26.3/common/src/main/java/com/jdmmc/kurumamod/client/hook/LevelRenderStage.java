package com.jdmmc.kurumamod.client.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;

/**
 * ワールドを描いている途中の 1 つの段。NeoForge の {@code RenderLevelStageEvent} と同じ名前の
 * メソッドを持たせてあるので、ハンドラの中身はローダーを問わず同じに書ける。
 *
 * <p><b>26.3 では common の mixin（{@code mixin/LevelRendererStagesMixin}）が作って飛ばす</b>
 * （{@link LevelStageHooks}）。Forge 26.3 には段のイベントが無いので、3 つのローダーで同じ位置に揃えるため。</p>
 *
 * <ul>
 *   <li>{@link Stage#AFTER_SKY} … {@code LevelRenderer#render} の頭（ワールドを描き始める前）。
 *       名前は 1.21.1 のまま（ハンドラを書き換えずに済むように）</li>
 *   <li>{@link Stage#AFTER_LEVEL} … {@code LevelRenderer#render} の終わり</li>
 *   <li>{@link Stage#AFTER_TRANSLUCENT_BLOCKS} / {@link Stage#AFTER_TRIPWIRE_BLOCKS} … <b>26.3 では飛ばない</b>
 *       （半透明の面は {@link LevelStageHooks#submit} で出す）。値だけ残してある</li>
 * </ul>
 *
 * <p><b>{@link #getPoseStack()} は単位行列。</b>カメラの回転は {@link #getModelViewMatrix()}（位置は入っていない＝
 * カメラ基準）、射影は {@link #getProjectionMatrix()}（画面の揺れは入っていない）。</p>
 */
public final class LevelRenderStage {

    public enum Stage {
        AFTER_SKY,
        AFTER_TRANSLUCENT_BLOCKS,
        AFTER_TRIPWIRE_BLOCKS,
        AFTER_LEVEL
    }

    private final Stage stage;
    private final PoseStack poseStack = new PoseStack();
    private final Matrix4f modelViewMatrix;
    private final Matrix4f projectionMatrix;
    private final DeltaTracker partialTick;
    private final Camera camera;

    public LevelRenderStage(Stage stage, Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                            DeltaTracker partialTick, Camera camera) {
        this.stage = stage;
        this.modelViewMatrix = modelViewMatrix;
        this.projectionMatrix = projectionMatrix;
        this.partialTick = partialTick;
        this.camera = camera;
    }

    public Stage getStage() {
        return stage;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public Matrix4f getModelViewMatrix() {
        return modelViewMatrix;
    }

    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public Camera getCamera() {
        return camera;
    }
}
