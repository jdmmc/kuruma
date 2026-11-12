package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.Wheel;

/**
 * 車体・ホイール・ハンドルを置く数（平行移動・拡大・角度）。<b>行列に掛けるのは版の {@code CarObjRenderer}</b>で、
 * ここは掛ける数を決めるところだけ。鏡（{@code MirrorRenderer}）・灯火・ナンバー・手・札も同じ数を使うので、
 * <b>車体の置き方は 1 か所に保つこと</b>——別の式で書き直すと、車高や荷重配分を変えたときに取り残される。
 */
public final class CarRig {

    /** 最大の切れ角でハンドルが回る角度 [rad]。ロック・トゥ・ロック 3 回転の半分。 */
    public static final double HANDWHEEL_LOCK = Math.toRadians(540.0);

    private CarRig() {
    }

    /**
     * 車体メッシュを置く平行移動 {x, y, z}（拡大より先に掛ける。後に置くと下げ量まで拡大される）。
     *
     * <p>エンティティの位置はシャシー基準面。モデルは地面に立った状態で作られているので下げる。
     * 現在の車高で下げてはいけない。サスを伸ばすとシャシー基準面も同じだけ上がるので、
     * 車体とタイヤの両方を車高で動かすと打ち消し合い、車体とタイヤの間隔が変わらなくなる。</p>
     *
     * <p>前後輪の中点まで下がる。エンティティの原点は<b>重心</b>なので、重心を前寄りにすると
     * タイヤは相対的に後ろへ動く（前 56% なら 0.187m）。車体メッシュは前後輪の中点を
     * 原点に作られているので、この差を埋めないとホイールアーチがタイヤから前へずれる。
     * エンティティ空間は -Z が前なので、物理側（+Z が前）とは符号が逆になる。</p>
     */
    public static double[] bodyOffset(CarModel model, CarSpec spec) {
        double axleMidpoint = -spec.axleMidpointOffset();
        CarModel.Vec3 offset = model.bodyOffset();
        return new double[]{offset.x(), offset.y() - model.designRideHeight(), offset.z() + axleMidpoint};
    }

    /**
     * メッシュの作りの大きさを既定の車格へ正規化する倍率。5.2m で作ったモデルに designWheelBase = 5.2 と
     * 書けば半分に縮み、ホイールアーチがタイヤの位置に合う。
     *
     * <p>比べる相手は {@link CarSpec#DEFAULT} であって、今の諸元ではない。今の諸元にすると、ホイールベースの
     * スライダーで車体まで伸び縮みする（キャビンごと伸びる）。定数なので走行中に変わることはない。</p>
     */
    public static double bodyFit(CarModel model) {
        return CarSpec.DEFAULT.wheelBase() / model.designWheelBase();
    }

    /** 車体メッシュの拡大 {x, y, z}（{@link #bodyFit} × 手で決める拡大率。原点が地面なので、上げると車体は上へ伸びる）。 */
    public static double[] bodyScale(CarModel model) {
        double fit = bodyFit(model);
        CarModel.Vec3 scale = model.bodyScale();
        return new double[]{fit * scale.x(), fit * scale.y(), fit * scale.z()};
    }

    /**
     * ハンドルの回転角 [rad]。正で右。ハンドルを握る手も同じ角度を使う。見た目用に抑えた角度ではなく
     * 実際の切れ角を使う——運転者が回しているのはこちら。
     */
    public static double handwheelAngle(CarSpec spec, double steerAngle) {
        if (spec.maxSteerAngle() <= 0.0) {
            return 0.0;
        }
        return steerAngle * HANDWHEEL_LOCK / spec.maxSteerAngle();
    }

    /**
     * 車軸の位置 {x, y, z}（姿勢だけ掛けた座標）。前後・左右は諸元（ホイールベースとトレッドはタイヤの位置の
     * 定義そのものなので、見た目の都合でずらすと接地点と食い違う）、上下はサスがどれだけ伸びているか。
     * JSON のオフセットはこれに対する補正で、X は左右で符号を反転させて「外向き」にする。
     */
    public static double[] wheelCenter(CarSpec spec, CarModel.Vec3 offset, Wheel wheel, double suspension) {
        double outward = wheel.isLeft() ? -offset.x() : offset.x();
        return new double[]{
                spec.wheelRightOffset(wheel) + outward,
                -suspension + offset.y(),
                -spec.wheelForwardOffset(wheel) + offset.z()};
    }

    /**
     * ホイールとタイヤの拡大 {x, y, z}。メッシュが作られた半径から諸元の半径へ正規化したうえで、JSON の拡大率を
     * 掛ける。前者があるので、調整画面でタイヤ半径を変えると見た目もそのまま追従する。後者で縦（Y・Z）を触ると
     * 接地が崩れる——物理はこの補正を知らないため。太さ（X）だけなら安全。
     */
    public static float[] wheelScale(CarSpec spec, CarPartModel.Wheel look) {
        double scale = spec.wheelRadius() / look.designRadius();
        CarModel.Vec3 wheelScale = look.scale();
        return new float[]{(float) (scale * wheelScale.x()), (float) (scale * wheelScale.y()),
                (float) (scale * wheelScale.z())};
    }

    /**
     * タイヤの内径（ビード）を置く半径（ホイールのメッシュの寸法）。
     *
     * <p>書かれていればその値。無ければ、付属のタイヤがあればその内径（作った人が嵌めた位置そのもの）、
     * それも無ければリムの外縁の {@link CarPartModel#BEAD_RATIO} 倍。</p>
     */
    public static double beadRadius(CarPartModel.Wheel look, ObjMesh wheelMesh) {
        if (look.rimRadius() != null) {
            return look.rimRadius();
        }
        double[] attached = wheelMesh.tireRadialRange();
        if (attached != null) {
            return attached[0];
        }
        return wheelMesh.rimOuterRadius() * CarPartModel.BEAD_RATIO;
    }

    /**
     * アイコンに収める外形 {minX, minY, minZ, maxX, maxY, maxZ}（姿勢だけ掛けた座標）。車体の 8 隅に描くときと
     * 同じ置き方を掛け、タイヤは太さに半径の半分を見込む（車体からはみ出すのは外側だけ）。
     *
     * @param mesh       車体メッシュの外形
     * @param suspension 各輪のサスの長さ（{@link Wheel#ordinal()} の順）
     */
    public static double[] iconBounds(CarModel model, CarSpec spec, double[] mesh, CarModel.Vec3 wheelOffset,
                                      double[] suspension) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[] offset = bodyOffset(model, spec);
        double[] scale = bodyScale(model);
        for (int corner = 0; corner < 8; corner++) {
            // 描くときと同じ float の行列で掛けたものに揃える
            float x = (float) (offset[0] + (float) scale[0] * (float) mesh[(corner & 1) == 0 ? 0 : 3]);
            float y = (float) (offset[1] + (float) scale[1] * (float) mesh[(corner & 2) == 0 ? 1 : 4]);
            float z = (float) (offset[2] + (float) scale[2] * (float) mesh[(corner & 4) == 0 ? 2 : 5]);
            include(box, x, y, z);
        }
        double radius = spec.wheelRadius();
        for (Wheel wheel : Wheel.VALUES) {
            double[] c = wheelCenter(spec, wheelOffset, wheel, suspension[wheel.ordinal()]);
            double side = Math.signum(c[0]) * radius * 0.5;
            include(box, c[0] + side, c[1] - radius, c[2] - radius);
            include(box, c[0], c[1] + radius, c[2] + radius);
        }
        return box;
    }

    private static void include(double[] box, double x, double y, double z) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }
}
