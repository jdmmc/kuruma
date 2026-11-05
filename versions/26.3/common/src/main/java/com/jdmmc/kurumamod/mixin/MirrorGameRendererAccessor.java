package com.jdmmc.kurumamod.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ミラーの描画が {@link GameRenderer} の private なところを使う口（{@code MirrorRenderer}）。
 *
 * <p>{@code GameRenderer#extract} を丸ごと呼ぶと GUI の抽出まで 2 回走るので、カメラの抽出
 * （{@code extractCamera}）だけを呼ぶ。全体の uniform（{@code Globals}。地形のシェーダーがカメラの位置と
 * 画面の大きさを読む）は本編の {@code render} の頭でしか書かれないので、鏡のぶんを自分で書く。</p>
 */
@Mixin(GameRenderer.class)
public interface MirrorGameRendererAccessor {

    @Invoker("extractCamera")
    void kuruma$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks);

    @Accessor("globalSettingsUniform")
    GlobalSettingsUniform kuruma$globalSettingsUniform();
}
