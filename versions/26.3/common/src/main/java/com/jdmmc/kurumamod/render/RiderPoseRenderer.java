package com.jdmmc.kurumamod.render;

import com.jdmmc.kurumamod.client.hook.LevelRenderStage;
import com.jdmmc.kurumamod.client.hook.LivingRender;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 乗員のモデルを<b>車体と一緒に傾けて描く</b>。運転者の腕をハンドルへ向けるのもここが段取りする。
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
 *   <li><b>世界を描いている間だけ。</b>インベントリ画面の人形に世界の座標のずれを足すと人形が画面の外へ飛ぶ</li>
 * </ul>
 *
 * <h2>26.3 では「集める段」で決めて「出す段」で掛ける</h2>
 *
 * <p>26.3 の描画は、先に生き物ごとの状態（{@code LivingEntityRenderState}）を集めてから、あとで状態だけを
 * 見て描く。描く段では<b>もう生き物にも車にも触れない</b>ので、集める段（{@code mixin/RiderExtractMixin}。
 * 世界の生き物だけが通る {@code EntityRenderDispatcher#extractEntity}）で傾きと腕の向きを決めて状態に紐づけ、
 * 描く段（{@code mixin/RiderRenderMixin}）で行列に掛ける。インベントリの人形は別の口で状態を作るので
 * ここを通らず、傾かない。</p>
 */
public final class RiderPoseRenderer {

    private RiderPoseRenderer() {
    }

    /** 集めた状態ごとの、描くときに掛ける行列と腕の向き。状態が捨てられれば一緒に消える。 */
    private static final Map<EntityRenderState, Pose> POSES = new WeakHashMap<>();

    /**
     * @param tilt 足元を補間済みの車へ寄せ、車体の傾きを掛ける行列
     * @param arms 運転者なら腕の向き（ハンドルが無ければ null）
     */
    private record Pose(Matrix4f tilt, @Nullable SteeringHands.Arms arms) {
    }

    /**
     * 世界の生き物の状態を集め終えたところ（{@code mixin/RiderExtractMixin}）。
     */
    public static void onExtract(Entity entity, EntityRenderState state, float partial) {
        if (!(state instanceof LivingEntityRenderState living) || !(entity instanceof LivingEntity rider)
                || !(entity.getVehicle() instanceof CarEntity car)) {
            POSES.remove(state);
            return;
        }
        Matrix4f tilt = new Matrix4f();
        Vec3 feet = car.riderFeetPosition(rider, partial);
        Vec3 drawn = entity.getPosition(partial);
        tilt.translate((float) (feet.x - drawn.x), (float) (feet.y - drawn.y), (float) (feet.z - drawn.z));

        float yaw = 180.0F - car.getRenderYaw(partial);
        tilt.rotate(Axis.YP.rotationDegrees(yaw));
        tilt.rotate(Axis.XP.rotationDegrees(car.getRenderPitch(partial) * Mth.RAD_TO_DEG));
        tilt.rotate(Axis.ZP.rotationDegrees(-car.getRenderRoll(partial) * Mth.RAD_TO_DEG));
        tilt.rotate(Axis.YP.rotationDegrees(-yaw));

        SteeringHands.Arms arms = null;
        // 運転者の体は車の正面へ向ける。ハンドルを握る腕は車体の中で肩の位置から組み立てる
        // （SteeringHands）ので、体が首と一緒に回っていると腕が肩ごとずれる。状態だけを書き換える
        if (SteeringHands.drivenCar(rider) == car) {
            float body = car.getRenderYaw(partial);
            float head = living.bodyRot + living.yRot;
            living.bodyRot = body;
            living.yRot = Mth.wrapDegrees(head - body);
            arms = SteeringHands.arms(car, rider, partial, isSlim(rider));
        }
        POSES.put(state, new Pose(tilt, arms));
    }

    /** 細い腕のモデルか（肩の高さが 0.5px 違う）。プレイヤー以外は太い腕。 */
    static boolean isSlim(LivingEntity entity) {
        return entity instanceof AbstractClientPlayer player && player.getSkin().model() == PlayerModelType.SLIM;
    }

    /**
     * 生き物を描き始めるところ（{@code mixin/RiderRenderMixin}、{@code LivingEntityRenderer#submit} の頭）。
     *
     * @return 行列を積んだか。積んだら {@link #afterSubmit} で戻すこと
     */
    public static boolean beforeSubmit(EntityRenderState state, PoseStack pose) {
        Pose found = POSES.get(state);
        if (found == null) {
            return false;
        }
        pose.pushPose();
        pose.mulPose(found.tilt());
        return true;
    }

    /** {@link #beforeSubmit} で積んだ行列を戻す。 */
    public static void afterSubmit(PoseStack pose) {
        pose.popPose();
    }

    /** この状態の運転者の腕の向き。運転者でなければ null（{@code mixin/PlayerModelMixin}）。 */
    @Nullable
    public static SteeringHands.Arms armsOf(EntityRenderState state) {
        Pose found = POSES.get(state);
        return found == null ? null : found.arms();
    }

    /**
     * 1.21.1 では世界を描いている最中かをここで見ていた。26.3 版では使わない（集める口で見分ける）。
     * 呼ぶ側が残っていてもよいよう、口だけ残す。
     */
    public static void onRenderLevelStage(LevelRenderStage event) {
        // 何もしない
    }

    /** 1.21.1 ではここで行列を積んでいた。26.3 版では {@code mixin/RiderRenderMixin} が受け持つ。口だけ残す。 */
    public static void onRenderLivingPre(LivingRender event) {
        // 何もしない
    }

    /** 同上。 */
    public static void onRenderLivingPost(LivingRender event) {
        // 何もしない
    }
}
