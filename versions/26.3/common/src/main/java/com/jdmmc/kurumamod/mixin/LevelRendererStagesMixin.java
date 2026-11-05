package com.jdmmc.kurumamod.mixin;

import com.jdmmc.kurumamod.client.hook.LevelStageHooks;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ワールドの描画の段に割り込む（{@link LevelStageHooks}）。<b>3 つのローダーで同じ位置。</b>
 *
 * <p>Forge 26.3 には {@code RenderLevelStageEvent} が無く、NeoForge と Fabric のイベントも段の位置が揃わないので、
 * ローダーのイベントは使わずここで割り込む。NeoForge と Forge はこのクラスにパッチを当てているので、
 * 割り込む位置は<b>どれにも残っているもの</b>だけにしてある（3 つの jar のバイトコードで確認）:</p>
 * <ul>
 *   <li>{@code render} の頭と終わり … ワールドを描く 1 回ぶんの始まりと終わり</li>
 *   <li>{@code submitFeatures} の中の {@code finalizeGizmoCollection()} の呼び出しの直前 …
 *       エンティティ・ブロックエンティティ・粒を出し終えたところ。ここで出した面は、
 *       同じフレームの半透明の段で一緒に描かれる</li>
 * </ul>
 *
 * <p>引数は取らず、要るものはフィールドから読む（ローダーのパッチで引数の並びが変わっても当たるように）。</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererStagesMixin {

    @Shadow
    @Final
    private SubmitNodeStorage submitNodeStorage;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void kurumamod$levelStart(CallbackInfo ci) {
        LevelStageHooks.levelStart(levelRenderState.cameraRenderState);
    }

    @Inject(method = "submitFeatures",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V"))
    private void kurumamod$submit(CallbackInfo ci) {
        LevelStageHooks.submit(submitNodeStorage, levelRenderState.cameraRenderState);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void kurumamod$levelEnd(CallbackInfo ci) {
        LevelStageHooks.levelEnd(levelRenderState.cameraRenderState);
    }
}
