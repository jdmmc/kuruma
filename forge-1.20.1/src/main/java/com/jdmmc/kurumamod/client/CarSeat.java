package com.jdmmc.kurumamod.client;

import net.minecraft.world.phys.Vec3;

/**
 * 座席の位置を<b>見た目の定義</b>（{@code vehicles/car.json} の {@code seat}）から引く。
 *
 * <p>座面の高さは車体の形で決まるので車ごとに違う。物理は一切読まない値なので、
 * 他の見た目と同じく {@code assets/} に置いてよい。<b>サーバーは読まないが実害はない</b>——
 * 乗員の位置は各クライアントが自分で計算するので、サーバー側の値はどこにも見えない。</p>
 *
 * <p><b>三人称のカメラはここを見ない。</b>{@link CarChaseCamera} が車の中心を軸に
 * カメラの位置そのものを置くので、運転席が右へ寄っていてもカメラは中央のまま。
 * 一人称だけがこの位置をそのまま目線にする（＝右ハンドルの視界）。</p>
 *
 * <p><b>かつては「カメラの起点＝乗り手の位置」をずらして距離を稼いでいたが、捨てた。</b>
 * 乗り手の位置は 20Hz でしか更新できないのにカメラの角度は毎フレーム変わるので、
 * 視点を振るたびに起点が遅れて追いかけ、画面がぐにゃぐにゃ揺れる。平滑化しても遅れが
 * 増えるだけ、起点を車基準に固定すると今度は視線と噛み合わない。
 * <b>位置を直接置ける以上、この方式に戻る理由はない。</b></p>
 */
public final class CarSeat {

    private CarSeat() {
    }

    /**
     * 席の位置（車体基準）。
     *
     * <p>JSON は<b>見た目の向き</b>（+X 右・+Y 上・-Z 前）で書くのに対し、乗員を置く座標系は
     * <b>+X 左・+Z 前</b>（{@code yRot(-yaw)} で回すため）。ここで読み替える。</p>
     *
     * @param passengerSeat true なら助手席（左右反転）
     */
    public static Vec3 seatOffset(net.minecraft.resources.ResourceLocation carId, boolean passengerSeat) {
        CarModel.Vec3 seat = CarModel.get(carId).seatOffset();
        double right = passengerSeat ? -seat.x() : seat.x();
        return new Vec3(-right, seat.y(), -seat.z());
    }
}
