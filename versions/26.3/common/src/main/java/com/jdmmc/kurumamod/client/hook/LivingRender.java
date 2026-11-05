package com.jdmmc.kurumamod.client.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 生き物（プレイヤーを含む）を 1 体描く前後。NeoForge の {@code RenderLivingEvent} と同じ名前のメソッドを持つ。
 * {@code LivingEntityRenderer#submit} の先頭（Pre）と終わり（Post）で作られる。
 *
 * <p><b>26.3 の描画は「状態を集める → あとで出す」なので、描く場面には実体が無い</b>（渡ってくるのは
 * {@link LivingEntityRenderState} だけ）。そこで、状態を集める場面（{@code LivingEntityRenderer#extractRenderState}）で
 * 各ローダーのつなぎが {@link #remember} を呼び、状態から実体を引けるようにしてある。状態は毎フレーム作り直されるので、
 * 弱い参照の表に置く（使い終わった状態は勝手に消える）。</p>
 */
public final class LivingRender {

    /** 状態 → 実体。状態は equals を持たないので、同じ物かどうかで引ける。描画のスレッドでしか触らないが念のため同期する。 */
    private static final Map<LivingEntityRenderState, Source> ENTITIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final LivingEntityRenderState renderState;
    private final PoseStack poseStack;

    public LivingRender(LivingEntityRenderState renderState, PoseStack poseStack) {
        this.renderState = renderState;
        this.poseStack = poseStack;
    }

    /** 状態の元になった実体と、集めたときの補間の比。 */
    private record Source(LivingEntity entity, float partialTick) {
    }

    /** 状態を集めた直後に呼ぶ（各ローダーのつなぎから）。補間の比は素の状態に入っていないので一緒に覚える。 */
    public static void remember(LivingEntity entity, LivingEntityRenderState state, float partialTick) {
        ENTITIES.put(state, new Source(entity, partialTick));
    }

    /** この状態の元になった実体。つなぎが覚えていなければ null。 */
    public @Nullable LivingEntity getEntity() {
        Source source = ENTITIES.get(renderState);
        return source == null ? null : source.entity();
    }

    public LivingEntityRenderState getRenderState() {
        return renderState;
    }

    public float getPartialTick() {
        Source source = ENTITIES.get(renderState);
        return source == null ? 0.0F : source.partialTick();
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }
}
