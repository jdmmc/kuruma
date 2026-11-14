package com.jdmmc.kurumamod.entity;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.client.effect.EmitterCar;
import com.jdmmc.kurumamod.client.hud.HudCar;
import com.jdmmc.kurumamod.client.effect.SkidMarkTrails;
import com.jdmmc.kurumamod.car.CarCore;
import com.jdmmc.kurumamod.car.CarHost;
import com.jdmmc.kurumamod.car.CarSync;
import com.jdmmc.kurumamod.car.CarPose;
import com.jdmmc.kurumamod.ground.GroundSensor;
import com.jdmmc.kurumamod.platform.RaceTexts;
import com.jdmmc.kurumamod.part.PartFitments;
import com.jdmmc.kurumamod.tuning.CarSpecNbt;
import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.network.CarPacketHandlers;
import com.jdmmc.kurumamod.network.KurumaNetwork;

import com.jdmmc.kurumamod.car.CarRules;
import com.jdmmc.kurumamod.Config;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarImpact;
import com.jdmmc.kurumamod.client.CarSparks;
import com.jdmmc.kurumamod.client.CarSeat;
import com.jdmmc.kurumamod.debug.GroundTrace;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.network.CarImpactPacket;
import com.jdmmc.kurumamod.network.CarPartsPacket;
import com.jdmmc.kurumamod.network.CarPushPacket;
import com.jdmmc.kurumamod.network.CarScrapePacket;
import com.jdmmc.kurumamod.network.CarSpecPacket;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.physics.BlockField;
import com.jdmmc.kurumamod.physics.CarBody;
import com.jdmmc.kurumamod.physics.CarHull;
import com.jdmmc.kurumamod.physics.CarInput;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.surface.SurfaceLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ドライブ可能な車両エンティティ。
 *
 * <p>物理は「運転している側」だけが解く:</p>
 * <ul>
 *   <li>運転クライアント … 入力を読んで {@link CarPhysics} を回し、自分で位置を確定させる。
 *       位置・向きはバニラが送る {@code ServerboundMoveVehiclePacket} でサーバーへ届き、
 *       それ以外の状態は {@link CarStatePacket} で別途送る。</li>
 *   <li>サーバー … 運転者がいる間は何も計算せず中継に徹する。無人のときだけ物理を回す。</li>
 *   <li>運転していないクライアント … サーバーから来た位置を {@link #tickLerp()} で補間して描画するだけ。</li>
 * </ul>
 *
 * <p>この分岐は {@link #isLocalInstanceAuthoritative()} 1 つで表せる
 * （運転クライアント、または無人時のサーバーで true になる）。</p>
 *
 * <p><b>エンティティの Y はシャシー基準面（サスペンションのハードポイントが並ぶ面）を指す。</b>
 * 地面ではない。タイヤは AABB の下にぶら下がっているので、AABB は車体だけを表す。</p>
 */
public class CarEntity extends CarEntityBase implements CarHost, HudCar, GroundSensor.World, SkidMarkTrails.Car, EmitterCar,
        com.jdmmc.kurumamod.client.input.CarDriver.Car, com.jdmmc.kurumamod.part.PartRequests.Car {

    /** 前輪の切れ角 [rad]。運転していないクライアントでの描画用。 */
    private static final EntityDataAccessor<Float> DATA_STEER_ANGLE =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /** 車速 [m/s]。運転していないクライアントでのタイヤ回転描画用。 */
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /**
     * ヨー角 [度]。<b>描画専用</b>。
     *
     * <p>バニラの位置パケットはヨー角を<b>バイト（1/256 回転 ＝ 1.4 度刻み）</b>に量子化する。
     * 直進では気づかないが、旋回中は「1.4 度進む／進まない」が交互に来るので回転の速さが
     * ガタつき、車体の端＝ケツで最も大きく出る（2.7m 先で 6.6cm）。float のまま配って
     * 描画だけこちらを使う。座席や当たり判定はバニラの値のままでよい（誤差 1.4 度は
     * 座面で 1cm 程度）。</p>
     */
    private static final EntityDataAccessor<Float> DATA_YAW =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 運転席に座っている乗員のエンティティ ID。空いていれば -1。<b>サーバーが決めて配る。</b>
     *
     * <p>乗員の並び順で席を決めると、運転者が降りたとたんに助手席の人が先頭へ繰り上がり、
     * 運転席へ移って運転を始めてしまう。席は乗ったときに決め、降りるまで変えない。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_DRIVER =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);
    /** ピッチ角 [rad]。正で鼻上げ。 */
    private static final EntityDataAccessor<Float> DATA_PITCH =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /** ロール角 [rad]。正で右下がり。 */
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 各輪のサスペンション長 [m]。{@link Wheel} の並び。
     *
     * <p>見た目に効くものは同期する方針。伸縮を配らないと、他人の車だけノーズダイブも
     * スクワットもしない板のように見える。</p>
     */
    private static final List<EntityDataAccessor<Float>> DATA_SUSPENSION = List.of(
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT));
    /**
     * 各輪の回転角速度 [rad/s]。{@link Wheel} の並び。
     *
     * <p><b>車速から作ってはいけない。</b>車速から積むとどの輪も路面と同じ速さで転がることになり、
     * <b>空転もロックも他人の画面では見えない</b>。</p>
     */
    private static final List<EntityDataAccessor<Float>> DATA_WHEEL_SPEED = List.of(
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT));
    /**
     * 各輪が接地面で捨てている摩擦の仕事率 [W]。{@link Wheel} の並び。
     *
     * <p>煙の濃さを決めるのに使う。滑り率と荷重から受信側で組み直すこともできるが、
     * <b>輪ごとのスリップ角も路面も配っていない</b>ので、横に滑っているぶんと
     * 路面のμを取り違える。物理コアが出した値をそのまま配る方が正しく、かつ安い。</p>
     */
    private static final List<EntityDataAccessor<Float>> DATA_WHEEL_FRICTION = List.of(
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT));

    // 以下は音のために配る。配らないと他人の車はアイドル音のままで、
    // 変速も空転も路面の違いも聞こえない
    /** エンジン回転数。排気音のピッチ。 */
    private static final EntityDataAccessor<Float> DATA_RPM =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /** 今の段。変速音の合図。 */
    private static final EntityDataAccessor<Integer> DATA_GEAR =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);
    /** アクセルの踏み込み量 0..1。排気音の音量。 */
    private static final EntityDataAccessor<Float> DATA_THROTTLE =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /** 車体スリップ角 [度]。タイヤの悲鳴。 */
    private static final EntityDataAccessor<Float> DATA_SLIP_ANGLE =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 各輪の路面。{@link RoadSurface} の並び順を 1 輪 4 ビットで詰めたもの（{@link #packSurfaces}）。
     * 各 4 ビットの最上位は「その輪が浮いている」（{@link #isRenderWheelAirborne}）。
     *
     * <p><b>車 1 台で 1 つにしてはいけない。</b>以前は「いちばん滑りやすい輪」だけを配っていたので、
     * 前輪が土に乗った瞬間に、まだコンクリートの上で空転している後輪の煙まで土煙になった。
     * ロードノイズのように車全体で 1 つ要るものは、受け取った側で 4 輪から選ぶ。</p>
     */
    private static final EntityDataAccessor<Integer> DATA_SURFACE =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);
    /**
     * 灯火の状態。ビットの詰め合わせ（{@link #LIGHT_HEAD} / {@link #LIGHT_BRAKE}）。
     *
     * <p>バックランプは段（{@link #DATA_GEAR}）から分かるので入れていない。
     * ヘッドライトは運転者が切り替え、ブレーキは踏み込み量から作る。</p>
     */
    private static final EntityDataAccessor<Byte> DATA_LIGHTS =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.BYTE);
    /** ターボのブースト 0..1。過給音とメーターの TURBO ランプに使う。 */
    private static final EntityDataAccessor<Float> DATA_BOOST =
            SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);

    /** ヘッドライト点灯（{@link CarCore#LIGHT_HEAD}）。 */
    public static final int LIGHT_HEAD = CarCore.LIGHT_HEAD;
    /** ブレーキランプ点灯（{@link CarCore#LIGHT_BRAKE}）。 */
    public static final int LIGHT_BRAKE = CarCore.LIGHT_BRAKE;

    /** これ以下の速度差では衝突として扱わない [m/s]（{@link CarCore#IMPACT_MIN_SPEED}）。 */
    private static final double IMPACT_MIN_SPEED = CarCore.IMPACT_MIN_SPEED;
    /** 車体が壁とこれより速く擦れたら火花を出す [m/s]（{@link CarCore#SPARK_MIN_SPEED}）。 */
    public static final double SPARK_MIN_SPEED = CarCore.SPARK_MIN_SPEED;
    /**
     * バニラの当たり判定の箱を、車体の箱（{@link CarHull}）の内側へさらに余らせる量 [m]。
     * サーバーが知っている姿勢は少し遅れて届くので、そのぶん小さくしておく。
     */
    private static final double VANILLA_BOX_MARGIN = 0.05;
    /** 登録している大きさ（1.8 × 0.7 × 1.8）の {@code AABB#getSize()}。描画の距離打ち切りの基準。 */
    private static final double REGISTERED_BOX_SIZE = (1.8 + 0.7 + 1.8) / 3.0;
    /**
     * クリックや攻撃の当たりを広げる量 [m]。バニラの箱は車体の箱の内側の細い板なので、
     * そのままでは車の下半分をクリックしても乗れない。
     */
    private static final float PICK_RADIUS = 0.6F;
    /**
     * 描画の距離打ち切りに掛ける倍率。約 92m → 約 160m
     * （＝ {@code EntityType.Builder#clientTrackingRange(10)} で要求している距離）。
     *
     * <p>これ以上伸ばしても、サーバーがそこまで車を配らないので意味がない。</p>
     */
    private static final double RENDER_DISTANCE_SCALE = 1.75;
    /**
     * 運転席の位置（右ハンドル）。シャシー基準面から見た乗員の座り位置。
     *
     * <p>ローカル座標は <b>+X が左・+Z が前</b>（{@code yRot(-yaw)} で回すため）なので、
     * 右席は X が負。トレッドを広げても動かさないのは、車体がトレッドに追従しない以上、
     * 席だけ外へ出ると車体からはみ出すため。</p>
     *
     * <p><b>三人称のカメラはここを見ない。</b>{@code CarChaseCamera} が車の中心を軸に
     * カメラの位置そのものを置くので、運転席が右へ寄っていてもカメラは中央のまま。
     * 一人称だけがこの位置をそのまま目線にする（＝右ハンドルの視界）。</p>
     */
    private static final Vec3 DRIVER_SEAT_OFFSET = new Vec3(-0.5, -0.55, -0.2);
    /** 助手席。運転席の左右反対。 */
    private static final Vec3 PASSENGER_SEAT_OFFSET =
            new Vec3(-DRIVER_SEAT_OFFSET.x, DRIVER_SEAT_OFFSET.y, DRIVER_SEAT_OFFSET.z);
    /** 車種・諸元・装着している部品と、そこから組み上げた実効の諸元。 */
    private final CarCore carCore = new CarCore(this);
    /** 物理の状態。運転権を持っている側だけが進める。 */
    private final CarState state = carCore.state();
    /** 同期した値（{@link SyncedValues}）。運転していない側はここから読む。 */
    private final CarSync synced = new SyncedValues();


    /** 前ティックで自分が運転権を持っていたか。持ち替わった瞬間に物理の状態を作り直す。 */
    private boolean hadControl;

    /**
     * このティックに運転クライアントがサーバーへ送った状態。運転していなければ null。
     *
     * <p>ゴーストの記録（{@code client.CarGhosts}）が読む。他人の画面へ届くのと<b>同じもの</b>を
     * 残しておけば、再生側は他人の車を描くのと同じ道で動かせる。</p>
     */
    @Nullable
    private CarStatePacket lastSentState;



    /** {@link CarCore#groundTrace}。 */
    public GroundTrace groundTrace() {
        return carCore.groundTrace();
    }

    @Override
    public GroundSensor.Hit castDown(double x, double fromY, double toY, double z) {
        return new BlockHit(level().clip(new ClipContext(new Vec3(x, fromY, z), new Vec3(x, toY, z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)));
    }

    @Override
    public boolean recordsTrace() {
        return level().isClientSide();
    }

    /** レイの当たり（{@link GroundSensor.Hit}）。路面は聞かれたときだけ引く。 */
    private final class BlockHit implements GroundSensor.Hit {
        private final BlockHitResult hit;

        BlockHit(BlockHitResult hit) {
            this.hit = hit;
        }

        @Override
        public boolean miss() {
            return hit.getType() == HitResult.Type.MISS;
        }

        @Override
        public boolean inside() {
            return hit.isInside();
        }

        @Override
        public double x() {
            return hit.getLocation().x;
        }

        @Override
        public double y() {
            return hit.getLocation().y;
        }

        @Override
        public double z() {
            return hit.getLocation().z;
        }

        @Override
        public RoadSurface surface() {
            return SurfaceLookup.of(level().getBlockState(hit.getBlockPos()));
        }
    }



    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
        // 重力は物理コアが自分で積むのでバニラのものは要らない。
        // これを立てておかないと、サーバーが「乗り物で浮いている」と見なして
        // 80 ティック（4 秒）で multiplayer.disconnect.flying キックしてくる。
        // 判定は AABB の下 0.55m が全部空気かどうかで、AABB は車体だけなので
        // 車高が 0.61m を超える設定（サスストローク 29cm 以上）で必ず引っかかる
        setNoGravity(true);
        // 視錐台による打ち切りを外す。
        //
        // 当たり判定の箱は 1.8m 角しかないのに車の全長は 5m を超え、ヘッドライトの光の筋は
        // さらに 14m 前へ伸びる。箱を広げるだけでは、少し離れた車が画面の端で丸ごと消える
        // （箱が視錐台から外れた瞬間に、まだ見えているモデルごと消える）。
        //
        // 距離による打ち切りは別の判定（EntityRenderer#shouldRender は
        // 距離 → affectedByCulling → 視錐台 の順に見る）。そちらは shouldRenderAtSqrDistance で広げてある
        //
        // 26.3 で Entity#noCulling は無くなり、描画側の EntityRenderer#affectedByCulling に移った。
        // 外すのは CarObjRenderer（affectedByCulling で false を返す）
    }

    /**
     * 描画の距離打ち切りを広げる。
     *
     * <p>バニラの判定は {@code getBoundingBox().getSize() × 64} なので、当たり判定の箱
     * （1.8 × 0.7 × 1.8 で {@code getSize()} は 1.43）では<b>約 92m で車が丸ごと消える</b>。
     * 箱は車体だけを覆っていて全長 5m の見た目より小さいのに対し、サーバーは
     * spigot 系の {@code entity-tracking-range} の {@code other}（既定 150m）まで
     * 配ってくれている。<b>マルチプレイで遠くの車が見えなかったのはこれが原因。</b></p>
     *
     * <p><b>距離ではなく倍率で書くのは、動画設定の「エンティティの表示距離」
     * （50〜500%）に自分で触らずに追従させるため。</b>その係数は {@code Entity} の
     * private な static なので、こちらで距離を割ってバニラに判定させる方が確実。</p>
     *
     * <p>{@code noCulling}（視錐台）とは別の判定。あちらは光の筋が箱からはみ出すぶんの話で、
     * こちらは距離そのもの。{@code CarSmoke} も同じ {@code shouldRender} を通るので、
     * 煙の打ち切りも一緒に伸びる。</p>
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // バニラの箱は傾きで大きさが変わる（車体の箱の内側に収めているため）ので、
        // 登録している大きさの箱だったときと同じ距離になるよう割り戻す
        double size = getBoundingBox().getSize();
        if (!(size > 0.0)) {
            size = REGISTERED_BOX_SIZE;
        }
        double scale = RENDER_DISTANCE_SCALE * REGISTERED_BOX_SIZE / size;
        return super.shouldRenderAtSqrDistance(distanceSq / (scale * scale));
    }

    /**
     * バニラの当たり判定の箱。<b>車体の箱（{@link CarHull}）の内側に収まる水平な箱</b>。
     *
     * <p>地形と他の車との衝突は車体の箱で自分で解いている（{@link #moveCars}）。バニラの箱が
     * まだ使われるのは、サーバーの {@code handleMoveVehicle} が「動いた先が塞がっていないか」を
     * 見るとき、他の車を探すとき、クリックの当たり。<b>内側に収めてあれば、車体の箱が
     * 通した位置をサーバーが弾くことはない</b>（外にはみ出していると、傾いた車の箱の角が
     * 段に刺さったと見なされて引き戻される）。</p>
     */
    @Override
    protected AABB makeBoundingBox(Vec3 position) {
        if (state == null) {
            // Entity のコンストラクタから呼ばれた。まだ姿勢が無い
            return super.makeBoundingBox(position);
        }
        // 26.3 は「この位置に居たら」の箱も聞いてくる（ブロックの効果の判定など）。
        // 車体の箱はいまの位置で組むので、聞かれた位置へずらして返す
        double[] box = currentBody().inscribedBox(VANILLA_BOX_MARGIN);
        return new AABB(box[0], box[1], box[2], box[3], box[4], box[5])
                .move(position.x - getX(), position.y - getY(), position.z - getZ());
    }

    /**
     * いまの姿勢の車体の箱。手元で解いていれば物理の値、そうでなければ同期された値で組む。
     */
    public CarHull currentHull() {
        return hull(false);
    }

    /** いまの姿勢の、車同士の当たりに使う箱（{@link CarHull#body}）。 */
    public CarHull currentBody() {
        return hull(true);
    }

    private CarHull hull(boolean body) {
        boolean local = isLocalInstanceAuthoritative();
        double yaw = local ? state.yaw : carCore.remoteYaw();
        double pitch = local ? state.pitch : synced.pitch();
        double roll = local ? state.roll : synced.roll();
        return body
                ? CarHull.body(getSpec(), carCore.bodyShape(), getX(), getY(), getZ(), yaw, pitch, roll)
                : CarHull.of(getSpec(), getX(), getY(), getZ(), yaw, pitch, roll);
    }

    @Override
    public float getPickRadius() {
        return PICK_RADIUS;
    }

    /**
     * サーバーが運転者から届いた位置へ車を動かすとき（{@code MoverType.PLAYER}）は、
     * 地形を見ずにそのまま動かす。
     *
     * <p>{@code handleMoveVehicle} は届いた移動量でいったん {@code move()} を呼び、結果が
     * 届いた位置と 0.0625 以上ずれると「moved wrongly」として扱う。バニラの {@code move()}
     * は回らない箱で地形を見るので、車体の箱で通した段々の地形でずれが出る。
     * 物理を解いているのは運転者（クライアント権威）なので、ここで検算しないのが正しい。</p>
     */
    @Override
    public void move(MoverType type, Vec3 delta) {
        if (type == MoverType.PLAYER) {
            setPos(getX() + delta.x, getY() + delta.y, getZ() + delta.z);
            return;
        }
        super.move(type, delta);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STEER_ANGLE, 0.0F);
        builder.define(DATA_SPEED, 0.0F);
        builder.define(DATA_PITCH, 0.0F);
        builder.define(DATA_ROLL, 0.0F);
        for (int i = 0; i < DATA_SUSPENSION.size(); i++) {
            builder.define(DATA_SUSPENSION.get(i),
                    (float) CarSpec.DEFAULT.staticSuspensionLength(Wheel.VALUES[i]));
            builder.define(DATA_WHEEL_SPEED.get(i), 0.0F);
            builder.define(DATA_WHEEL_FRICTION.get(i), 0.0F);
        }
        builder.define(DATA_RPM, (float) CarSpec.DEFAULT.idleRpm());
        builder.define(DATA_GEAR, 0);
        builder.define(DATA_THROTTLE, 0.0F);
        builder.define(DATA_SLIP_ANGLE, 0.0F);
        builder.define(DATA_SURFACE, CarCore.packSurfaces(RoadSurface.PAVED));
        builder.define(DATA_LIGHTS, (byte) 0);
        builder.define(DATA_BOOST, 0.0F);
        builder.define(DATA_YAW, 0.0F);
        builder.define(DATA_DRIVER, -1);
    }

    /** 停車時のシャシー基準面の地上高 [m]。スポーンさせるときにこの高さぶん持ち上げる。 */
    public double getStaticRideHeight() {
        return carCore.staticRideHeight();
    }

    /** 物理が解く諸元（調整画面の諸元に、装着している部品の性能を載せたもの。{@link CarCore#spec()}）。 */
    public CarSpec getSpec() {
        return carCore.spec();
    }

    /** 初期装備を足した、実際に付いている状態（{@link CarCore#effectiveFitment()}）。 */
    public PartFitment getEffectiveFitment() {
        return carCore.effectiveFitment();
    }

    /** 中身（車種・諸元・部品）。 */
    public CarCore carCore() {
        return carCore;
    }

    /** ターボが付いているか。 */
    public boolean hasTurbo() {
        return carCore.hasTurbo();
    }

    /**
     * ブローオフバルブが付いているか（<b>音だけ</b>。ターボが無ければ鳴らない）。
     * 知らない部品（カーパックを外した）なら付いていないものとする。
     */
    public boolean hasBlowoff() {
        return carCore.hasBlowoff();
    }

    /** {@link CarCore#getRenderBoost}。 */
    public double getRenderBoost() {
        return carCore.getRenderBoost();
    }

    /**
     * 諸元を差し替える。調整画面から呼ばれ、次のティックからその値で物理が解かれる。
     *
     * <p>諸元は寸法（＝見た目）にも効くので、サーバー側で変わったときは周囲のクライアントへ配る。
     * 配らないと他人の車だけ既定の寸法で描かれる。</p>
     */
    /** 車種の id。見た目の定義を引くのと、名前を出すのに使う。 */
    public CarId getCarId() {
        return carCore.carId();
    }

    /**
     * 車種を決める。<b>アイテムから出すときに 1 度だけ呼ぶ。</b>
     *
     * <p>id と諸元をまとめて入れる。別々に入れられるようにすると、
     * 「id は AE86 なのに諸元は既定の車」という組み合わせを作れてしまう。</p>
     */
    public void setCarType(CarType type) {
        carCore.setCarId(type.id());
        setSpec(type.spec());
    }

    public void setSpec(CarSpec spec) {
        carCore.setBaseSpec(spec);
        if (!level().isClientSide()) {
            KurumaNetwork.sendToPlayersTrackingEntity(this, new CarSpecPacket(getId(), spec));
        }
    }

    /**
     * 装着している部品。付けていない場所は車種の既定の見た目になる。
     *
     * <p><b>{@code getParts()} という名前は使えない。</b>Forge の {@code IForgeEntity} が
     * 多分割エンティティ用に同じ名前（戻り値 {@code PartEntity<?>[]}）を持っているため。</p>
     */
    public PartFitment getFitment() {
        return carCore.fitment();
    }

    /**
     * 装着している部品を差し替える。
     *
     * <p>見た目に効くので、サーバー側で変わったときは周囲のクライアントへ配る。
     * 配らないと、換えた本人の画面でしか見た目が変わらない。車が現れるときのぶんは
     * {@link #writeSpawnData} が運ぶ。</p>
     */
    public void setFitment(PartFitment parts) {
        carCore.setFitment(parts);
        if (!level().isClientSide()) {
            KurumaNetwork.sendToPlayersTrackingEntity(this, new CarPartsPacket(getId(), parts));
        }
    }

    // ------------------------------------------------------------------
    // ティック処理
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        carCore.shiftWheelRoll();
        super.tick();

        if (level().isClientSide()) {
            tickLerp();
        }

        if (isLocalInstanceAuthoritative()) {
            if (!hadControl) {
                carCore.takeOverPhysics();
                hadControl = true;
            }
            carCore.physicsTick(carCore.driverInput());
            if (!level().isClientSide()) {
                // 無人の車をサーバーが動かしている場合。運転者から届く値の代わりに自分の結果を配る
                applyDriverState(stateSnapshot());
            }
        } else {
            hadControl = false;
        }

        carCore.updateWheelRoll();

        if (level().isClientSide() && isLocalInstanceAuthoritative()) {
            lastSentState = stateSnapshot();
            KurumaNetwork.sendToServer(lastSentState);
        } else {
            lastSentState = null;
        }

        if (!level().isClientSide()) {
            tickAbandoned();
        }

        // 位置が変わらなくても姿勢が変われば箱の形が変わる
        setBoundingBox(makeBoundingBox());
        carCore.updateRenderSnapshot();
    }






    /**
     * 放置された車を消す。
     *
     * <p>マルチプレイでは乗り捨てが溜まっていく一方で、無人の車もサーバーが毎ティック
     * 物理を解いている。設定した時間だけ誰も乗らなければ消す（0 で無効）。</p>
     */
    private void tickAbandoned() {
        if (carCore.tickAbandoned(Config.abandonedCarLifetimeSeconds, !getPassengers().isEmpty())) {
            discard();
        }
    }




    /**
     * 点がブロックの当たり判定の中にあれば、そこから上へすき間なく続くブロックの天面。
     * 中に無ければ負の無限大。
     */
    @Override
    public double solidTopAbove(double x, double y, double z) {
        double top = Double.NEGATIVE_INFINITY;
        double probe = y;
        for (int i = 0; i < 16; i++) {
            BlockPos pos = BlockPos.containing(x, probe, z);
            VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
            double found = Double.NEGATIVE_INFINITY;
            for (AABB box : shape.toAabbs()) {
                AABB moved = box.move(pos);
                if (x > moved.minX && x < moved.maxX && z > moved.minZ && z < moved.maxZ
                        && probe >= moved.minY - 1.0e-6 && probe < moved.maxY) {
                    found = Math.max(found, moved.maxY);
                }
            }
            if (found == Double.NEGATIVE_INFINITY) {
                break;
            }
            top = found;
            probe = found + 1.0e-4;
        }
        return top;
    }

    /** {@link #moveCars} の結果。当たった車（無ければ null）と、押し出しの中身。 */
    private record HullMove(@Nullable CarEntity car, CarHull.Motion motion) {
    }

    /**
     * 車同士用の箱（{@link CarHull#body}）で動かし、他の車から押し出す。地形は {@link CarBody} が解く。
     *
     * <p>他の車は当たり判定のルール（{@link CarRules#carCollision()}）が許すときだけ見る。
     * いちばん深く当たった車を返す——3 台が絡んだときに、掠めただけの相手を
     * 主たる衝突相手と取り違えないため。</p>
     */
    private HullMove moveCars(Vec3 delta) {
        CarHull hull = currentBody();
        double[] from = hull.bounds();
        AABB area = new AABB(from[0], from[1], from[2], from[3], from[4], from[5])
                .expandTowards(delta).inflate(0.5);
        List<CarEntity> cars = new ArrayList<>();
        List<CarHull> hulls = new ArrayList<>();
        if (CarRules.carCollision()) {
            // 相手のバニラの箱は車体の箱より小さいので、広めに探す
            for (CarEntity other : level().getEntitiesOfClass(CarEntity.class, area.inflate(3.0),
                    car -> car != this && !car.isRemoved() && !car.isGhost())) {
                cars.add(other);
                hulls.add(other.currentBody());
            }
        }
        CarHull.Motion motion = hull.move(delta.x, delta.y, delta.z, List.of(), currentBody(), hulls);
        Vec3 startPos = position();
        setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);
        // バニラの move() がしていた、踏んだブロックへの通知（感圧板・ポータルなど）
        applyEffectsFromBlocks(startPos, position());
        return new HullMove(motion.car >= 0 ? cars.get(motion.car) : null, motion);
    }




    /**
     * 車を止めるワールドボーダーの内側 {minX, minZ, maxX, maxZ}。止めないときは null。
     * {@link #collectBlocks} が 1 ティックに 1 回決める。
     */
    @Nullable
    private double[] border;

    /**
     * ワールドボーダーを壁として当たり判定の箱に足す。
     *
     * <p>{@code getBlockCollisions} はボーダーを返さない（バニラは {@code Entity#collide} で
     * 別に足している）ので、車の物理はボーダーを素通りしていた。引っかかって見えたのは、
     * サーバーの {@code handleMoveVehicle} が「ボーダーに食い込んだ」として位置を差し戻していたから。
     * 速いと 1 ティックで車の箱ごとボーダーの外へ出てしまい、差し戻す判定（ボーダーの内側に
     * いる箱だけを止める）を抜けて、アクセルを踏み続けると突破できた。物理の側で壁にして止める。</p>
     *
     * <p>バニラと同じく、中心がボーダーの内側にある車だけを止める。外にいる車（ボーダーが
     * 縮んだ・外で出した）を壁の中に閉じ込めないため。</p>
     */
    private void addWorldBorder(BlockField field, AABB area, double x, double z) {
        WorldBorder worldBorder = level().getWorldBorder();
        border = null;
        if (!worldBorder.isWithinBounds(x, z)) {
            return;
        }
        double minX = worldBorder.getMinX();
        double minZ = worldBorder.getMinZ();
        double maxX = worldBorder.getMaxX();
        double maxZ = worldBorder.getMaxZ();
        border = new double[]{minX, minZ, maxX, maxZ};
        field.addBorderWalls(new double[]{area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ},
                border);
    }

    /** 点が車を止めるワールドボーダーの外か。範囲の外を聞かれたときも、壁が途切れないように答える。 */
    private boolean outsideBorder(double x, double z) {
        return BlockField.outsideBorder(border, x, z);
    }

    /** 当たり判定の箱の範囲の外を引いたブロックの形。1 ティックの間だけ覚えておく。 */
    private final Map<Long, VoxelShape> outsideShapes = new HashMap<>();

    /** 点がブロックの当たり判定の中か。当たり判定の箱の範囲の外を聞かれたときに使う。 */
    private boolean solidOutside(double x, double y, double z) {
        if (outsideBorder(x, z)) {
            return true;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        VoxelShape shape = outsideShapes.computeIfAbsent(pos.asLong(),
                key -> level().getBlockState(pos).getCollisionShape(level(), pos));
        if (shape.isEmpty()) {
            return false;
        }
        double lx = x - pos.getX();
        double ly = y - pos.getY();
        double lz = z - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx > box.minX && lx < box.maxX && ly > box.minY && ly < box.maxY && lz > box.minZ && lz < box.maxZ) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    /** {@link CarCore#tireRims}。 */
    public double[][][] tireRims() {
        return carCore.tireRims();
    }



    @Nullable
    /** {@link CarCore#staircaseLines}。 */
    public List<double[]> staircaseLines() {
        return carCore.staircaseLines();
    }


    /** {@link CarCore#setVisualTireSpan}。 */
    public void setVisualTireSpan(double inner, double outer) {
        carCore.setVisualTireSpan(inner, outer);
    }


    @Override
    /** {@link CarCore#tireGroundEdges}。 */
    public double[] tireGroundEdges() {
        return carCore.tireGroundEdges();
    }

    /** {@link CarCore#bodyCorners}。 */
    public double[][] bodyCorners() {
        return carCore.bodyCorners();
    }

    /**
     * 当たった車へ押されたぶんを届ける（{@link CarCore} が決めた量）。
     *
     * <p>物理を解いているのは相手の持ち主なので、ここで相手を動かしても次のティックに上書きされる。
     * クライアントからはサーバー経由（{@link CarPushPacket}）で、サーバー（無人の車を解いている）からは
     * 相手の運転者へ直接届ける。運転者のいない相手はサーバーが解いているので、その場で足す。</p>
     */
    @Override
    public void pushCar(CarHost target, float dx, float dz, float dYawRate) {
        CarEntity other = (CarEntity) target;
        CarPushPacket push = new CarPushPacket(other.getId(), dx, dz, dYawRate);
        if (level().isClientSide()) {
            KurumaNetwork.sendToServer(push);
            return;
        }
        // 無人の車はサーバーが解いているので、ここはサーバーのスレッド。サーバー宛てに送ると
        // クライアントの接続を引きにいき、ワールドを開いた直後（まだ繋がっていない）に落ちる。
        // パケットを受けたサーバーと同じく、相手の持ち主へ直接届ける
        if (other.getControllingPassenger() instanceof net.minecraft.server.level.ServerPlayer driver) {
            KurumaNetwork.sendToPlayer(driver, push);
        } else {
            other.applyPush(push.dx(), push.dz(), push.dYawRate());
        }
    }

    /** {@link CarCore#applyPush}。 */
    public void applyPush(double dx, double dz, double dYawRate) {
        carCore.applyPush(dx, dz, dYawRate);
    }

    /** {@link CarCore#worldVelocityX}。 */
    public double worldVelocityX() {
        return carCore.worldVelocityX();
    }

    /** {@link CarCore#worldVelocityZ}。 */
    public double worldVelocityZ() {
        return carCore.worldVelocityZ();
    }



    /**
     * ぶつかったことを音と揺れに変える。
     *
     * <p><b>気づけるのは物理を解いている側だけ</b>なので、運転クライアント（または無人の車を持つ
     * サーバー）がここで拾い、{@link CarImpactPacket} で周りへ配る。運転者は自分の画面へ
     * 先に出しておく——往復を待つと、ぶつかった手応えが 1 往復ぶん遅れて届くことになる。</p>
     */
    @Override
    public void reportImpact(double impact) {
        if (impact < IMPACT_MIN_SPEED) {
            return;
        }
        if (level().isClientSide()) {
            CarImpact.onImpact(this, impact);
            KurumaNetwork.sendToServer(new CarImpactPacket(getId(), (float) impact));
        } else {
            CarPacketHandlers.broadcastImpact(this, (float) impact);
        }
    }

    /**
     * 車体が擦れた・ぶつかったことを火花に変える。衝突（{@link #reportImpact}）と同じく、
     * 気づけるのは物理を解いている側だけなので、ここで拾って {@link CarScrapePacket} で周りへ配る。
     * 運転者は自分の画面へ先に出す。
     */
    @Override
    public void reportScrape(float[] offset, float[] normal, float[] velocity, float impact) {
        if (level().isClientSide()) {
            CarSparks.emit(this, offset, normal, velocity, impact);
            KurumaNetwork.sendToServer(new CarScrapePacket(getId(), offset, normal, velocity, impact));
        } else {
            CarPacketHandlers.broadcastScrape(this, offset, normal, velocity, impact);
        }
    }

    private static final int SURFACE_BITS = CarCore.SURFACE_BITS;
    private static final int AIRBORNE_BIT = CarCore.AIRBORNE_BIT;
    private static final int SURFACE_MASK = CarCore.SURFACE_MASK;



    /** {@link CarCore#getRenderSurface}。 */
    public RoadSurface getRenderSurface(Wheel wheel) {
        return carCore.getRenderSurface(wheel);
    }

    /** {@link CarCore#isRenderWheelAirborne}。 */
    public boolean isRenderWheelAirborne(Wheel wheel) {
        return carCore.isRenderWheelAirborne(wheel);
    }

    /** {@link CarCore#getRenderSurface}。 */
    public RoadSurface getRenderSurface() {
        return carCore.getRenderSurface();
    }

    /** {@link CarCore#getRenderWetness}。 */
    public double getRenderWetness() {
        return carCore.getRenderWetness();
    }

    /** サーバーから届いた位置へ数ティックかけて寄せる。運転していないクライアント用。 */
    private void tickLerp() {
        if (isLocalInstanceAuthoritative()) {
            // 自分が権威なので、サーバーから来た位置は捨てて自分の計算結果を通す
            lerpSteps = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
            return;
        }
        if (lerpSteps <= 0) {
            return;
        }

        double x = getX() + (lerpX - getX()) / lerpSteps;
        double y = getY() + (lerpY - getY()) / lerpSteps;
        double z = getZ() + (lerpZ - getZ()) / lerpSteps;
        float yRot = getYRot() + (float) Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps;
        lerpSteps--;

        setPos(x, y, z);
        setRot(yRot, getXRot());
    }


    /** 届いた位置を補間の目標にする。サーバーからの位置もゴーストの記録もここを通る。 */
    private void setLerpTarget(double x, double y, double z, float yRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = Math.max(steps, 1);
    }

    /**
     * バニラの補間の口（26.3 で {@code lerpTo} の代わりになった）。
     *
     * <p>サーバーから届いた位置（{@code moveOrInterpolateTo}）はここへ来る。<b>{@code true} を返して
     * 「受け取った」ことにし、バニラに {@code setPos} させない</b>——させると運転者の計算結果が
     * サーバーの位置で上書きされて車がガタつく。目標だけ覚えて、{@link #tickLerp()} が
     * 運転していないときにだけ寄せる（運転しているときは捨てる）。</p>
     */
    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return new InterpolationHandler() {
            @Override
            public @Nullable PositionAndRotation target() {
                return lerpSteps > 0 ? PositionAndRotation.of(new Vec3(lerpX, lerpY, lerpZ), (float) lerpYRot, getXRot()) : null;
            }

            @Override
            public boolean interpolateTo(@Nullable PositionPath position, float yRot, float xRot, boolean hasRotation) {
                Vec3 to = position != null ? position.endPosition() : new Vec3(
                        lerpSteps > 0 ? lerpX : getX(), lerpSteps > 0 ? lerpY : getY(), lerpSteps > 0 ? lerpZ : getZ());
                float yaw = hasRotation ? yRot : (lerpSteps > 0 ? (float) lerpYRot : getYRot());
                // バニラ（1.21.1 まで）がエンティティの移動で渡していた刻みと同じ 3
                setLerpTarget(to.x, to.y, to.z, yaw, 3);
                return true;
            }

            @Override
            public void interpolate() {
                // 寄せるのは tick() の中の tickLerp() でやる（物理の前に位置を決めたいので）
            }

            @Override
            public void applyPredictedMovement(Vec3 delta) {
            }

            @Override
            public boolean hasActiveInterpolation() {
                return lerpSteps > 0;
            }

            @Override
            public void cancel() {
                lerpSteps = 0;
            }
        };
    }

    // ------------------------------------------------------------------
    // 外部から状態を差し込む口
    // ------------------------------------------------------------------

    /** このティックに送った状態。運転していなければ null。 */
    @Nullable
    public CarStatePacket lastSentState() {
        return lastSentState;
    }

    /** {@link CarCore#isGhost}。 */
    public boolean isGhost() {
        return carCore.isGhost();
    }

    /**
     * 記録を再生する車にする。<b>クライアントでだけ、ワールドへ足す前に呼ぶ。</b>
     *
     * <p>車種・諸元・装着は記録したときのもの。いま乗っている車とは関係なく、
     * 記録した車の姿で走る。</p>
     */
    public void becomeGhost(CarId carId, CarSpec spec, PartFitment parts) {
        carCore.markGhost();
        carCore.setCarId(carId);
        carCore.setBaseSpec(spec);
        carCore.setFitment(parts);
        setNoGravity(true);
    }

    /**
     * アイテムのアイコンに描く車にする。<b>クライアントでだけ、ワールドへは足さずに使う。</b>
     *
     * <p>車種・諸元・装着を入れた後に呼ぶ。サスペンションの長さはふだん同期された値から
     * 描くが、その初期値は既定の車の停車時の長さなので、この車の停車時の長さを入れ直す
     * （入れないと、車高の違う車ではタイヤが車体から浮くか車体に埋まる）。</p>
     */
    public void becomeIcon() {
        CarSpec current = getSpec();
        for (int i = 0; i < Wheel.COUNT; i++) {
            entityData.set(DATA_SUSPENSION.get(i), (float) current.staticSuspensionLength(Wheel.VALUES[i]));
        }
        carCore.updateRenderSnapshot();
    }

    /**
     * 記録の 1 ティックぶんを流し込む。他人の車がサーバーから受け取るのと同じ道
     * （位置は {@link #setLerpTarget}、姿勢と車輪は同期値）なので、描き方も補間も同じになる。
     */
    public void playGhostFrame(double x, double y, double z, CarStatePacket frame) {
        setLerpTarget(x, y, z, frame.yaw(), 3);
        applyDriverState(frame);
    }

    @Nullable
    /** {@link CarCore#steeringInputHeld}。 */
    public Boolean steeringInputHeld() {
        return carCore.steeringInputHeld();
    }

    /** {@link CarCore#setDriverInput}。 */
    public void setDriverInput(CarInput input) {
        carCore.setDriverInput(input);
    }


    /** {@link CarCore#setHeadlights}。 */
    public void setHeadlights(boolean on) {
        carCore.setHeadlights(on);
    }


    /** {@link CarCore#isHeadlightOnSynced}。 */
    public boolean isHeadlightOnSynced() {
        return carCore.isHeadlightOnSynced();
    }

    /** {@link CarCore#getRenderLights}。 */
    public byte getRenderLights() {
        return carCore.getRenderLights();
    }

    /** {@link CarCore#isHeadlightOn}。 */
    public boolean isHeadlightOn() {
        return carCore.isHeadlightOn();
    }

    /** {@link CarCore#isBrakeLightOn}。 */
    public boolean isBrakeLightOn() {
        return carCore.isBrakeLightOn();
    }

    /** {@link CarCore#isReverseLightOn}。 */
    public boolean isReverseLightOn() {
        return carCore.isReverseLightOn();
    }

    /** いまの物理の状態を、配る形（{@link CarStatePacket}）にする。運転しているクライアントが送り、サーバーは自分で解いたときに覚える。 */
    private CarStatePacket stateSnapshot() {
        return new CarStatePacket(getId(),
                    (float) state.steerAngle, (float) state.forwardSpeed,
                    getYRot(), (float) state.pitch, (float) state.roll,
                    carCore.suspensionSnapshot(), carCore.wheelSpeedSnapshot(), carCore.frictionSnapshot(),
                    (float) state.engineRpm, state.gear, (float) state.throttle,
                    (float) (state.slipAngle() * Mth.RAD_TO_DEG), carCore.packSurfaces(),
                    carCore.driverLights(), (float) state.boost);
    }

    /** サーバーが {@link CarStatePacket} から受け取った値を反映する。 */
    public void applyDriverState(CarSync values) {
        entityData.set(DATA_STEER_ANGLE, values.steerAngle());
        entityData.set(DATA_SPEED, values.speed());
        entityData.set(DATA_YAW, values.yaw());
        entityData.set(DATA_PITCH, values.pitch());
        entityData.set(DATA_ROLL, values.roll());
        for (int i = 0; i < DATA_SUSPENSION.size(); i++) {
            entityData.set(DATA_SUSPENSION.get(i), values.suspension(i));
            entityData.set(DATA_WHEEL_SPEED.get(i), values.wheelSpeed(i));
            entityData.set(DATA_WHEEL_FRICTION.get(i), values.friction(i));
        }
        entityData.set(DATA_RPM, values.rpm());
        entityData.set(DATA_GEAR, values.gear());
        entityData.set(DATA_THROTTLE, values.throttle());
        entityData.set(DATA_SLIP_ANGLE, values.slipAngle());
        entityData.set(DATA_SURFACE, values.surface());
        entityData.set(DATA_LIGHTS, values.lights());
        entityData.set(DATA_BOOST, Math.max(0.0F, Math.min(1.0F, values.boost())));
        if (!level().isClientSide()) {
            // 運転者が入れた状態を覚えておく。降りた後もサーバーがこれを配り続ける
            carCore.setHeadlights((values.lights() & LIGHT_HEAD) != 0);
        }
    }

    // ------------------------------------------------------------------
    // 描画向けの取得
    // ------------------------------------------------------------------


    /** 自分の手元で物理を解いているか（＝同期を待たずに自前の値を描画に使えるか） */
    private boolean hasLocalState() {
        return level().isClientSide() && isLocalInstanceAuthoritative();
    }

    /** {@link CarCore#getRenderSteerAngle}。 */
    public float getRenderSteerAngle() {
        return carCore.getRenderSteerAngle();
    }

    /** {@link CarCore#getVisualSteerAngle}。 */
    public float getVisualSteerAngle(float partialTick) {
        return carCore.getVisualSteerAngle(partialTick);
    }

    /** {@link CarCore#getVisualSteerAngle}。 */
    public float getVisualSteerAngle() {
        return carCore.getVisualSteerAngle();
    }




    /** {@link CarCore#getRenderSpeed}。 */
    public float getRenderSpeed() {
        return carCore.getRenderSpeed();
    }

    /** {@link CarCore#getRenderPitch}。 */
    public float getRenderPitch() {
        return carCore.getRenderPitch();
    }

    /** {@link CarCore#getRenderYaw}。 */
    public float getRenderYaw(float partialTick) {
        return carCore.getRenderYaw(partialTick);
    }

    /** {@link CarCore#getRenderPitch}。 */
    public float getRenderPitch(float partialTick) {
        return carCore.getRenderPitch(partialTick);
    }

    /** {@link CarCore#getRenderRoll}。 */
    public float getRenderRoll(float partialTick) {
        return carCore.getRenderRoll(partialTick);
    }


    /** {@link CarCore#getRenderSteerAngle}。 */
    public float getRenderSteerAngle(float partialTick) {
        return carCore.getRenderSteerAngle(partialTick);
    }

    /** {@link CarCore#getRenderSuspensionLength}。 */
    public double getRenderSuspensionLength(Wheel wheel, float partialTick) {
        return carCore.getRenderSuspensionLength(wheel, partialTick);
    }

    /** {@link CarCore#getRenderRoll}。 */
    public float getRenderRoll() {
        return carCore.getRenderRoll();
    }

    /** {@link CarCore#getRenderSuspensionLength}。 */
    public double getRenderSuspensionLength(Wheel wheel) {
        return carCore.getRenderSuspensionLength(wheel);
    }

    /** {@link CarCore#getWheelRoll}。 */
    public float getWheelRoll(Wheel wheel, float partialTick) {
        return carCore.getWheelRoll(wheel, partialTick);
    }

    /** {@link CarCore#getRenderSlipRatio}。 */
    public double getRenderSlipRatio(Wheel wheel) {
        return carCore.getRenderSlipRatio(wheel);
    }

    /** {@link CarCore#getRenderFrictionPower}。 */
    public double getRenderFrictionPower(Wheel wheel) {
        return carCore.getRenderFrictionPower(wheel);
    }

    /** {@link CarCore#getRenderScrubSpeed}。 */
    public double getRenderScrubSpeed(Wheel wheel) {
        return carCore.getRenderScrubSpeed(wheel);
    }


    /** {@link CarCore#getRenderSlideSpeed}。 */
    public double getRenderSlideSpeed(Wheel wheel) {
        return carCore.getRenderSlideSpeed(wheel);
    }

    /** {@link CarCore#getRenderThrottle}。 */
    public double getRenderThrottle() {
        return carCore.getRenderThrottle();
    }

    /** {@link CarCore#getRenderBrake}。 */
    public double getRenderBrake() {
        return carCore.getRenderBrake();
    }

    /** {@link CarCore#getRenderWheelLoad}。 */
    public double getRenderWheelLoad(Wheel wheel) {
        return carCore.getRenderWheelLoad(wheel);
    }

    /** {@link CarCore#getRenderLateralAcceleration}。 */
    public double getRenderLateralAcceleration() {
        return carCore.getRenderLateralAcceleration();
    }

    /** {@link CarCore#getRenderRpm}。 */
    public double getRenderRpm() {
        return carCore.getRenderRpm();
    }

    /** {@link CarCore#isTractionControlActive}。 */
    public boolean isTractionControlActive() {
        return carCore.isTractionControlActive();
    }

    /** {@link CarCore#isHandbrakeOn}。 */
    public boolean isHandbrakeOn() {
        return carCore.isHandbrakeOn();
    }

    /** {@link CarCore#getRenderHandbrake}。 */
    public double getRenderHandbrake() {
        return carCore.getRenderHandbrake();
    }

    /** {@link CarCore#getRenderClutchPedal}。 */
    public double getRenderClutchPedal() {
        return carCore.getRenderClutchPedal();
    }

    /** {@link CarCore#getRenderLeverGear}。 */
    public int getRenderLeverGear() {
        return carCore.getRenderLeverGear();
    }

    /** {@link CarCore#isAbsActive}。 */
    public boolean isAbsActive() {
        return carCore.isAbsActive();
    }

    /** {@link CarCore#getRenderGear}。 */
    public int getRenderGear() {
        return carCore.getRenderGear();
    }

    /** {@link CarCore#getRenderSlipAngleDegrees}。 */
    public double getRenderSlipAngleDegrees() {
        return carCore.getRenderSlipAngleDegrees();
    }

    // ------------------------------------------------------------------
    // 乗車まわり
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (carCore.isGhost() || player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide()) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        int driver = entityData.get(DATA_DRIVER);
        if (driver < 0) {
            return null;
        }
        for (Entity passenger : getPassengers()) {
            if (passenger.getId() == driver) {
                return passenger instanceof LivingEntity living ? living : null;
            }
        }
        return null;
    }

    /**
     * 運転席と助手席の 2 人まで。
     *
     * <p><b>運転席が空いていれば運転席、埋まっていれば助手席に乗る。</b> 席は降りるまで変わらない
     * （{@link #DATA_DRIVER}）。運転者かどうかの判定はすべて {@link #getControllingPassenger()} を通っているので、
     * 助手席の人は入力も送らないし、パケットも受け付けられないし、レースの計測にも乗らない。</p>
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        // 運転席が空いていれば、乗った人が運転席へ。埋まっていれば助手席のまま
        if (!level().isClientSide() && getControllingPassenger() == null && passenger instanceof LivingEntity) {
            entityData.set(DATA_DRIVER, passenger.getId());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        // 運転者が降りたら運転席は空ける。<b>助手席の人を繰り上げない</b>（運転したければ乗り直す）
        if (!level().isClientSide() && passenger.getId() == entityData.get(DATA_DRIVER)) {
            entityData.set(DATA_DRIVER, -1);
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) {
            return;
        }
        // 席は車体に付いているので、車体と一緒に傾ける。ヨーだけで回していた頃は
        // 車がロールしても乗員は水平のまま浮いていて、一人称の視点だけが車体から取り残された
        Vec3 seat = toWorld(seatOffset(passenger).add(0.0, ridingOffset(passenger), 0.0),
                hasLocalState() ? getYRot() : synced.yaw(), getRenderPitch(), getRenderRoll());
        move.accept(passenger, getX() + seat.x, getY() + seat.y, getZ() + seat.z);
    }

    /**
     * 乗員の足元の位置を、<b>車体の座標</b>（乗員の向き。+X 左・+Y 上・+Z 前）で。
     * ハンドルを握る手の向きを、車体の中で組み立てるのに使う（{@code SteeringHands}）。
     */
    public Vec3 riderSeatLocal(Entity passenger) {
        return seatOffset(passenger).add(0.0, ridingOffset(passenger), 0.0);
    }

    /**
     * 乗員の目の位置（世界座標）。<b>一人称のカメラが毎フレーム置く位置。</b>
     *
     * <p>乗員の位置は {@link #positionRider} が 20Hz で置くだけなので、それを目線にすると
     * 車体の傾きが階段状にしか伝わらない。補間済みの車の位置と姿勢から直接組み立てる。</p>
     */
    public Vec3 riderEyePosition(Entity passenger, float partialTick) {
        return riderPosition(passenger, partialTick, passenger.getEyeHeight());
    }

    /**
     * 乗員の足元（{@link #positionRider} が置く位置）を、補間済みの車の位置と姿勢から組んだもの。
     * <b>三人称で乗員を描くときに、足元をここへ合わせ直す</b>（{@code RiderPoseRenderer}）。
     */
    public Vec3 riderFeetPosition(Entity passenger, float partialTick) {
        return riderPosition(passenger, partialTick, 0.0);
    }

    private Vec3 riderPosition(Entity passenger, float partialTick, double height) {
        Vec3 local = seatOffset(passenger).add(0.0, ridingOffset(passenger) + height, 0.0);
        Vec3 offset = toWorld(local, getRenderYaw(partialTick),
                getRenderPitch(partialTick), getRenderRoll(partialTick));
        return new Vec3(Mth.lerp(partialTick, xo, getX()) + offset.x,
                Mth.lerp(partialTick, yo, getY()) + offset.y,
                Mth.lerp(partialTick, zo, getZ()) + offset.z);
    }

    /**
     * 乗員の座標系（+X 左・+Y 上・+Z 前）のベクトルを、車体の姿勢で世界の向きへ回す。
     *
     * <p>{@code CarObjRenderer#applyRotations} と同じ順（ヨー → ピッチ → ロール）。
     * ピッチは正で鼻上げ、ロールは正で右下がり（＝左が上がる）。</p>
     *
     * @param yaw 度。pitch と roll はラジアン
     */
    public static Vec3 toWorld(Vec3 local, float yaw, float pitch, float roll) {
        return local.zRot(-roll).xRot(pitch).yRot(-yaw * Mth.DEG_TO_RAD);
    }

    /**
     * 乗員の足元を席からどれだけ下げるか。
     *
     * <p><b>1.20.1 版と同じ姿勢で座らせるため、プレイヤーは 1.20.1 の値（-0.35）のまま。</b>
     * 1.20.5 で乗り物の仕組みが「乗員側の取り付け点」に変わり、プレイヤーの取り付け点は 0.6 になった。
     * そのまま使うと 25cm 沈み、{@code vehicles/<car>.json} の {@code seat}（1.20.1 で合わせた値）が
     * ずれる。プレイヤー以外は取り付け点に従う。</p>
     */
    private Vec3 vehicleAttachment(Entity passenger) {
        return passenger.getVehicleAttachmentPoint(this);
    }

    private double ridingOffset(Entity passenger) {
        return passenger instanceof Player ? PLAYER_RIDING_OFFSET : -vehicleAttachment(passenger).y;
    }

    /** 1.20.1 の {@code Player#getMyRidingOffset()}。 */
    private static final double PLAYER_RIDING_OFFSET = -0.35;

    /**
     * この乗員の席。先に乗った方が運転席、次が助手席。
     *
     * <p><b>クライアントは見た目の定義（{@code vehicles/car.json} の {@code seat}）から引く。</b>
     * 座面の高さは車体の形で決まるので車ごとに違い、物理は一切読まない値だから。
     * サーバーは {@code assets/} を読まないので下の既定値を使うが、<b>乗員の位置は
     * 各クライアントが自分で計算する</b>ので、サーバー側の値はどこにも見えない。</p>
     */
    private Vec3 seatOffset(Entity passenger) {
        boolean passengerSeat = passenger.getId() != entityData.get(DATA_DRIVER);
        Vec3 seat = null;
        if (level().isClientSide()) {
            // クライアントの側でしか通らない（isClientSide()）ので、クライアント専用のクラスを直接呼んでよい
            var offset = CarSeat.seatOffset(carCore.carId(), passengerSeat);
            seat = new Vec3(offset.x(), offset.y(), offset.z());
        }
        if (seat == null) {
            seat = passengerSeat ? PASSENGER_SEAT_OFFSET : DRIVER_SEAT_OFFSET;
        }
        // 席は車体の一部。車体メッシュの原点は前後輪の中点なので、席もそこを基準に書く。
        // エンティティの原点は重心なので、その差を足す（前 56% なら 0.187m 後ろ）。
        // これを忘れると、荷重配分のスライダーを動かしたとき車体だけが動いて席が取り残される
        return seat.add(0.0, 0.0, carCore.baseSpec().axleMidpointOffset());
    }

    // ------------------------------------------------------------------
    // Entity のその他の振る舞い
    // ------------------------------------------------------------------

    @Override
    public boolean isPickable() {
        return !isRemoved() && !carCore.isGhost();
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return !carCore.isGhost();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 車同士が当たるかは<b>サーバーが決める</b>（{@link CarRules#carCollision()}、
     * 既定は当たる。{@code /kuruma-admin collide false} で切れる）。
     *
     * <p>切れるようにしてあるのは、物理をそれぞれの運転クライアントが解いているため。
     * ぶつけると<b>両者で違う結果になって巻き戻り合う</b>ことがあり、加えてサーバーの
     * {@code handleMoveVehicle} は<b>「動く前は重なっていなかったのに動いた後は重なっている」
     * と判定すると移動を却下して位置を巻き戻す</b>（バイトコードで確認。判定は
     * {@code noCollision} → {@code getEntityCollisions} を通るので、<b>この
     * {@code canCollideWith} がそのまま効く</b>）。手元で止まっていれば重ならないので
     * 普段は起きないが、相手の位置がサーバーと食い違うほど遅れていると引き戻される。</p>
     *
     * <p><b>この値は両側で一致していなければならない。</b>だから設定ファイルではなく
     * サーバーが配った値（{@link CarRules}）を読む。クライアントだけ「当たらない」と
     * 思って重なる位置まで走ると、サーバーに引き戻され続ける。</p>
     *
     * <p>判定は動いた側で行われるが、どちらが動いても両方 {@link CarEntity} なので対称に効く。
     * 当たったぶんは衝突音と画面の揺れがそのまま乗る。</p>
     */
    @Override
    public boolean canCollideWith(Entity other) {
        if (carCore.isGhost() || other instanceof CarEntity car && car.isGhost()) {
            return false;
        }
        if (!CarRules.carCollision() && other instanceof CarEntity) {
            return false;
        }
        return super.canCollideWith(other);
    }

    /**
     * <b>殴ると消え、シフト＋殴りで回収する。</b>回収したアイテムには諸元も装着した部品も
     * 入るので、置き直せば同じ車が同じセッティングで出てくる（{@link CarSpawnItem#stackFor(CarEntity)}）。
     *
     * <p>素の殴りは何も残さずに消す。設定を残したいときはシフトを押しながら殴る。</p>
     *
     * <p>運転席に人がいる車はどちらもさせない。走っている車を外から消せてしまうため。
     * <b>助手席にだけ人がいる車は壊せる</b>（同乗者は降ろされる）。残った同乗者が車を守ってしまわないように。
     * 乗っている本人は自分の車を壊せない。</p>
     */
    @Override
    public boolean hurtClient(DamageSource source) {
        // 1.21.1 の hurt はクライアントでは true を返していた（殴った手応えを出すため）
        return !carCore.isGhost();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (carCore.isGhost()) {
            return false;
        }
        if (isRemoved()) {
            return true;
        }
        if (!(source.getEntity() instanceof Player player)
                || getControllingPassenger() != null || player.getVehicle() == this) {
            return false;
        }
        if (!player.isShiftKeyDown()) {
            discard();
            return true;
        }
        ItemStack stack = CarSpawnItem.stackFor(this);
        // 手が塞がっていたら足元へ落とす。回収できずに車だけ消えるのが最悪なので
        if (!player.getInventory().add(stack)) {
            spawnAtLocation(level, stack);
        }
        discard();
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        // 拾ったアイテムからは同じ車が出る
        return CarSpawnItem.stackFor(carCore.carId());
    }

    @Override
    public Component getName() {
        return RaceTexts.of(CarTypes.get(carCore.carId()).displayName());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        // 26.3 で保存は ValueInput / ValueOutput になった。キーと中身の形は 1.21.1 と同じ
        // （Spec と Parts は CompoundTag をそのまま入れているので、古いワールドもそのまま読める）
        //
        // 車種を外していても落ちないこと。知らない id は CarTypes 側で既定の車に落ちるが、
        // id 自体は覚えておく（カーパックを入れ直せばまた元の車に戻る）
        input.getString("CarId").map(CarId::parse).ifPresent(parsed -> carCore.setCarId(parsed));
        // ここは saveWithoutId 経由なので配信はしない（まだ誰も追跡していない）
        input.read("Spec", CompoundTag.CODEC).ifPresent(tag -> carCore.setBaseSpec(CarSpecNbt.load(tag)));
        // 装着している部品。知らない id もそのまま覚えておく（カーパックを入れ直せば戻る）
        input.read("Parts", CompoundTag.CODEC).ifPresent(tag -> carCore.setFitment(PartFitments.load(tag)));
        // 姿勢。物理を引き継ぐときに takeOverPhysics がここから読む。
        // 保存しないと、ひっくり返ったまま放置した車がチャンクの読み直しで水平に戻る。
        // 単位はラジアン（/summon で横転した車を出すのにも使う）
        entityData.set(DATA_PITCH, input.getFloatOr("Pitch", 0.0F));
        entityData.set(DATA_ROLL, input.getFloatOr("Roll", 0.0F));
        carCore.setHeadlights(input.getBooleanOr("Headlights", false));
        entityData.set(DATA_LIGHTS, (byte) (carCore.headlightsOn() ? LIGHT_HEAD : 0));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("CarId", carCore.carId().toString());
        // 諸元を保存しないと、リログやチャンクの読み直しで調整が消えて車高が変わる
        output.store("Spec", CompoundTag.CODEC, CarSpecNbt.save(carCore.baseSpec()));
        // 保存しないと、リログやチャンクの読み直しで換装が消えて既定の見た目に戻る
        if (!carCore.fitment().isEmpty()) {
            output.store("Parts", CompoundTag.CODEC, PartFitments.save(carCore.fitment()));
        }
        output.putFloat("Pitch", synced.pitch());
        output.putFloat("Roll", synced.roll());
        output.putBoolean("Headlights", carCore.headlightsOn());
    }

    /**
     * 車がクライアントに現れるときに諸元を一緒に送る。
     *
     * <p>諸元は寸法（＝見た目）に効くので、これが無いと他人の車が既定の寸法で描かれる。
     * 走行中の変更は {@link CarSpecPacket} が受け持つ。</p>
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        // 車種も一緒に。これが無いと、他人の車が全部既定の見た目で描かれる
        McIds.write(buf, carCore.carId());
        CarSpecNbt.write(buf, carCore.baseSpec());
        // 装着した部品も。これが無いと、後から見た人には既定のホイールで見える
        PartFitments.write(buf, carCore.fitment());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        carCore.setCarId(McIds.read(buf));
        carCore.setBaseSpec(CarSpecNbt.read(buf));
        carCore.setFitment(PartFitments.read(buf));
    }

    // ---- core の口（EmitterCar・SkidMarkTrails.Car）。Minecraft と同じ名前は配布環境で満たされないので、名前を変えて渡す ----

    @Override
    public int carEntityId() {
        return getId();
    }

    @Override
    public float vanillaYaw() {
        return getYRot();
    }

    @Override
    public double posX() {
        return getX();
    }

    @Override
    public double posY() {
        return getY();
    }

    @Override
    public double posZ() {
        return getZ();
    }

    // ------------------------------------------------------------------
    // CarHost（CarCore が聞くこと）
    // ------------------------------------------------------------------

    @Override
    public boolean simulatedOnThisClient() {
        return hasLocalState();
    }

    @Override
    public boolean simulatedHere() {
        return isLocalInstanceAuthoritative();
    }

    @Override
    public CarSync synced() {
        return synced;
    }

    @Override
    public boolean clientSide() {
        return level().isClientSide();
    }

    @Override
    public void setVanillaYaw(float yaw) {
        setYRot(yaw);
    }

    @Override
    public void wrapVanillaRotation() {
        setRot(getYRot(), getXRot());
    }

    @Override
    public void placeAt(double x, double y, double z) {
        setPos(x, y, z);
    }

    @Override
    public boolean rainingHere() {
        return level().isRainingAt(blockPosition());
    }

    @Override
    public void collectBlocks(BlockField field, double[] area, double x, double z) {
        AABB box = new AABB(area[0], area[1], area[2], area[3], area[4], area[5]);
        for (VoxelShape shape : level().getBlockCollisions(this, box)) {
            for (AABB b : shape.toAabbs()) {
                field.add(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
            }
        }
        addWorldBorder(field, box, x, z);
        // 範囲の外は、聞かれたときにワールドを引く（見えない壁の端の角を、壁に沿って探すため）
        outsideShapes.clear();
        field.setOutside(new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ},
                this::solidOutside);
    }

    @Override
    public CarHost.Move moveWithCars(double dx, double dy, double dz) {
        Vec3 velocity = new Vec3(dx, dy, dz);
        setDeltaMovement(velocity);
        HullMove hullMove = moveCars(velocity);
        return new CarHost.Move(hullMove.motion(), hullMove.car());
    }

    @Override
    public void setCollisions(boolean horizontal, boolean vertical) {
        horizontalCollision = horizontal;
        verticalCollision = vertical;
    }

    /** 同期項目を読む口。運転していない側の見た目と、運転を引き継ぐときに読む。 */
    private final class SyncedValues implements CarSync {
        @Override
        public float steerAngle() {
            return entityData.get(DATA_STEER_ANGLE);
        }

        @Override
        public float speed() {
            return entityData.get(DATA_SPEED);
        }

        @Override
        public float yaw() {
            return entityData.get(DATA_YAW);
        }

        @Override
        public float pitch() {
            return entityData.get(DATA_PITCH);
        }

        @Override
        public float roll() {
            return entityData.get(DATA_ROLL);
        }

        @Override
        public float rpm() {
            return entityData.get(DATA_RPM);
        }

        @Override
        public int gear() {
            return entityData.get(DATA_GEAR);
        }

        @Override
        public float throttle() {
            return entityData.get(DATA_THROTTLE);
        }

        @Override
        public float slipAngle() {
            return entityData.get(DATA_SLIP_ANGLE);
        }

        @Override
        public int surface() {
            return entityData.get(DATA_SURFACE);
        }

        @Override
        public byte lights() {
            return entityData.get(DATA_LIGHTS);
        }

        @Override
        public float boost() {
            return entityData.get(DATA_BOOST);
        }

        @Override
        public float suspension(int wheel) {
            return entityData.get(DATA_SUSPENSION.get(wheel));
        }

        @Override
        public float wheelSpeed(int wheel) {
            return entityData.get(DATA_WHEEL_SPEED.get(wheel));
        }

        @Override
        public float friction(int wheel) {
            return entityData.get(DATA_WHEEL_FRICTION.get(wheel));
        }
    }
}
