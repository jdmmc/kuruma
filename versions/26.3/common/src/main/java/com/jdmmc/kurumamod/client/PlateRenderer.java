package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Function;

/**
 * ナンバープレートを描く。<b>車体の姿勢を掛けた後、車体の拡大（{@code body.scale}）を掛ける前</b>に呼ぶ。
 *
 * <h2>実寸のまま描く</h2>
 *
 * <p>付ける位置（{@link PlateMounts}）は車体メッシュの座標で書かれているので、車体と同じ変換
 * （{@link CarObjRenderer#applyBodyTransform}）で動かす。<b>プレート自体は拡大しない</b>——車体を
 * 大きく作った車でもナンバーは同じ大きさ。ただし実寸のままでは車体に対して小さいので、種類ごとの倍率
 * （{@link PlateModel#scale()}。既定 1.2）を掛けて描く。</p>
 *
 * <h2>字はテクスチャに焼いてある</h2>
 *
 * <p>字は別に描かず、{@link PlateTextures} が地の絵に焼き込んだ 1 枚を貼る。そのため車体と同じ
 * {@code RenderType} で描けて、ゴーストの半透明・ヘッドライトの照り返し・シェーダーパックがそのまま効く。</p>
 *
 * <h2>ナンバー灯</h2>
 *
 * <p>ここでは明るくしない。後ろのプレートの上にヘッドライトと同じ種類の光を 1 灯置き（{@link #rearLamp}）、
 * 路面と同じ仕組みでプレートを照らす。L でライトを点けている間だけ。</p>
 *
 * <h2>シェーダーパックの影</h2>
 *
 * <p>太陽・月の影を描いている最中は、プレートを囲む箱で描く。厚みがほぼ無ければ板 1 枚（頂点 4）、
 * 縁取りなどで厚みがあれば箱（頂点 8）。影の地図は 1 画素が数 cm あるので、形の細部は出ない。</p>
 */
public final class PlateRenderer {

    /** これより薄いプレートは、影を板 1 枚で描く [m]。 */
    private static final double THIN = 0.003;

    private PlateRenderer() {
    }

    /**
     * 前後のプレートを描く。
     *
     * @param solid       不透明な面を積む先（ゴーストのときは半透明の回に差し替わる）
     * @param shadowPass  シェーダーパックが太陽・月の影を描いている最中か
     */
    public static void render(CarEntity car, CarModel model, CarSpec spec, PoseStack pose, SubmitNodeCollector buffer,
                              int packedLight, Function<Identifier, RenderType> solid, boolean shadowPass) {
        PartFitment fitment = car.getEffectiveFitment();
        Identifier partId = fitment.getPart(PartSlot.PLATE);
        if (partId == null) {
            return;
        }
        PlateModel look = PlateModel.get(partId);
        ObjModel mesh = ObjModel.get(look.model());
        if (mesh.isEmpty()) {
            return;
        }
        CarPart part = CarParts.get(partId);
        PlateRules rules = part == null || part.plate() == null ? PlateRules.NONE : part.plate();
        PlateLook plate = fitment.plate();
        PlateMounts.Mounts mounts = PlateMounts.get(car.getCarId());
        for (boolean front : new boolean[]{true, false}) {
            PlateMounts.Mount mount = PlateMounts.pick(mounts.side(front), front ? plate.front() : plate.rear());
            if (mount == null) {
                continue;
            }
            pose.pushPose();
            place(model, spec, mount, look.scale(), front, mesh, pose);
            Identifier texture = PlateTextures.get(partId, look, mesh, rules, plate, front);
            if (shadowPass) {
                ObjModel.submit(buffer, pose, RenderTypes.entityCutout(texture), (p, consumer) ->
                        drawShadow(mesh, p, consumer, packedLight));
            } else {
                // ナンバー灯はここでは明るくしない。ヘッドライトと同じ光（HeadlightVolume）が照らす（rearLamp）
                ObjModel.submit(buffer, pose, solid.apply(texture), (p, consumer) ->
                        mesh.render(p, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                                1.0F, 1.0F, 1.0F, 1.0F, null, false, ObjModel.Pass.OPAQUE));
            }
            pose.popPose();
        }
    }

    /**
     * プレートの原点を付ける位置へ。向きは「ヨー → 後ろなら裏返す → ピッチ」の順で、ピッチはプレートの表が
     * 上を向くのを正にする（前でも後ろでも同じ向きの意味になるように、裏返した後で掛ける）。
     */
    static void place(CarModel model, CarSpec spec, PlateMounts.Mount mount, float plateScale, boolean front,
                      ObjModel mesh, PoseStack pose) {
        // 位置だけ車体と同じ変換で動かす（拡大はプレートに掛けない）
        PoseStack body = new PoseStack();
        CarObjRenderer.applyBodyTransform(model, spec, body);
        Vector3f point = body.last().pose().transformPosition(
                new Vector3f((float) mount.x(), (float) mount.y(), (float) mount.z()));
        pose.translate(point.x(), point.y(), point.z());
        // 上から見て時計回りが正
        pose.rotate(Axis.YP.rotationDegrees(-mount.yaw()));
        if (!front) {
            pose.rotate(Axis.YP.rotationDegrees(180.0F));
        }
        pose.rotate(Axis.XP.rotationDegrees(mount.pitch()));
        // 大きさ（プレートの種類ごと × この位置だけ）。端を揃えるずれ（メッシュの寸法）にも掛かるよう、ずらす前に掛ける
        float scale = plateScale * mount.scale();
        if (scale != 1.0F) {
            pose.scale(scale, scale, scale);
        }
        // 揃える端。プレートのメッシュは正面から見た右が -X（エンティティ空間）。裏返した後ろでは左右が入れ替わる
        double shift = anchorShift(mount.anchor(), front, mesh.bounds());
        if (shift != 0.0) {
            pose.translate(shift, 0.0, 0.0);
        }
    }

