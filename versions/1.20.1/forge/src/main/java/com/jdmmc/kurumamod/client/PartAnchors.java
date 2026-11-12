package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.EnumMap;
import java.util.Map;
import com.jdmmc.kurumamod.client.model.CarModel;
import com.jdmmc.kurumamod.client.screen.CarPartScreen;
import com.jdmmc.kurumamod.client.model.PartAnchorLayout;
import com.jdmmc.kurumamod.client.model.PlateMounts;

/**
 * 換装画面（{@link CarPartScreen}）の札を立てる場所。<b>車の部位を、このフレームの画面の位置へ写したもの。</b>
 *
 * <h2>描画の途中で測る</h2>
 *
 * <p>画面（GUI）を描く頃には世界の行列が残っていないので、<b>世界を描き始めたところ
 * （{@code AFTER_SKY}）で部位の位置を出して画面へ写しておく</b>。同じフレームの中なので、
 * カメラを回している最中でも札が車から遅れない。</p>
 *
 * <p>どの部位をどこに立てるか（車体の外形の何割の位置か、左右のどちらか）は core の {@link PartAnchorLayout}。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY
                || MirrorRenderer.isRendering() || DeferredGlows.skip()) {
            return;
        }
        POINTS.clear();
        paint = null;
        center = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(McUiScreen.unwrap(minecraft.screen) instanceof CarPartScreen)) {
            return;
        }
        CarEntity car = CarClientDriver.driving();
        if (car == null) {
            return;
        }
        float partialTick = event.getPartialTick();
        // 1.20.1 は PoseStack にカメラの回転が入っている（位置は入っていない＝カメラ基準）
        Matrix4f clip = new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
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
        Matrix4f bodyFrame = new Matrix4f(rig.last().pose());

        // どの部位をどこに立てるかは core の PartAnchorLayout。ここは行列で画面へ写すだけ
        double[] suspension = new double[Wheel.COUNT];
        for (Wheel wheel : Wheel.VALUES) {
            suspension[wheel.ordinal()] = car.getRenderSuspensionLength(wheel, partialTick);
        }
        PlateMounts.Mounts mounts = PlateMounts.get(car.getCarId());
        PlateMounts.Mount front = PlateMounts.pick(mounts.front(), car.getFitment().plate().front());
        PlateMounts.Mount rear = PlateMounts.pick(mounts.rear(), car.getFitment().plate().rear());
        PartAnchorLayout.Projector frames = new PartAnchorLayout.Projector() {
            @Override
            public float[] project(PartAnchorLayout.Frame frame, double x, double y, double z) {
                return projector.project(frame == PartAnchorLayout.Frame.CAR ? carFrame : bodyFrame, x, y, z);
            }

            @Override
            public double distance(PartAnchorLayout.Frame frame, double[] point) {
                return projector.distance(frame == PartAnchorLayout.Frame.CAR ? carFrame : bodyFrame, point);
            }
        };
        float[][] marks = PartAnchorLayout.layout(frames, spec, suspension, ObjModel.get(model.bodyModel()).bounds(),
                front != null ? new double[]{front.x(), front.y(), front.z()} : null,
                rear != null ? new double[]{rear.x(), rear.y(), rear.z()} : null, POINTS);
        center = marks[0];
        paint = marks[1];
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
