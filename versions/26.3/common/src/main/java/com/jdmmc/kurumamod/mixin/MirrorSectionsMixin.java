package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.render.MirrorRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ミラーを描いている間は、見えるチャンクの探索（遮蔽グラフ）を<b>捨てさせない</b>（{@link MirrorRenderer}）。
 *
 * <p>グラフはカメラが 8 ブロックの升目を跨ぐと捨てられ、裏で丸ごと探し直される。鏡のカメラは運転者の目から
 * 1〜2m 離れているので、目と鏡が升目の境を挟んでいる間は、本編と鏡で<b>毎フレーム 2 回捨てる</b>ことになる。
 * 鏡は本編のグラフのまま描く（位置の差は 1〜2m なので、見えるチャンクはほぼ同じ）。
 * 区画の並びのほうは {@link MirrorViewAreaMixin}・{@link MirrorSectionTrackerMixin}。</p>
 *
 * <p>割り込む先は public で、NeoForge のパッチ入りと、Fabric・Forge の jar に同じ形である（javap で確認）。</p>
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class MirrorSectionsMixin {

    @Inject(method = "invalidateIfNeeded", at = @At("HEAD"), cancellable = true)
    private void kurumamod$keepGraph(CameraRenderState camera, int fov, CallbackInfo ci) {
        if (MirrorRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
