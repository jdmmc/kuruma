package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarId;

import java.util.Set;
import java.util.function.Function;

/**
 * 車体・ホイールのメッシュ（OBJ）の形のうち、描画以外の所（モデルの定義・ナンバーの置き場所・画面）が見るもの。
 * メッシュそのもの（GPU に置く頂点）は版ごとの {@code ObjModel} が持ち、これを実装する。
 */
public interface MeshShape {

    /** 部品の形に付ける名前の頭（{@code part_te37} など）。 */
    String PART_PREFIX = "part_";

    /** 外形の箱 {minX, minY, minZ, maxX, maxY, maxZ}。 */
    double[] bounds();

    boolean isEmpty();

    /** 出し分けのある形の名前（{@code part_*}・{@code opt_*} など）。 */
    Set<String> optionalNames();

    /** 前（または後ろ）から z 方向に見て、(x, y) で最初に当たる面 {z, 法線の y, 法線の z}。当たらなければ null。 */
    double[] surfaceAlongZ(double x, double y, boolean front);

    /** ハンドルの軸の傾き [度]（{@code ObjModel#steeringColumnAngle}）。測れなければ NaN。 */
    double steeringColumnAngle(double column);

    /** そのメッシュ（読み込み済みのものを返す）。 */
    static MeshShape get(CarId location) {
        return Holder.meshes.apply(location);
    }

    static void set(Function<CarId, MeshShape> meshes) {
        Holder.meshes = meshes;
    }

    final class Holder {
        private static Function<CarId, MeshShape> meshes;

        private Holder() {
        }
    }
}
