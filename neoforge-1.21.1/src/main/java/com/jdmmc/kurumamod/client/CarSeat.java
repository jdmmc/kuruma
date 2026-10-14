package com.jdmmc.kurumamod.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

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
     * 座席の調整画面（{@link SeatScreen}）で動かした値。車種ごと。JSON の値より優先する。
     * <b>このクライアントだけ・ゲームを閉じるまで</b>（デバッグ用。決まった値は JSON へ書く）。
     */
    private static final Map<ResourceLocation, CarModel.Vec3> OVERRIDES = new HashMap<>();

    /** いま使っている運転席の位置（JSON の向き）。調整画面で動かしていればその値。 */
    public static CarModel.Vec3 current(ResourceLocation carId) {
        CarModel.Vec3 override = OVERRIDES.get(carId);
        return override != null ? override : CarModel.get(carId).seatOffset();
    }

    public static void setOverride(ResourceLocation carId, CarModel.Vec3 seat) {
        OVERRIDES.put(carId, seat);
    }

    /** JSON の値へ戻す。 */
    public static void clearOverride(ResourceLocation carId) {
        OVERRIDES.remove(carId);
    }

    /**
     * ハンドルの位置を調整画面で動かした値。座席と同じく<b>このクライアントだけ・ゲームを閉じるまで</b>。
     * ハンドルの位置は座席から見て決めるものなので、同じ画面・同じ置き場にまとめてある。
     */
    private static final Map<ResourceLocation, CarModel.Vec3> STEERING_OVERRIDES = new HashMap<>();

    /** いま使っているハンドルの平行移動（JSON の向き）。調整画面で動かしていればその値。 */
    public static CarModel.Vec3 currentSteering(ResourceLocation carId) {
        CarModel.Vec3 override = STEERING_OVERRIDES.get(carId);
        return override != null ? override : CarModel.get(carId).steeringOffset();
    }

    public static void setSteeringOverride(ResourceLocation carId, CarModel.Vec3 offset) {
        STEERING_OVERRIDES.put(carId, offset);
    }

    /** ハンドルの傾きを調整画面で動かした値 [度]。位置と同じく、このクライアントだけ・ゲームを閉じるまで。 */
    private static final Map<ResourceLocation, Double> STEERING_TILT_OVERRIDES = new HashMap<>();

    /** いま使っているハンドルの傾きの足し分 [度]。正でコラムが起きる。 */
    public static double currentSteeringTilt(ResourceLocation carId) {
        Double override = STEERING_TILT_OVERRIDES.get(carId);
        return override != null ? override : CarModel.get(carId).steeringTilt();
    }

    public static void setSteeringTiltOverride(ResourceLocation carId, double tilt) {
        STEERING_TILT_OVERRIDES.put(carId, tilt);
    }

    /** JSON の値へ戻す（位置も傾きも）。 */
    public static void clearSteeringOverride(ResourceLocation carId) {
        STEERING_OVERRIDES.remove(carId);
        STEERING_TILT_OVERRIDES.remove(carId);
    }

    /**
     * 席の位置（車体基準）。
     *
     * <p>JSON は<b>見た目の向き</b>（+X 右・+Y 上・-Z 前）で書くのに対し、乗員を置く座標系は
     * <b>+X 左・+Z 前</b>（{@code yRot(-yaw)} で回すため）。ここで読み替える。</p>
     *
     * @param passengerSeat true なら助手席（左右反転）
     */
    public static Vec3 seatOffset(ResourceLocation carId, boolean passengerSeat) {
        CarModel.Vec3 seat = current(carId);
        double right = passengerSeat ? -seat.x() : seat.x();
        return new Vec3(-right, seat.y(), -seat.z());
    }
}
