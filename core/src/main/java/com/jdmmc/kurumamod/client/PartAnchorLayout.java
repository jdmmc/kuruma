package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;

import java.util.Map;

/**
 * 換装画面の札を立てる部位の位置の決めごと（版の {@code PartAnchors} が画面へ写す）。
 *
 * <p>カーパックに「ウイングはここ」と書いてもらう項目は無いので、<b>車体メッシュを囲む箱</b>の
 * 決まった割合の位置に置く（ウイングは後端の上、リップは前端の下）。ホイールとタイヤだけは
 * 物理がタイヤを置く位置そのものを使う。正確な一点である必要はなく、「このあたり」が分かれば足りる。</p>
 *
 * <p><b>左右のある部位は、カメラに近い側に置く。</b>右に固定すると、左から見ているときに札が
 * 車の向こう側を指して、線が車体を突き抜ける。</p>
 */
public final class PartAnchorLayout {

    /** 点の座標系。 */
    public enum Frame {
        /** 姿勢だけ掛けた座標（+X 右・+Y 上・-Z 前、原点は重心）。タイヤはこちらで置く。 */
        CAR,
        /** 車体メッシュの座標。外形の箱はこちらで測る。 */
        BODY
    }

    /** 点を画面へ写す口（版が行列で写す）。 */
    public interface Projector {
        /** 画面の位置 {x, y}（GUI 座標）。カメラの後ろなら null。 */
        float[] project(Frame frame, double x, double y, double z);

        /** カメラからの距離の 2 乗。 */
        double distance(Frame frame, double[] point);
    }

    private PartAnchorLayout() {
    }

    /**
     * 部位ごとの画面の位置を {@code out} に入れる。写せなかったもの（カメラの後ろ）は入らない。
     *
     * @param suspension 各輪のサスの長さ [m]（{@link Wheel#ordinal()} の順）
     * @param bodyBox    車体メッシュの外形 {minX, minY, minZ, maxX, maxY, maxZ}
     * @param front      前のナンバーを付ける位置（車体メッシュの座標）。無ければ null
     * @param rear       後ろのナンバー。無ければ null
     * @return {車の中心, 塗装の札（屋根）}。写せなければその要素が null
     */
    public static float[][] layout(Projector projector, CarSpec spec, double[] suspension, double[] bodyBox,
                                   double[] front, double[] rear, Map<PartSlot, float[]> out) {
        float[] center = projector.project(Frame.CAR, 0.0, 0.3, 0.0);

        // タイヤ。ホイールは前輪、タイヤは後輪に立てて、2 枚の札が同じ点を指さないようにする
        put(out, PartSlot.WHEEL, nearer(projector, Frame.CAR, wheelPoint(spec, suspension, Wheel.FRONT_RIGHT, 0.0),
                wheelPoint(spec, suspension, Wheel.FRONT_LEFT, 0.0)));
        put(out, PartSlot.TIRE, nearer(projector, Frame.CAR,
                wheelPoint(spec, suspension, Wheel.REAR_RIGHT, -0.6),
                wheelPoint(spec, suspension, Wheel.REAR_LEFT, -0.6)));
        put(out, PartSlot.FENDER, nearer(projector, Frame.CAR,
                wheelPoint(spec, suspension, Wheel.FRONT_RIGHT, 1.15),
                wheelPoint(spec, suspension, Wheel.FRONT_LEFT, 1.15)));

        double minX = bodyBox[0], minY = bodyBox[1], minZ = bodyBox[2];
        double maxX = bodyBox[3], maxY = bodyBox[4], maxZ = bodyBox[5];
        double width = maxX - minX, height = maxY - minY, depth = maxZ - minZ;
        double midX = (minX + maxX) * 0.5, midZ = (minZ + maxZ) * 0.5;
        Frame body = Frame.BODY;
        // メッシュも -Z が前。minZ が鼻先、maxZ が後端
        put(out, PartSlot.FRONT_BUMPER, projector.project(body, midX, minY + height * 0.18, minZ));
        put(out, PartSlot.TURBO, projector.project(body, midX - width * 0.12, minY + height * 0.62, minZ + depth * 0.22));
        put(out, PartSlot.BLOWOFF, projector.project(body, midX + width * 0.15, minY + height * 0.62, minZ + depth * 0.30));
        put(out, PartSlot.REAR_WING, projector.project(body, midX, minY + height * 0.80, maxZ - depth * 0.06));
        put(out, PartSlot.REAR_DIFFUSER, projector.project(body, midX, minY + height * 0.10, maxZ));
        put(out, PartSlot.SIDE_SKIRT, nearer(projector, body,
                new double[]{maxX, minY + height * 0.12, midZ}, new double[]{minX, minY + height * 0.12, midZ}));
        put(out, PartSlot.EXHAUST, nearer(projector, body,
                new double[]{midX + width * 0.3, minY + height * 0.12, maxZ},
                new double[]{midX - width * 0.3, minY + height * 0.12, maxZ}));
        float[] paint = projector.project(body, midX, maxY, midZ + depth * 0.05);

        // ナンバーは付ける位置。前後のうちカメラに近いほう
        double[] frontPoint = front != null ? front : new double[]{midX, minY + height * 0.3, minZ};
        double[] rearPoint = rear != null ? rear : new double[]{midX, minY + height * 0.4, maxZ};
        put(out, PartSlot.PLATE, nearer(projector, body, frontPoint, rearPoint));
        return new float[][]{center, paint};
    }

    private static void put(Map<PartSlot, float[]> out, PartSlot slot, float[] point) {
        if (point != null) {
            out.put(slot, point);
        }
    }

    /**
     * タイヤの中心から、半径の {@code up} 倍だけ上の点（姿勢だけ掛けた座標）。
     * 置き方は {@code CarObjRenderer#renderWheels} と同じ（左右・前後は諸元、高さはサスの長さ）。
     */
    private static double[] wheelPoint(CarSpec spec, double[] suspension, Wheel wheel, double up) {
        return new double[]{
                spec.wheelRightOffset(wheel),
                -suspension[wheel.ordinal()] + spec.wheelRadius() * up,
                -spec.wheelForwardOffset(wheel)};
    }

    /** 2 つの候補のうち、カメラに近いほうを写す。 */
    private static float[] nearer(Projector projector, Frame frame, double[] a, double[] b) {
        double[] chosen = projector.distance(frame, a) <= projector.distance(frame, b) ? a : b;
        return projector.project(frame, chosen[0], chosen[1], chosen[2]);
    }
}
