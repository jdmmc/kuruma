package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class SteeringHands {

    /** プレイヤーのモデルの拡大率（{@code PlayerRenderer#scale}）。 */
    private static final float PLAYER_SCALE = 0.9375F;
    /** モデルの原点を足元へ下げる量（{@code LivingEntityRenderer#render}）[ブロック]。 */
    private static final float MODEL_DROP = 1.501F;
    /** 腕の付け根から手のひらまで [ブロック]（腕の箱は付け根から 10px、握るのはその少し手前）。 */
    private static final float HAND_REACH = 9.5F / 16.0F;
    /** 腕を伸び縮みさせてでも手をリムに届かせる範囲。これを越える座席の位置では手が離れて見える。 */
    private static final float MIN_STRETCH = 0.75F;
    private static final float MAX_STRETCH = 1.35F;
    /** 握る位置の半径。メッシュの外形の直径に対する割合（リムの太さの真ん中あたり）。 */
    private static final double RIM_RATIO = 0.45;
    /** 持ち替えで手をリムから浮かせる高さ [m]（運転者の側へ）。 */
    private static final double LIFT = 0.07;

    /**
     * 腕の向き。ModelPart の xRot / yRot [rad] と、長さ方向の拡大率。
     *
     * @param right {xRot, yRot, 拡大率}
     * @param left  同じ
     */
    public record Arms(float[] right, float[] left) {
    }

    /** 車ごとの手の握り具合。車が消えれば一緒に捨てる。 */
    private static final Map<CarEntity, Grip> GRIPS = new WeakHashMap<>();

    private static final class Grip {
        final SteeringGrip grip = new SteeringGrip();
        long lastNanos;
        int driverId = -1;
        /** 前のフレームのハンドルの角度 [度]。他人の車で「放している」を推し量るのに使う */
        double lastWheel;
    }

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
        Grip state = GRIPS.computeIfAbsent(car, key -> new Grip());
        long now = System.nanoTime();
        if (state.driverId != driver.getId() || now - state.lastNanos > 1_000_000_000L) {
            // 乗り換えた・しばらく見ていなかった。定位置で握らせ直す
            state.grip.reset(wheel);
            state.driverId = driver.getId();
        } else {
            state.grip.update(wheel, (now - state.lastNanos) / 1.0e9, steeringHeld(car, wheel, state.lastWheel));
        }
        state.lastNanos = now;
        state.lastWheel = wheel;
        return state.grip;
    }

    /**
     * 運転者がハンドルを握っているか。放している間は手を止める（{@link SteeringGrip}）。
     *
     * <p>自分が運転している車は入力（A/D・スティック）をそのまま見る。他人の車は入力が届かないので、
     * <b>ハンドルが中立へ戻っていく間を「放している」と見なす</b>（舵を放すと戻るため）。
     * 自己復元でカウンターへ切れていくのは見分けられず、握っている扱いになる。</p>
     */
    private static boolean steeringHeld(CarEntity car, double wheel, double lastWheel) {
        Boolean input = car.steeringInputHeld();
        if (input != null) {
            return input;
        }
        return !(Math.abs(wheel) < Math.abs(lastWheel) - 0.05);
    }

    /** 車体のメッシュの座標から車体の座標へ（{@link CarObjRenderer#applyBodyTransform}）。 */
    private static Matrix4f bodyToCar(CarModel model, CarSpec spec) {
        PoseStack pose = new PoseStack();
        CarObjRenderer.applyBodyTransform(model, spec, pose);
        return new Matrix4f(pose.last().pose());
    }

    /**
     * リムの上の、見た目の角度 {@code angle}（真上から時計回り）[度] の点を車体の座標で。
     * ハンドルの描き方（{@link CarObjRenderer} の drawSteeringWheel → {@link ObjModel#renderSteeringWheel}）と同じ順で
     * 平行移動と傾きを掛ける。ハンドルの回転ぶんは角度に入っているので掛けない。
     */
    private static Vector3f gripPoint(CarModel model, SteeringWheelAxis.Result axis, CarEntity car,
                                      Matrix4f toCar, double angle, double lift) {
        double[] c = axis.center();
        double[] a = axis.axis();
        // 面内の上（世界の上を面へ落としたもの）と右（上 × 運転者向きの軸）
        double ux = -a[0] * a[1];
        double uy = 1.0 - a[1] * a[1];
        double uz = -a[2] * a[1];
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double rx = uy * a[2] - uz * a[1];
        double ry = uz * a[0] - ux * a[2];
        double rz = ux * a[1] - uy * a[0];

        double fit = CarSpec.DEFAULT.wheelBase() / model.designWheelBase();
        CarModel.Vec3 scale = model.bodyScale();
        double radius = axis.diameter() * RIM_RATIO;
        double theta = Math.toRadians(angle);
        double cos = Math.cos(theta) * radius;
        double sin = Math.sin(theta) * radius;
        double up = lift * LIFT / (fit * scale.y());
        Vector3f v = new Vector3f((float) (ux * cos + rx * sin + a[0] * up),
                (float) (uy * cos + ry * sin + a[1] * up),
                (float) (uz * cos + rz * sin + a[2] * up));

        double tilt = Math.toRadians(CarSeat.currentSteeringTilt(car.getCarId()));
        if (tilt != 0.0) {
            v.rotate(new Quaternionf().rotationX((float) -tilt));
        }
        CarModel.Vec3 offset = CarSeat.currentSteering(car.getCarId());
        v.add((float) (c[0] + offset.x() / (fit * scale.x())),
                (float) (c[1] + offset.y() / (fit * scale.y())),
                (float) (c[2] + offset.z() / (fit * scale.z())));
        return toCar.transformPosition(v);
    }

    /**
     * 肩から握る位置へ腕を向ける。
     *
     * <p>腕は付け根から +Y（モデルの下）へ伸びていて、ModelPart は Rz·Ry·Rx の順に回す。zRot を 0 にすると
     * 向きは (sin y·sin x, cos x, cos y·sin x) なので、x = −acos(向きの y)、y = atan2(−向きの x, −向きの z)
     * （前へ出すとき sin x が負）。</p>
     *
     * @param pivotX 付け根の X [ブロック]（モデルの座標。右腕が負）
     */
    private static float[] arm(Vector3f feet, float pivotX, float pivotY, Vector3f grip) {
        // 肩（車体の座標）
        float sx = feet.x - PLAYER_SCALE * pivotX;
        float sy = feet.y - PLAYER_SCALE * (pivotY - MODEL_DROP);
        float sz = feet.z;
        // 肩から手へ（モデルの座標。Rz(180) を戻して拡大で割る）
        float dx = -(grip.x - sx) / PLAYER_SCALE;
        float dy = -(grip.y - sy) / PLAYER_SCALE;
        float dz = (grip.z - sz) / PLAYER_SCALE;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-4F) {
            return new float[]{0.0F, 0.0F, 1.0F};
        }
        float x = (float) -Math.acos(Math.max(-1.0F, Math.min(1.0F, dy / length)));
        float y = (float) Math.atan2(-dx, -dz);
        float stretch = Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, length / HAND_REACH));
        return new float[]{x, y, stretch};
    }

    /** モデルの腕へ向きを入れる。袖も同じ向き・同じ伸びにする。 */
    public static void apply(PlayerModel<?> model, Arms arms) {
        set(model.rightArm, arms.right());
        set(model.leftArm, arms.left());
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.yScale = model.rightArm.yScale;
        model.leftSleeve.yScale = model.leftArm.yScale;
    }

    private static void set(ModelPart part, float[] arm) {
        part.xRot = arm[0];
        part.yRot = arm[1];
        part.zRot = 0.0F;
        part.yScale = arm[2];
    }

    /** 腕の伸びを元へ戻す。モデルは全員で使い回しているので、運転者以外に伸びを残さない。 */
    public static void resetStretch(PlayerModel<?> model) {
        model.rightArm.yScale = 1.0F;
        model.leftArm.yScale = 1.0F;
        model.rightSleeve.yScale = 1.0F;
        model.leftSleeve.yScale = 1.0F;
    }

    /**
     * 三人称（プレイヤーのモデルの姿勢を決め終えたところ。{@code mixin/PlayerModelMixin}）。
     *
     * @param partialTick ageInTicks − tickCount
     */
    public static void poseModel(PlayerModel<?> model, LivingEntity entity, float partialTick) {
        resetStretch(model);
        CarEntity car = drivenCar(entity);
        if (car == null) {
            return;
        }
        Arms arms = arms(car, entity, partialTick, isSlim(entity));
        if (arms != null) {
            apply(model, arms);
        }
    }

    /** 細い腕のモデルか（肩の高さが 0.5px 違う）。プレイヤー以外は太い腕。 */
    private static boolean isSlim(LivingEntity entity) {
        return entity instanceof AbstractClientPlayer player && "slim".equals(player.getModelName());
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
    public static void renderFirstPerson(CarEntity car, PoseStack pose, MultiBufferSource buffer,
                                         int packedLight, float partialTick) {
        if (!drivingInFirstPerson(car)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = (AbstractClientPlayer) minecraft.getCameraEntity();
        EntityRenderer<? super AbstractClientPlayer> renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerRenderer playerRenderer) || player.isInvisible()) {
            return;
        }
        boolean slim = isSlim(player);
        Arms arms = arms(car, player, partialTick, slim);
        if (arms == null) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
        ResourceLocation skin = player.getSkinTextureLocation();
        float shoulderY = slim ? 2.5F : 2.0F;

        Vec3 seat = car.riderSeatLocal(player);
        pose.pushPose();
        // 三人称の描き方（LivingEntityRenderer）と同じ: 足元へ寄せ、上下と左右を返し、縮めて原点を下げる
        pose.translate(-seat.x, seat.y, -seat.z);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
        pose.translate(0.0F, -MODEL_DROP, 0.0F);

        model.rightArm.setPos(-5.0F, shoulderY, 0.0F);
        model.leftArm.setPos(5.0F, shoulderY, 0.0F);
        model.rightArm.visible = true;
        model.leftArm.visible = true;
        apply(model, arms);
        var solid = buffer.getBuffer(RenderType.entitySolid(skin));
        model.rightArm.render(pose, solid, packedLight, OverlayTexture.NO_OVERLAY);
        model.leftArm.render(pose, solid, packedLight, OverlayTexture.NO_OVERLAY);
        var layer = buffer.getBuffer(RenderType.entityTranslucent(skin));
        if (player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE)) {
            model.rightSleeve.render(pose, layer, packedLight, OverlayTexture.NO_OVERLAY);
        }
        if (player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE)) {
            model.leftSleeve.render(pose, layer, packedLight, OverlayTexture.NO_OVERLAY);
        }
        resetStretch(model);
        pose.popPose();
    }

    /**
     * 一人称で運転している間は、バニラの手（持っている物）を描かない。腕はハンドルの上に描いているので、
     * 画面の右下にもう 1 本の手が出てしまう。ハンドルが無い車では消さない。
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
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
