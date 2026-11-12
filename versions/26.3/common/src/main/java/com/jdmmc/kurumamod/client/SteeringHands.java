package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.HandRender;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 運転者の手をハンドルに置く。<b>三人称はプレイヤーのモデルの腕を向け直し</b>（{@code mixin/PlayerModelMixin}）、
 * <b>一人称は車を描くときに自分の腕を描き足す</b>（バニラは一人称で自分の体を描かないため）。
 *
 * <p>どちらも同じ計算を通る。車体の座標（{@link CarObjRenderer#applyRotations} を掛けた後。+X 右・+Y 上・-Z 前）で
 * 肩と握る位置を出し、肩から握る位置へ腕を向ける。握る位置はハンドルのメッシュから読んだ中心・軸・直径
 * （{@link SteeringWheelAxis}）と、Ctrl+G で足した位置と傾き。どこを握るかは {@link SteeringGrip}
 * （大きく切ると片手ずつ持ち替える）。</p>
 *
 * <p>体の向きは車に揃える（{@code RiderPoseRenderer}）。肩の位置がずれると腕の向きもずれる。
 * ハンドルが無い車・平たいと言えないハンドル・助手席では何もしない（バニラの姿勢のまま）。</p>
 */
public final class SteeringHands {


    /**
     * 腕の向き。ModelPart の xRot / yRot [rad] と、長さ方向の拡大率。
     *
     * @param right {xRot, yRot, 拡大率}
     * @param left  同じ
     */
    public record Arms(float[] right, float[] left) {
    }

    /** 車ごとの手の握り具合。車が消えれば一緒に捨てる。 */
    private static final Map<CarEntity, SteeringArms.Grip> GRIPS = new WeakHashMap<>();

    private SteeringHands() {
    }

    /** この乗員がハンドルを握る人（運転者）なら、その車。 */
    @Nullable
    public static CarEntity drivenCar(Entity entity) {
        return entity.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == entity
                && !car.isGhost() ? car : null;
    }

    /**
     * 腕の向きを出す。ハンドルが無いか軸が取れていなければ null。
     *
     * @param slim 細い腕のモデルか（肩の高さが 0.5px 違う）
     */
    @Nullable
    public static Arms arms(CarEntity car, LivingEntity driver, float partialTick, boolean slim) {
        CarModel model = CarModel.get(car.getCarId());
        ObjModel body = ObjModel.get(model.bodyModel());
        SteeringWheelAxis.Result axis = body.steeringAxis(CarSeat.currentSteeringColumn(car.getCarId()),
                CarSeat.currentSteeringCenter(car.getCarId()));
        if (axis == null) {
            return null;
        }
        CarSpec spec = car.getSpec();
        double wheel = Math.toDegrees(CarObjRenderer.handwheelAngle(car, spec, partialTick));
        SteeringGrip grip = gripOf(car, driver, wheel);

        // 肩（車体の座標）。足元からモデルの付け根まで: 足元 + Rz(180)·0.9375·(付け根 − 原点の下げ)
        Vec3 seat = car.riderSeatLocal(driver);
        Vector3f feet = new Vector3f((float) -seat.x, (float) seat.y, (float) -seat.z);
        float shoulderY = (slim ? 2.5F : 2.0F) / 16.0F;
        Matrix4f toCar = bodyToCar(model, spec);
        float[] right = arm(feet, -5.0F / 16.0F, shoulderY,
                gripPoint(model, axis, car, toCar, grip.angle(true), grip.lift(true)));
        float[] left = arm(feet, 5.0F / 16.0F, shoulderY,
                gripPoint(model, axis, car, toCar, grip.angle(false), grip.lift(false)));
        return new Arms(right, left);
    }

    private static SteeringGrip gripOf(CarEntity car, LivingEntity driver, double wheel) {
        return GRIPS.computeIfAbsent(car, key -> new SteeringArms.Grip())
                .update(driver.getId(), wheel, car.steeringInputHeld());
    }

    /** 車体のメッシュの座標から車体の座標へ（{@link CarObjRenderer#applyBodyTransform}）。 */
    private static Matrix4f bodyToCar(CarModel model, CarSpec spec) {
        PoseStack pose = new PoseStack();
        CarObjRenderer.applyBodyTransform(model, spec, pose);
        return new Matrix4f(pose.last().pose());
    }

    /**
     * リムの上の、見た目の角度 {@code angle}（真上から時計回り）[度] の点を車体の座標で（{@link SteeringArms#rimPoint}）。
     * ハンドルの描き方（{@link CarObjRenderer} の drawSteeringWheel → {@link ObjModel#renderSteeringWheel}）と同じ順で
     * 平行移動と傾きを掛ける。ハンドルの回転ぶんは角度に入っているので掛けない。
     */
    private static Vector3f gripPoint(CarModel model, SteeringWheelAxis.Result axis, CarEntity car,
                                      Matrix4f toCar, double angle, double lift) {
        double fit = CarSpec.DEFAULT.wheelBase() / model.designWheelBase();
        CarModel.Vec3 scale = model.bodyScale();
        CarModel.Vec3 offset = CarSeat.currentSteering(car.getCarId());
        float[] p = SteeringArms.rimPoint(axis.center(), axis.axis(), axis.diameter(), fit,
                new double[]{scale.x(), scale.y(), scale.z()}, angle, lift,
                CarSeat.currentSteeringTilt(car.getCarId()), new double[]{offset.x(), offset.y(), offset.z()});
        return toCar.transformPosition(new Vector3f(p[0], p[1], p[2]));
    }

    /** 肩から握る位置へ腕を向ける（{@link SteeringArms#arm}）。 */
    private static float[] arm(Vector3f feet, float pivotX, float pivotY, Vector3f grip) {
        return SteeringArms.arm(feet.x, feet.y, feet.z, pivotX, pivotY, grip.x, grip.y, grip.z);
    }

    /**
     * モデルの腕へ向きを入れる。
     *
     * <p>26.3 では袖は腕の子なので、腕を向ければ袖も付いてくる（1.21.1 のように写さなくてよい。写すと
     * 回転が二重に掛かる）。伸び（{@code yScale}）も子に掛かる。</p>
     */
    public static void apply(HumanoidModel<?> model, Arms arms) {
        set(model.rightArm, arms.right());
        set(model.leftArm, arms.left());
    }

    private static void set(ModelPart part, float[] arm) {
        part.xRot = arm[0];
        part.yRot = arm[1];
        part.zRot = 0.0F;
        part.yScale = arm[2];
    }

    /** 腕の伸びを元へ戻す。モデルは全員で使い回しているので、運転者以外に伸びを残さない。 */
    public static void resetStretch(HumanoidModel<?> model) {
        model.rightArm.yScale = 1.0F;
        model.leftArm.yScale = 1.0F;
    }

    /**
     * 三人称（人型のモデルの姿勢を決め終えたところ。{@code mixin/PlayerModelMixin}）。
     *
     * <p>腕の向きは状態を集める段で決めてある（{@link RiderPoseRenderer#onExtract}）。描く段では車に触れない。
     * 防具のモデルも同じ状態で姿勢を決めるので、ここを通って袖の防具も一緒に向く。</p>
     */
    public static void poseModel(HumanoidModel<?> model, EntityRenderState state) {
        Arms arms = RiderPoseRenderer.armsOf(state);
        if (arms != null) {
            apply(model, arms);
        }
    }

    /** 細い腕のモデルか（肩の高さが 0.5px 違う）。プレイヤー以外は太い腕。 */
    private static boolean isSlim(LivingEntity entity) {
        return RiderPoseRenderer.isSlim(entity);
    }

    /** 一人称で、自分が運転している車か。 */
    private static boolean drivingInFirstPerson(CarEntity car) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options.getCameraType().isFirstPerson()
                && minecraft.getCameraEntity() instanceof AbstractClientPlayer player
                && drivenCar(player) == car;
    }

    /**
     * 一人称で、自分の腕をハンドルに描く。{@link CarObjRenderer#applyRotations} を掛けた後に呼ぶこと。
     */
    public static void renderFirstPerson(CarEntity car, PoseStack pose, SubmitNodeCollector buffer,
                                         int packedLight, float partialTick) {
        if (!drivingInFirstPerson(car)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = (AbstractClientPlayer) minecraft.getCameraEntity();
        EntityRenderer<? super AbstractClientPlayer, ?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof AvatarRenderer<?> playerRenderer) || player.isInvisible()) {
            return;
        }
        boolean slim = isSlim(player);
        Arms arms = arms(car, player, partialTick, slim);
        if (arms == null) {
            return;
        }
        PlayerModel model = playerRenderer.getModel();
        Identifier skin = player.getSkin().body().texturePath();
        float shoulderY = slim ? 2.5F : 2.0F;
        boolean rightSleeve = player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
        boolean leftSleeve = player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);

        Vec3 seat = car.riderSeatLocal(player);
        pose.pushPose();
        // 三人称の描き方（LivingEntityRenderer）と同じ: 足元へ寄せ、上下と左右を返し、縮めて原点を下げる
        pose.translate(-seat.x, seat.y, -seat.z);
        pose.rotate(Axis.ZP.rotationDegrees(180.0F));
        pose.scale(SteeringArms.PLAYER_SCALE, SteeringArms.PLAYER_SCALE, SteeringArms.PLAYER_SCALE);
        pose.translate(0.0F, -SteeringArms.MODEL_DROP, 0.0F);

        // モデルは全員で使い回していて、描かれるのは後（ほかの人の姿勢で上書きされている）。
        // 腕の向きは描く直前に入れ直す。袖は腕の子なので腕と一緒に描かれる
        buffer.submitCustomGeometry(pose, RenderTypes.entityTranslucent(skin), (entry, consumer) -> {
            PoseStack stack = ObjModel.stackOf(entry);
            model.rightArm.resetPose();
            model.leftArm.resetPose();
            model.rightArm.setPos(-5.0F, shoulderY, 0.0F);
            model.leftArm.setPos(5.0F, shoulderY, 0.0F);
            model.rightArm.visible = true;
            model.leftArm.visible = true;
            model.rightSleeve.visible = rightSleeve;
            model.leftSleeve.visible = leftSleeve;
            apply(model, arms);
            model.rightArm.render(stack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
            model.leftArm.render(stack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
            resetStretch(model);
        });
        pose.popPose();
    }

    /**
     * 一人称で運転している間は、バニラの手（持っている物）を描かない。腕はハンドルの上に描いているので、
     * 画面の右下にもう 1 本の手が出てしまう。ハンドルが無い車では消さない。
     */
    public static void onRenderHand(HandRender event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        CarEntity car = drivenCar(minecraft.player);
        if (car != null && ObjModel.get(CarModel.get(car.getCarId()).bodyModel())
                .steeringAxis(CarSeat.currentSteeringColumn(car.getCarId())) != null) {
            event.setCanceled(true);
        }
    }
}