    /** {@code pos} にプレートのどの端を合わせるか。中央ならメッシュの原点のまま。 */
    private static double anchorShift(PlateMounts.Anchor anchor, boolean front, double[] box) {
        return switch (anchor) {
            case CENTER -> 0.0;
            // 車の左（-X）の端を合わせる。前はメッシュの -X 側、後ろは裏返すので +X 側
            case LEFT -> front ? -box[0] : -box[3];
            case RIGHT -> front ? -box[3] : -box[0];
        };
    }

    /** 影の箱。厚みがほぼ無ければ表の板 1 枚、あれば 6 面。 */
    private static void drawShadow(ObjModel mesh, PoseStack.Pose entry, VertexConsumer consumer, int light) {
        double[] box = mesh.bounds();
        float x0 = (float) box[0], y0 = (float) box[1], z0 = (float) box[2];
        float x1 = (float) box[3], y1 = (float) box[4], z1 = (float) box[5];
        if (z1 - z0 < THIN) {
            quad(consumer, entry, light, 0, 0, -1,
                    x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
            return;
        }
        quad(consumer, entry, light, 0, 0, -1, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(consumer, entry, light, 0, 0, 1, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        quad(consumer, entry, light, -1, 0, 0, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        quad(consumer, entry, light, 1, 0, 0, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        quad(consumer, entry, light, 0, -1, 0, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        quad(consumer, entry, light, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose entry, int light,
                             float nx, float ny, float nz, float... corners) {
        for (int i = 0; i < 4; i++) {
            consumer.addVertex(entry, corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2])
                    .setColor(1.0F, 1.0F, 1.0F, 1.0F)
                    .setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(entry, nx, ny, nz);
        }
    }

    /** ナンバー灯の位置。プレートの上の縁からの高さ [m]。 */
    private static final double LAMP_ABOVE = 0.03;
    /** ナンバー灯の位置。プレートの表から離す量 [m]。近いほど上下の明るさの差が大きくなる。 */
    private static final double LAMP_OUT = 0.10;

    /**
     * ナンバー灯（後ろのプレートを照らす光）の位置と向き。<b>車の位置からの相対</b>（世界の向き）。
     *
     * <p>実物と同じく、プレートの上の縁の少し外に置いてプレートの中央へ向ける。照らすのは
     * ヘッドライトと同じ仕組み（{@link CarLights#collectLamps} → {@link HeadlightVolume}）で、
     * 配光だけナンバー灯用（カットオフの無い円錐・届くのはプレートのまわりだけ）。</p>
     *
     * @return {位置, 光軸, 右, 上}。後ろにプレートが付いていなければ null
     */
    @Nullable
    static org.joml.Vector3f[] rearLamp(CarEntity car, float partialTick) {
        PartFitment fitment = car.getEffectiveFitment();
        Identifier partId = fitment.getPart(PartSlot.PLATE);
        if (partId == null) {
            return null;
        }
        PlateModel look = PlateModel.get(partId);
        ObjModel mesh = ObjModel.get(look.model());
        PlateMounts.Mount mount = PlateMounts.pick(PlateMounts.get(car.getCarId()).rear(), fitment.plate().rear());
        if (mesh.isEmpty() || mount == null) {
            return null;
        }
        PoseStack rig = new PoseStack();
        CarObjRenderer.applyRotations(car, rig, partialTick);
        place(CarModel.get(car.getCarId()), car.getSpec(), mount, look.scale(), false, mesh, rig);
        Matrix4f matrix = rig.last().pose();
        double[] box = mesh.bounds();
        float centerX = (float) ((box[0] + box[3]) * 0.5);
        float centerY = (float) ((box[1] + box[4]) * 0.5);
        // メッシュの表は -Z（いちばん小さい Z）
        Vector3f lamp = matrix.transformPosition(new Vector3f(centerX, (float) (box[4] + LAMP_ABOVE),
                (float) (box[2] - LAMP_OUT)));
        Vector3f target = matrix.transformPosition(new Vector3f(centerX, centerY, (float) box[2]));
        Vector3f axis = new Vector3f(target).sub(lamp).normalize();
        Vector3f right = matrix.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F)).normalize();
        Vector3f up = new Vector3f(right).cross(axis).normalize();
        return new Vector3f[]{lamp, axis, right, up};
    }
}
