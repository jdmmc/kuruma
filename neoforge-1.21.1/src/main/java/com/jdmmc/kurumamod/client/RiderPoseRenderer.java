package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 乗員のモデルを<b>車体と一緒に傾けて描く</b>。
 *
 * <p>{@code CarEntity#positionRider} は席の位置を車体の姿勢で回しているが、そこに立つモデルは
 * 垂直のままなので、片輪を乗り上げて車がロールすると<b>頭が屋根から横へはみ出した</b>。
 * 描画の行列にだけ車体の傾きを掛ける。</p>
 *
 * <ul>
 *   <li><b>プレイヤーの向きや位置そのものは触らない。</b>向きを書き換えると {@link CarCamera} が
 *       「自分でマウスを動かした」と誤解して追従を止める</li>
 *   <li><b>足元を補間済みの車から組み直す。</b>{@code positionRider} は 20Hz なので、乗員自身の補間と
 *       車体の姿勢の補間がずれ、傾きだけ滑らかで足元が階段状に揺れる</li>
 *   <li><b>回す軸は足元。</b>席の位置はもう傾けてあるので、残っているのは足から上の向きだけ。
 *       ヨーを外して傾きを掛け、ヨーを戻す（{@code CarObjRenderer#applyRotations} と同じ順・符号）</li>
 *   <li><b>世界を描いている間だけ。</b>インベントリ画面の人形も同じイベントを通るので、
 *       そこで世界の座標のずれを足すと人形が画面の外へ飛ぶ</li>
 * </ul>
 */
@EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class RiderPoseRenderer {

    private RiderPoseRenderer() {
    }

    /** 世界を描いている最中か（空を描いた後から、世界を描き終えるまで）。 */
    private static boolean inLevel;

    /** Pre で積んだ乗員。Post で同じ相手のときだけ戻す（行列を漏らさないため）。 */
    private static final Deque<LivingEntity> PUSHED = new ArrayDeque<>();

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            inLevel = true;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            inLevel = false;
            PUSHED.clear();
        }
    }

    /** 他の MOD がキャンセルしたら Post は来ないので、最後に受けてキャンセル済みは見ない。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!inLevel || !(entity.getVehicle() instanceof CarEntity car)) {
            return;
        }
        float partial = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        PUSHED.push(entity);

        Vec3 feet = car.riderFeetPosition(entity, partial);
        Vec3 drawn = entity.getPosition(partial);
        pose.translate(feet.x - drawn.x, feet.y - drawn.y, feet.z - drawn.z);

        float yaw = 180.0F - car.getRenderYaw(partial);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(car.getRenderPitch(partial) * Mth.RAD_TO_DEG));
        pose.mulPose(Axis.ZP.rotationDegrees(-car.getRenderRoll(partial) * Mth.RAD_TO_DEG));
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (!PUSHED.isEmpty() && PUSHED.peek() == event.getEntity()) {
            PUSHED.pop();
            event.getPoseStack().popPose();
        }
    }
}
