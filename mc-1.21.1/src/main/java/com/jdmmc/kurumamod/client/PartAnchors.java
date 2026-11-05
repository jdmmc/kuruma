package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.hook.LevelRenderStage;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.EnumMap;
import java.util.Map;

/**
 * 換装画面（{@link CarPartScreen}）の札を立てる場所。<b>車の部位を、このフレームの画面の位置へ写したもの。</b>
 *
 * <h2>描画の途中で測る</h2>
 *
 * <p>画面（GUI）を描く頃には世界の行列が残っていないので、<b>世界を描き始めたところ
 * （{@code AFTER_SKY}）で部位の位置を出して画面へ写しておく</b>。同じフレームの中なので、
 * カメラを回している最中でも札が車から遅れない。</p>
 *
 * <h2>部位の位置は車体の外形から割り出す</h2>
 *
 * <p>カーパックに「ウイングはここ」と書いてもらう項目は無いので、<b>車体メッシュを囲む箱</b>の
 * 決まった割合の位置に置く（ウイングは後端の上、リップは前端の下）。ホイールとタイヤだけは
 * 物理がタイヤを置く位置そのものを使う。正確な一点である必要はなく、「このあたり」が分かれば足りる。</p>
 *
 * <p><b>左右のある部位は、カメラに近い側に置く。</b>右に固定すると、左から見ているときに札が
 * 車の向こう側を指して、線が車体を突き抜ける。</p>
 */
public final class PartAnchors {

    /** 部位ごとの画面の位置 {x, y}（GUI 座標）。写せなかったもの（カメラの後ろ）は入らない。 */
    private static final Map<PartSlot, float[]> POINTS = new EnumMap<>(PartSlot.class);
    @Nullable
    private static float[] paint;
    @Nullable
    private static float[] center;

    private PartAnchors() {
    }

    /** その部位の画面の位置。まだ測っていない・写せなければ null。 */
    @Nullable
    static float[] point(PartSlot slot) {
        return POINTS.get(slot);
    }

    /** 塗装の札を立てる場所（屋根）。 */
    @Nullable
    static float[] paint() {
        return paint;
    }

    /** 車の中心の画面の位置。札はここから外へ向けて逃がす。 */
    @Nullable
    static float[] center() {
        return center;
    }

