package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.ground.GroundSensor;
import com.jdmmc.kurumamod.physics.BlockField;
import com.jdmmc.kurumamod.physics.CarHull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link CarCore} が、それを持つ版の {@code CarEntity} に聞くこと・頼むこと。
 *
 * <p>版ごとに Minecraft のメソッドの名前が違う（1.21.1 の {@code isControlledByLocalInstance} が 26.3 では
 * {@code isLocalInstanceAuthoritative}）ので、その違いはここの実装で吸収する。地面を読む口
 * （{@link GroundSensor.World}）もここに含む。</p>
 *
 * <p><b>Minecraft と同じ名前のメソッドを置かないこと。</b>開発環境ではバニラから継いだメソッドで満たされても、
 * 配布環境（Fabric の intermediary・Forge の SRG）では名前が変わって {@code AbstractMethodError} になる。</p>
 */
public interface CarHost extends GroundSensor.World {

    /** このクライアントが物理を解いている車か（クライアント側で、運転しているのがこのクライアント）。 */
    boolean simulatedOnThisClient();

    /**
     * この側（クライアントでもサーバーでも）が物理を解いているか。
     * 運転者のいない車はサーバーが解くので、サーバー側では true になりうる。
     */
    boolean simulatedHere();

    /** クライアント側か。 */
    boolean clientSide();

    /** 向き [度]（Minecraft のヨー）。 */
    float vanillaYaw();

    /** 向き [度] を入れる（Minecraft の {@code setYRot}。折り返さない）。 */
    void setVanillaYaw(float yaw);

    /** 向きを Minecraft の決まりどおり折り返す（{@code setRot(getYRot(), getXRot())}。360 で割った余りにする）。 */
    void wrapVanillaRotation();

    /** 位置 [m]。 */
    double posX();

    double posY();

    double posZ();

    /** 位置を置き直す（Minecraft の {@code setPos}）。 */
    void placeAt(double x, double y, double z);

    /** 同期した値。運転していない側はここから読む。 */
    CarSync synced();

    /** 車の足元に、いま実際に雨が当たっているか（屋根の下・寒冷地では false）。 */
    boolean rainingHere();

    /**
     * {@code area}（{minX, minY, minZ, maxX, maxY, maxZ}）の中のブロックの当たり判定とワールドボーダーを
     * {@code field} に集める（{@code field} は空にしてある）。範囲の外を聞かれたときの答え方もここで決める。
     *
     * @param x 車の中心（ワールドボーダーの内側にいるかを見るため）
     */
    void collectBlocks(BlockField field, double[] area, double x, double z);

    /**
     * 車同士の箱（{@link CarHull#body}）で動かし、他の車から押し出す。地形は {@code CarBody} が解いてあるので、
     * ここで見るのは車同士だけ。当たった車との運動量のやりとりは {@link CarCore} が決める。
     */
    Move moveWithCars(double dx, double dy, double dz);

    /** この車の中身。当たった相手の速度と諸元を読むのに使う。 */
    CarCore carCore();

    /**
     * 当たった車 {@code other}（{@link Move#other}）へ、押されたぶんを届ける。
     * 相手の物理を解いている側（相手の運転者・無人ならサーバー）へ届ける道筋は版が決める。
     *
     * @param dx       ワールド座標での速度の足し X [m/s]
     * @param dz       同じく Z
     * @param dYawRate ヨー角速度の足し [rad/s]
     */
    void pushCar(CarHost other, float dx, float dz, float dYawRate);

    /** バニラの「横にぶつかった」「縦にぶつかった」の印（{@code horizontalCollision}・{@code verticalCollision}）。 */
    void setCollisions(boolean horizontal, boolean vertical);

    /** ぶつかったことを音と揺れに変えて周りへ配る（{@link CarCore#IMPACT_MIN_SPEED} 未満なら何もしない）。 */
    void reportImpact(double impact);

    /** 車体が擦れた・ぶつかったことを火花に変えて周りへ配る。 */
    void reportScrape(float[] offset, float[] normal, float[] velocity, float impact);

    /**
     * {@link #moveWithCars} の結果。
     *
     * @param motion 車同士の箱で動かした結果（横で止められたか・当たった面の向きと接点）
     * @param other  いちばん深く当たった車。当たっていなければ null
     */
    record Move(CarHull.Motion motion, @Nullable CarHost other) {
    }
}
