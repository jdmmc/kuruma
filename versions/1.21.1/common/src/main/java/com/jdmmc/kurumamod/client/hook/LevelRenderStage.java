package com.jdmmc.kurumamod.client.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;

/**
 * ワールドを描いている途中の 1 つの段。NeoForge の {@code RenderLevelStageEvent} と同じ名前の
 * メソッドを持たせてあるので、ハンドラの中身はローダーを問わず同じに書ける。
 *
 * <p>段は NeoForge が割り込んでいる位置と同じ。Fabric 版は Fabric API の {@code WorldRenderEvents} を
 * 使わず、mixin で同じ位置に割り込む（{@code WorldRenderEvents.AFTER_TRANSLUCENT} はパーティクルの後で、
 * NeoForge の {@code AFTER_TRANSLUCENT_BLOCKS} とずれるため。煙やヘッドライトは深度を見て描くので、
 * 段がずれると前後が崩れる）。</p>
 *
 * <ul>
 *   <li>{@link Stage#AFTER_SKY} … {@code LevelRenderer#renderLevel} で空を描いた直後</li>
 *   <li>{@link Stage#AFTER_TRANSLUCENT_BLOCKS} / {@link Stage#AFTER_TRIPWIRE_BLOCKS} …
 *       {@code renderSectionLayer} で、その層を描き終えて {@code clearRenderState()} する手前</li>
 *   <li>{@link Stage#AFTER_LEVEL} … {@code GameRenderer#renderLevel} で、ワールドを描き終えて手を描く手前</li>
 * </ul>
 *
 * <p><b>{@link #getPoseStack()} は単位行列</b>（NeoForge もこの 4 つの段では新しい {@code PoseStack} を渡す）。
 * カメラの回転は {@link #getModelViewMatrix()} にある。同じ段のハンドラは同じ PoseStack を受け取る。</p>
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