    public static void onRenderLevel(LevelRenderStage event) {
        if (event.getStage() != LevelRenderStage.Stage.AFTER_SKY
                || MirrorRenderer.isRendering() || DeferredGlows.skip()) {
            return;
        }
        POINTS.clear();
        paint = null;
        center = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CarPartScreen)) {
            return;
        }
        CarEntity car = CarClientDriver.driving();
        if (car == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        // 1.20.5 からカメラの回転は PoseStack ではなく別の行列で渡される（位置は入っていない＝カメラ基準）。
        // PoseStack から取ると単位行列になり、札が車とまったく違う場所に出る
        Matrix4f clip = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        Projector projector = new Projector(clip, event.getCamera().getPosition(), car.getPosition(partialTick),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());

        CarModel model = CarModel.get(car.getCarId());
        CarSpec spec = car.getSpec();
        PoseStack rig = new PoseStack();
        CarObjRenderer.applyRotations(car, rig, partialTick);
        // 姿勢だけ掛けた座標（+X 右・+Y 上・-Z 前、原点は重心）。タイヤはこちらで置く
        Matrix4f carFrame = new Matrix4f(rig.last().pose());
        CarObjRenderer.applyBodyTransform(model, spec, rig);
        // 車体メッシュの座標。外形の箱はこちらで測る
        Matrix4f bodyFrame = rig.last().pose();

        center = projector.project(carFrame, 0.0, 0.3, 0.0);

        // タイヤ。ホイールは前輪、タイヤは後輪に立てて、2 枚の札が同じ点を指さないようにする
        float[] frontWheel = nearer(projector, carFrame, wheelPoint(car, spec, Wheel.FRONT_RIGHT, partialTick, 0.0),
                wheelPoint(car, spec, Wheel.FRONT_LEFT, partialTick, 0.0));
        put(PartSlot.WHEEL, frontWheel);
        put(PartSlot.TIRE, nearer(projector, carFrame,
                wheelPoint(car, spec, Wheel.REAR_RIGHT, partialTick, -0.6),
                wheelPoint(car, spec, Wheel.REAR_LEFT, partialTick, -0.6)));
        put(PartSlot.FENDER, nearer(projector, carFrame,
                wheelPoint(car, spec, Wheel.FRONT_RIGHT, partialTick, 1.15),
                wheelPoint(car, spec, Wheel.FRONT_LEFT, partialTick, 1.15)));

        double[] box = ObjModel.get(model.bodyModel()).bounds();
        double minX = box[0], minY = box[1], minZ = box[2], maxX = box[3], maxY = box[4], maxZ = box[5];
        double width = maxX - minX, height = maxY - minY, depth = maxZ - minZ;
        double midX = (minX + maxX) * 0.5, midZ = (minZ + maxZ) * 0.5;
        // メッシュも -Z が前。minZ が鼻先、maxZ が後端
        put(PartSlot.FRONT_BUMPER, projector.project(bodyFrame, midX, minY + height * 0.18, minZ));
        put(PartSlot.TURBO, projector.project(bodyFrame, midX - width * 0.12, minY + height * 0.62, minZ + depth * 0.22));
        put(PartSlot.BLOWOFF, projector.project(bodyFrame, midX + width * 0.15, minY + height * 0.62, minZ + depth * 0.30));
        put(PartSlot.REAR_WING, projector.project(bodyFrame, midX, minY + height * 0.80, maxZ - depth * 0.06));
        put(PartSlot.REAR_DIFFUSER, projector.project(bodyFrame, midX, minY + height * 0.10, maxZ));
        put(PartSlot.SIDE_SKIRT, nearer(projector, bodyFrame,
                new double[]{maxX, minY + height * 0.12, midZ}, new double[]{minX, minY + height * 0.12, midZ}));
        put(PartSlot.EXHAUST, nearer(projector, bodyFrame,
                new double[]{midX + width * 0.3, minY + height * 0.12, maxZ},
                new double[]{midX - width * 0.3, minY + height * 0.12, maxZ}));
        paint = projector.project(bodyFrame, midX, maxY, midZ + depth * 0.05);

        // ナンバーは付ける位置（車体メッシュの座標）。前後のうちカメラに近いほう
        PlateMounts.Mounts mounts = PlateMounts.get(car.getCarId());
        PlateMounts.Mount front = PlateMounts.pick(mounts.front(), car.getFitment().plate().front());
        PlateMounts.Mount rear = PlateMounts.pick(mounts.rear(), car.getFitment().plate().rear());
        double[] frontPoint = front != null ? new double[]{front.x(), front.y(), front.z()}
                : new double[]{midX, minY + height * 0.3, minZ};
        double[] rearPoint = rear != null ? new double[]{rear.x(), rear.y(), rear.z()}
                : new double[]{midX, minY + height * 0.4, maxZ};
        put(PartSlot.PLATE, nearer(projector, bodyFrame, frontPoint, rearPoint));
    }

    private static void put(PartSlot slot, @Nullable float[] point) {
        if (point != null) {
            POINTS.put(slot, point);
        }
    }

    /**
     * タイヤの中心から、半径の {@code up} 倍だけ上の点（姿勢だけ掛けた座標）。
     * 置き方は {@code CarObjRenderer#renderWheels} と同じ（左右・前後は諸元、高さはサスの長さ）。
     */
    private static double[] wheelPoint(CarEntity car, CarSpec spec, Wheel wheel, float partialTick, double up) {
        return new double[]{
                spec.wheelRightOffset(wheel),
                -car.getRenderSuspensionLength(wheel, partialTick) + spec.wheelRadius() * up,
                -spec.wheelForwardOffset(wheel)};
    }

    /** 2 つの候補のうち、カメラに近いほうを写す。 */
    @Nullable
    private static float[] nearer(Projector projector, Matrix4f frame, double[] a, double[] b) {
        double[] chosen = projector.distance(frame, a) <= projector.distance(frame, b) ? a : b;
        return projector.project(frame, chosen[0], chosen[1], chosen[2]);
    }

    /** 車の座標の点を、世界を経て画面の位置へ写す。 */
    private record Projector(Matrix4f clip, Vec3 eye, Vec3 origin, int guiWidth, int guiHeight) {

        Vec3 world(Matrix4f frame, double x, double y, double z) {
            Vector4f local = frame.transform(new Vector4f((float) x, (float) y, (float) z, 1.0F));
            return origin.add(local.x(), local.y(), local.z());
        }

        double distance(Matrix4f frame, double[] point) {
            return world(frame, point[0], point[1], point[2]).distanceToSqr(eye);
        }

        @Nullable
        float[] project(Matrix4f frame, double x, double y, double z) {
            Vec3 relative = world(frame, x, y, z).subtract(eye);
            Vector4f p = clip.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F));
            // カメラの後ろ（と、ほぼ真横）は写さない。割ると符号が反転して反対側に出る
            if (p.w() <= 0.05F) {
                return null;
            }
            float ndcX = p.x() / p.w();
            float ndcY = p.y() / p.w();
            return new float[]{(ndcX + 1.0F) * 0.5F * guiWidth, (1.0F - ndcY) * 0.5F * guiHeight};
        }
    }
}
