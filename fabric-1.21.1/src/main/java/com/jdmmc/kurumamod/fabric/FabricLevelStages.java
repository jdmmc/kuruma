package com.jdmmc.kurumamod.fabric;

import com.jdmmc.kurumamod.client.hook.ClientHooks;
import com.jdmmc.kurumamod.client.hook.LevelRenderStage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * ワールドの描画の段を、mixin（{@code LevelRendererMixin}・{@code GameRendererMixin}）から共有側へ渡す。
 *
 * <p>NeoForge と同じく、補間の比は {@code Minecraft#getTimer()}、PoseStack は段ごとに 1 つ（単位行列）で、
 * 同じ段の優先度ごとのハンドラ（普通 → 低 → 最低）には同じものを渡す。</p>
 */
public final class FabricLevelStages {

    private FabricLevelStages() {
    }

    public static void fire(LevelRenderStage.Stage stage, Matrix4f modelView, Matrix4f projection, Camera camera) {
        LevelRenderStage event = new LevelRenderStage(stage, modelView, projection,
                Minecraft.getInstance().getTimer(), camera);
        ClientHooks.renderLevelStageNormal(event);
        ClientHooks.renderLevelStageLow(event);
        ClientHooks.renderLevelStageLowest(event);
    }
}
