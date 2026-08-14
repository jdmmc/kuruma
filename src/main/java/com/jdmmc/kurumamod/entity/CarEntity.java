package com.jdmmc.kurumamod.entity;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.CarRules;
import com.jdmmc.kurumamod.Config;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarImpact;
import com.jdmmc.kurumamod.client.CarSeat;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.network.CarImpactPacket;
import com.jdmmc.kurumamod.network.CarPushPacket;
import com.jdmmc.kurumamod.network.CarSpecPacket;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.physics.CarCollision;
import com.jdmmc.kurumamod.physics.CarInput;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.GroundContact;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.surface.SurfaceLookup;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

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
 * <p>この分岐は {@link #isControlledByLocalInstance()} 1 つで表せる
 * （運転クライアント、または無人時のサーバーで true になる）。</p>
 *
 * <p><b>エンティティの Y はシャシー基準面（サスペンションのハードポイントが並ぶ面）を指す。</b>
 * 地面ではない。タイヤは AABB の下にぶら下がっているので、AABB は車体だけを表す。</p>
 */
public class CarEntity extends Entity implements IEntityAdditionalSpawnData {

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
    /** いちばんグリップの低い輪の路面。ロードノイズ。 */
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

    /** ヘッドライト点灯。 */
    public static final int LIGHT_HEAD = 1;
    /** ブレーキランプ点灯。 */
    public static final int LIGHT_BRAKE = 2;
    /** これ以上踏んでいればブレーキランプを点ける。 */
    private static final double BRAKE_LIGHT_THRESHOLD = 0.05;

    /** 1 ティック = 50ms は粗いので、物理はこの回数に分割して解く */
    private static final int PHYSICS_SUBSTEPS = 4;
    /** 1 ティックの長さ [s] */
    private static final double TICK_SECONDS = 0.05;
    /** 見た目のタイヤ角を進行方向で測るのに要る最低速度 [m/s]。これ以下では進行方向が定まらない。 */
    private static final double VISUAL_SLIP_MIN_SPEED = 1.0;
    /** 接地レイの余長 [m]。伸びきり位置ちょうどで取りこぼさないための余裕。 */
    private static final double GROUND_RAY_MARGIN = 0.1;
    /**
     * 接地面が 1 ティックで上がってよい高さ [m]。＝段差を乗り越える速さ（3m/s）。
     *
     * <p>段差の天面をいきなり接地面として渡すと、1m ぶんの食い込みがそのまま
     * バンプストップに入り、<b>8 倍の荷重で車が跳ね上がる</b>。実測（{@code WallTest}）では
     * 1 ブロックの段差で上昇 8.7m/s・100 ティック中 75 ティック宙に浮き、ピッチが
     * 2779 度まで回った——<b>段差に当たっただけで車がとんぼ返りする</b>。少しずつ渡せば
     * サスが縮みながら車体を持ち上げるので、跳ねずに乗り越える（0.15m/tick で
     * 上昇 2.57m/s・浮きなし・5 ティックで段の上に落ち着く）。</p>
     *
     * <p>大きすぎても跳ねる。0.2m/tick ではストロークを食いきって行き過ぎ、
     * 落ち着くまでに 29 ティック掛かった。</p>
     */
    private static final double GROUND_RISE_PER_TICK = 0.15;
    /** 雨に入ってから濡れきるまでの時定数 [s]。 */
    private static final double WET_SECONDS = 3.0;
    /** 雨から出てから乾くまでの時定数 [s]。濡れるより遅い。 */
    private static final double DRY_SECONDS = 20.0;
    /**
     * これ以下の速度差では衝突として扱わない [m/s]。
     *
     * <p>縁石を擦った程度で毎回鳴ると安っぽくなる。クライアント側の
     * {@code CarImpact.MIN_IMPACT_SPEED} と同じ値だが、<b>ここで弾いておかないと
     * 毎ティック無駄なパケットが飛ぶ</b>ので両方に置いてある。</p>
     */
    private static final double IMPACT_MIN_SPEED = 2.5;
    /**
     * 壁にぶつかったときに失う速度の割合。
     *
     * <p>正面から当たったときは<b>実際に動けた量が 0 なのでこれが無くても止まる</b>。
     * 効くのは<b>掠めたとき</b>で、壁と平行な成分がまるごと残るため、削らないと
     * 擦りながら同じ速度で走り抜けられてしまう。{@link #IMPACT_MIN_SPEED} を
     * 超えたときだけ削るので、縁石を撫でた程度では落ちない（音と揺れが出る場面と
     * 一致するので、<b>速度が落ちた理由が必ず見て分かる</b>）。</p>
     */
    private static final double IMPACT_SPEED_LOSS = 0.50;
    /** ぶつかった相手の車を探すときに当たり判定の箱を膨らませる量 [m]。 */
    private static final double CAR_CONTACT_MARGIN = 0.15;
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
    /**
     * 車種。<b>諸元とは別に持つ。</b>
     *
     * <p>諸元は調整画面でいくらでも変わるので、「もとはどの車か」は諸元からは分からない。
     * 見た目（{@code assets/<ns>/vehicles/<path>.json}）を引くのも、名前を出すのも、
     * 調整画面の「戻す」でどこへ戻すかも、すべてこの id が決める。</p>
     */
    private ResourceLocation carId = CarTypes.DEFAULT_ID;
    /** 車両諸元。調整画面から差し替えられる。 */
    private CarSpec spec = CarSpec.DEFAULT;
    /** 物理の状態。運転権を持っている側だけが進める。 */
    private final CarState state = new CarState();
    /** 各輪の接地面の高さ。毎ティック使い回す。 */
    private final GroundContact groundContact = new GroundContact();
    /**
     * 前ティックで各輪へ渡した接地面の高さ [m]。段差の立ち上がりを制限するのと、
     * 壁に食い込んだ輪を据え置くのに使う。
     */
    private final double[] previousGroundHeight = newGroundHeights();

    private static double[] newGroundHeights() {
        double[] heights = new double[Wheel.COUNT];
        Arrays.fill(heights, Double.NEGATIVE_INFINITY);
        return heights;
    }

    /** 運転クライアントから毎ティック差し込まれる入力。サーバーや同乗者側では常に無操作。 */
    private CarInput driverInput = CarInput.NONE;
    /** 前ティックで自分が運転権を持っていたか。持ち替わった瞬間に物理の状態を作り直す。 */
    private boolean hadControl;
    /** 誰も乗っていないまま経過したティック数。サーバー側だけで数える。 */
    private int abandonedTicks;

    /** 各輪が乗っている路面。物理には倍率として渡すので、こちらは表示用。 */
    private final RoadSurface[] surfaces = new RoadSurface[Wheel.COUNT];
    /** 路面の濡れ具合 0..1。天候から作るので同期も保存もしない。 */
    private double wetness;

    /** 描画用のタイヤ回転角 [rad]。車輪ごとに持つので、空転やロックがそのまま見える。 */
    private final float[] wheelRoll = new float[Wheel.COUNT];
    private final float[] wheelRollPrev = new float[Wheel.COUNT];

    // 運転していないクライアントが、サーバーから来た位置へ寄せていくための状態
    /**
     * 描画に使う姿勢の、今ティックと前ティックの値。
     *
     * <p><b>位置は補間されるのに、姿勢や切れ角は補間されていなかった。</b>同期値は 20Hz で
     * 階段状に変わるので、位置だけ滑らかに動いて車体の傾きだけがカクつく。物理を手元で
     * 解いている側も 20Hz なので事情は同じ。ティックの境目の値を 2 つ持って、描画時に
     * {@code partialTick} で間を取る。</p>
     */
    private float renderYaw;
    private float renderPitch;
    private float renderRoll;
    private float renderSteer;
    private final double[] renderSuspension = new double[Wheel.COUNT];
    private float prevRenderYaw;
    private float prevRenderPitch;
    private float prevRenderRoll;
    private float prevRenderSteer;
    private final double[] prevRenderSuspension = new double[Wheel.COUNT];
    /** 最初のティックは前の値が無いので、両方を同じ値で埋める。 */
    private boolean renderSnapshotReady;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        Arrays.fill(surfaces, RoadSurface.PAVED);
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
        // 距離 → noCulling → 視錐台 の順に見る）。そちらは shouldRenderAtSqrDistance で広げてある
        this.noCulling = true;
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
        return super.shouldRenderAtSqrDistance(
                distanceSq / (RENDER_DISTANCE_SCALE * RENDER_DISTANCE_SCALE));
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_STEER_ANGLE, 0.0F);
        entityData.define(DATA_SPEED, 0.0F);
        entityData.define(DATA_PITCH, 0.0F);
        entityData.define(DATA_ROLL, 0.0F);
        for (int i = 0; i < DATA_SUSPENSION.size(); i++) {
            entityData.define(DATA_SUSPENSION.get(i),
                    (float) CarSpec.DEFAULT.staticSuspensionLength(Wheel.VALUES[i]));
            entityData.define(DATA_WHEEL_SPEED.get(i), 0.0F);
            entityData.define(DATA_WHEEL_FRICTION.get(i), 0.0F);
        }
        entityData.define(DATA_RPM, (float) CarSpec.DEFAULT.idleRpm());
        entityData.define(DATA_GEAR, 0);
        entityData.define(DATA_THROTTLE, 0.0F);
        entityData.define(DATA_SLIP_ANGLE, 0.0F);
        entityData.define(DATA_SURFACE, RoadSurface.PAVED.ordinal());
        entityData.define(DATA_LIGHTS, (byte) 0);
        entityData.define(DATA_YAW, 0.0F);
    }

    /** 停車時のシャシー基準面の地上高 [m]。スポーンさせるときにこの高さぶん持ち上げる。 */
    public double getStaticRideHeight() {
        return spec.staticRideHeight();
    }

    public CarSpec getSpec() {
        return spec;
    }

    /**
     * 諸元を差し替える。調整画面から呼ばれ、次のティックからその値で物理が解かれる。
     *
     * <p>諸元は寸法（＝見た目）にも効くので、サーバー側で変わったときは周囲のクライアントへ配る。
     * 配らないと他人の車だけ既定の寸法で描かれる。</p>
     */
    /** 車種の id。見た目の定義を引くのと、名前を出すのに使う。 */
    public ResourceLocation getCarId() {
        return carId;
    }

    /**
     * 車種を決める。<b>アイテムから出すときに 1 度だけ呼ぶ。</b>
     *
     * <p>id と諸元をまとめて入れる。別々に入れられるようにすると、
     * 「id は AE86 なのに諸元は既定の車」という組み合わせを作れてしまう。</p>
     */
    public void setCarType(CarType type) {
        this.carId = type.id();
        setSpec(type.spec());
    }

    public void setSpec(CarSpec spec) {
        this.spec = spec;
        if (!level().isClientSide) {
            KurumaNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> this),
                    new CarSpecPacket(getId(), spec));
        }
    }

    // ------------------------------------------------------------------
    // ティック処理
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        System.arraycopy(wheelRoll, 0, wheelRollPrev, 0, wheelRoll.length);
        super.tick();

        if (level().isClientSide) {
            tickLerp();
        }

        if (isControlledByLocalInstance()) {
            if (!hadControl) {
                takeOverPhysics();
                hadControl = true;
            }
            physicsTick(driverInput);
        } else {
            hadControl = false;
        }

        updateWheelRoll();

        if (level().isClientSide && isControlledByLocalInstance()) {
            KurumaNetwork.CHANNEL.sendToServer(new CarStatePacket(getId(),
                    (float) state.steerAngle, (float) state.forwardSpeed,
                    getYRot(), (float) state.pitch, (float) state.roll,
                    suspensionSnapshot(), wheelSpeedSnapshot(), frictionSnapshot(),
                    (float) state.engineRpm, state.gear, (float) state.throttle,
                    (float) (state.slipAngle() * Mth.RAD_TO_DEG), worstSurface().ordinal(),
                    driverLights()));
        }

        if (!level().isClientSide) {
            tickAbandoned();
        }

        updateRenderSnapshot();
    }

    /**
     * 描画用の値をティックの境目で 1 つずらす。
     *
     * <p>手元で解いていてもいなくても同じように補間できるよう、<b>読み出し元の違いは
     * ここで吸収する</b>。</p>
     */
    private void updateRenderSnapshot() {
        boolean local = hasLocalState();
        prevRenderYaw = renderYaw;
        prevRenderPitch = renderPitch;
        prevRenderRoll = renderRoll;
        prevRenderSteer = renderSteer;
        System.arraycopy(renderSuspension, 0, prevRenderSuspension, 0, Wheel.COUNT);

        renderYaw = local ? getYRot() : entityData.get(DATA_YAW);
        renderPitch = local ? (float) state.pitch : entityData.get(DATA_PITCH);
        renderRoll = local ? (float) state.roll : entityData.get(DATA_ROLL);
        renderSteer = local ? (float) state.steerAngle : entityData.get(DATA_STEER_ANGLE);
        for (int i = 0; i < Wheel.COUNT; i++) {
            renderSuspension[i] = local
                    ? state.suspensionLength[i] : entityData.get(DATA_SUSPENSION.get(i));
        }

        if (!renderSnapshotReady) {
            renderSnapshotReady = true;
            prevRenderYaw = renderYaw;
            prevRenderPitch = renderPitch;
            prevRenderRoll = renderRoll;
            prevRenderSteer = renderSteer;
            System.arraycopy(renderSuspension, 0, prevRenderSuspension, 0, Wheel.COUNT);
        }
    }

    /** 送信用に各輪のサスペンション長を float でまとめる。 */
    private float[] suspensionSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.suspensionLength[i];
        }
        return values;
    }

    /** 送信用に各輪の摩擦の仕事率を float でまとめる。 */
    private float[] frictionSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.wheelFrictionPower[i];
        }
        return values;
    }

    /** 送信用に各輪の回転角速度を float でまとめる。 */
    private float[] wheelSpeedSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.wheelAngularVelocity[i];
        }
        return values;
    }

    /**
     * 放置された車を消す。
     *
     * <p>マルチプレイでは乗り捨てが溜まっていく一方で、無人の車もサーバーが毎ティック
     * 物理を解いている。設定した時間だけ誰も乗らなければ消す（0 で無効）。</p>
     */
    private void tickAbandoned() {
        int lifetime = Config.abandonedCarLifetimeSeconds;
        if (lifetime <= 0) {
            abandonedTicks = 0;
            return;
        }
        if (!getPassengers().isEmpty()) {
            abandonedTicks = 0;
            return;
        }
        if (++abandonedTicks > lifetime * 20) {
            discard();
        }
    }

    /**
     * 運転権を得た（あるいはサーバーが無人の車を引き継いだ）ときに、
     * 現在のエンティティの状態から物理の状態を作り直す。
     */
    private void takeOverPhysics() {
        state.yaw = getYRot() * Mth.DEG_TO_RAD;
        state.forwardSpeed = entityData.get(DATA_SPEED);
        state.lateralSpeed = 0.0;
        state.yawRate = 0.0;
        state.height = getY();
        state.verticalSpeed = 0.0;
        state.pitch = entityData.get(DATA_PITCH);
        state.roll = entityData.get(DATA_ROLL);
        state.pitchRate = 0.0;
        state.rollRate = 0.0;
        // 車輪の回転を車速に合わせておく。0 のままだと乗った瞬間に
        // 「全輪ロック」と判定されて急制動がかかる
        double angularVelocity = state.forwardSpeed / spec.wheelRadius();
        Arrays.fill(state.wheelAngularVelocity, angularVelocity);
        Arrays.fill(state.wheelSlipRatio, 0.0);
        // 段差の立ち上がり制限は前ティックの値を見るので、引き継いだ時点では捨てる
        Arrays.fill(previousGroundHeight, Double.NEGATIVE_INFINITY);
    }

    /** 物理を解いて実際に動かす。運転クライアント、または無人時のサーバーでのみ呼ばれる。 */
    private void physicsTick(CarInput input) {
        // 接地の探索は 1 ティックに 1 回だけ。サブステップの間は同じ地形を見続けるので、
        // 高速で走るほど地形のサンプリングが粗くなる（平地では問題にならない）
        updateWetness();
        updateGroundContact();

        double dt = TICK_SECONDS / PHYSICS_SUBSTEPS;
        for (int i = 0; i < PHYSICS_SUBSTEPS; i++) {
            CarPhysics.step(spec, state, groundContact, dt, input);
        }

        setYRot((float) (state.yaw * Mth.RAD_TO_DEG));
        setRot(getYRot(), getXRot());

        // 水平方向は物理から、上下はサスペンションが決めた高さへの差分として渡す。
        // move() に通すのは、壁や天井との衝突をバニラに任せるため
        Vec3 velocity = new Vec3(
                state.velocityX() * TICK_SECONDS,
                state.height - getY(),
                state.velocityZ() * TICK_SECONDS);
        setDeltaMovement(velocity);
        double beforeX = getX();
        double beforeZ = getZ();
        move(MoverType.SELF, velocity);

        double impact = 0.0;
        if (horizontalCollision) {
            // 実際に動けたぶんから速度を取り直す。前後だけ 0 にすると、横滑り中に
            // 壁を擦ったときに横速度だけが残って挙動がおかしくなる
            double movedForward = ((getX() - beforeX) * state.forwardX()
                    + (getZ() - beforeZ) * state.forwardZ()) / TICK_SECONDS;
            double movedRight = ((getX() - beforeX) * state.rightX()
                    + (getZ() - beforeZ) * state.rightZ()) / TICK_SECONDS;
            // 衝撃の大きさは「出そうとした速度」と「実際に出た速度」の差。
            // 速度を書き換える前に取ること
            impact = Math.hypot(movedForward - state.forwardSpeed, movedRight - state.lateralSpeed);

            CarEntity hit = findHitCar();
            if (hit != null) {
                // 相手が車なら運動量のやりとりで決める（壁と違って相手も動く）
                resolveCarImpact(hit);
            } else {
                state.forwardSpeed = movedForward;
                state.lateralSpeed = movedRight;
                // ぶつかったと分かる強さなら、壁に沿って残った勢いも削る。
                // 正面から当たれば動けた量が 0 なので上の行だけで止まるが、掠めただけだと
                // 壁と平行な成分がまるごと残り、擦りながら同じ速度で走り抜けられてしまう
                if (impact >= IMPACT_MIN_SPEED) {
                    state.forwardSpeed *= 1.0 - IMPACT_SPEED_LOSS;
                    state.lateralSpeed *= 1.0 - IMPACT_SPEED_LOSS;
                }
            }
        }
        if (verticalCollision) {
            // 落ちてシャシーを打ちつけた。サスが受け止めきれなかったぶんが衝撃になる
            impact = Math.max(impact, Math.abs(state.verticalSpeed));
            state.verticalSpeed = 0.0;
        }
        reportImpact(impact);
        // 実際に動けた位置を正とする（衝突で押し戻されたぶんを取り込む）
        state.height = getY();

        if (!level().isClientSide) {
            // 無人の車をサーバーが動かしている場合。運転者から届く値の代わりに自分の結果を配る
            applyDriverState((float) state.steerAngle, (float) state.forwardSpeed,
                    getYRot(), (float) state.pitch, (float) state.roll,
                    suspensionSnapshot(), wheelSpeedSnapshot(), frictionSnapshot(),
                    (float) state.engineRpm, state.gear, (float) state.throttle,
                    (float) (state.slipAngle() * Mth.RAD_TO_DEG), worstSurface().ordinal(),
                    driverLights());
        }
    }

    /**
     * ぶつかった相手の車を探す。壁や地形なら null。
     *
     * <p>{@code move()} は「何に当たったか」を教えてくれないので、当たり判定の箱を少し
     * 膨らませて重なっている車を拾う。<b>いちばん深く重なっているものを選ぶ</b>——3 台が
     * 絡んだときに、掠めただけの相手を主たる衝突相手と取り違えないため。</p>
     */
    private CarEntity findHitCar() {
        if (!CarRules.carCollision()) {
            return null;
        }
        AABB box = getBoundingBox().inflate(CAR_CONTACT_MARGIN);
        CarEntity best = null;
        double deepest = 0.0;
        for (CarEntity other : level().getEntitiesOfClass(CarEntity.class, box,
                car -> car != this && !car.isRemoved())) {
            AABB overlap = box.intersect(other.getBoundingBox());
            double depth = Math.min(overlap.getXsize(), overlap.getZsize());
            if (best == null || depth > deepest) {
                best = other;
                deepest = depth;
            }
        }
        return best;
    }

    /**
     * 車同士の衝突を運動量で解く。
     *
     * <p><b>壁と違って相手も動く。</b>反発係数つきの弾性衝突として、両者の速度差から力積を
     * 求め、質量の比で分け合う（重い車がぶつかれば軽い車がよく飛ぶ）。自分のぶんはその場で
     * 足し、<b>相手のぶんは {@link CarPushPacket} で相手の持ち主へ届ける</b>——物理を解いて
     * いるのはそれぞれの運転クライアントなので、ここで相手を動かしても次のティックに
     * 上書きされてしまう。</p>
     *
     * <p>当たった向きは<b>重なりの浅い軸</b>で決める。箱は回らないので世界軸のどちらかになるが、
     * {@code move()} が実際に止めた軸と一致する。接触点が相手の重心から離れていれば
     * その腕の長さぶんヨーのモーメントになるので、<b>側面に当てれば相手は回る</b>。</p>
     *
     * <p>速度は「出そうとした速度」から解く。{@code move()} が既に壁と同じように
     * 止めてしまっているが、車同士では止まるのが正しいとは限らない（弾かれる）。</p>
     */
    private void resolveCarImpact(CarEntity other) {
        AABB a = getBoundingBox();
        AABB b = other.getBoundingBox();
        // 重なりの浅い軸が衝突の向き。深く食い込んでいる軸ではない
        double overlapX = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        double overlapZ = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        double nx;
        double nz;
        if (overlapX <= overlapZ) {
            nx = Math.signum(other.getX() - getX());
            nz = 0.0;
        } else {
            nx = 0.0;
            nz = Math.signum(other.getZ() - getZ());
        }
        if (nx == 0.0 && nz == 0.0) {
            return;
        }

        // 接触点は重なっている範囲の中心。相手の重心から外れていればヨーも動く
        double contactX = (Math.max(a.minX, b.minX) + Math.min(a.maxX, b.maxX)) / 2.0;
        double contactZ = (Math.max(a.minZ, b.minZ) + Math.min(a.maxZ, b.maxZ)) / 2.0;

        // state はまだ「出そうとした速度」のまま（車が相手のときは move() の結果で
        // 上書きしていない）。壁と違って、止まるのが正しいとは限らないため
        CarCollision.Result result = CarCollision.solve(nx, nz,
                contactX - other.getX(), contactZ - other.getZ(),
                state.velocityX(), state.velocityZ(),
                other.worldVelocityX(), other.worldVelocityZ(),
                spec.mass(), other.getSpec().mass(), other.getSpec().yawInertia());
        if (result == null) {
            return;
        }

        state.addWorldVelocity(result.aDx(), result.aDz());
        KurumaNetwork.CHANNEL.sendToServer(new CarPushPacket(other.getId(),
                (float) result.bDx(), (float) result.bDz(), (float) result.bYawRate()));
    }

    /**
     * ぶつけられたぶんを自分の物理へ足す。
     *
     * <p><b>足せるのは物理を解いている側だけ。</b>運転していないクライアントで足しても、
     * 次に届く同期で上書きされるだけなので捨てる。</p>
     */
    public void applyPush(double dx, double dz, double dYawRate) {
        if (!isControlledByLocalInstance()) {
            return;
        }
        state.addWorldVelocity(dx, dz);
        state.yawRate += dYawRate;
        reportImpact(Math.hypot(dx, dz));
    }

    /** ワールド座標での速度の X 成分 [m/s]。手元で解いていない車は同期された値から組み立てる。 */
    public double worldVelocityX() {
        if (hasLocalState()) {
            return state.velocityX();
        }
        return remoteForward() * -Math.sin(remoteYaw()) + remoteLateral() * -Math.cos(remoteYaw());
    }

    /** ワールド座標での速度の Z 成分 [m/s]。 */
    public double worldVelocityZ() {
        if (hasLocalState()) {
            return state.velocityZ();
        }
        return remoteForward() * Math.cos(remoteYaw()) + remoteLateral() * -Math.sin(remoteYaw());
    }

    private double remoteYaw() {
        return entityData.get(DATA_YAW) * Mth.DEG_TO_RAD;
    }

    private double remoteForward() {
        return entityData.get(DATA_SPEED);
    }

    /**
     * 同期された値から組み立てた横速度 [m/s]。
     *
     * <p>横速度そのものは配っていないが、ドリフト角
     * （{@code atan2(横速度, |前後速度|)}）は配ってあるので、そこから戻せる。</p>
     */
    private double remoteLateral() {
        return Math.tan(entityData.get(DATA_SLIP_ANGLE) * Mth.DEG_TO_RAD)
                * Math.abs(entityData.get(DATA_SPEED));
    }

    /**
     * ぶつかったことを音と揺れに変える。
     *
     * <p><b>気づけるのは物理を解いている側だけ</b>なので、運転クライアント（または無人の車を持つ
     * サーバー）がここで拾い、{@link CarImpactPacket} で周りへ配る。運転者は自分の画面へ
     * 先に出しておく——往復を待つと、ぶつかった手応えが 1 往復ぶん遅れて届くことになる。</p>
     */
    private void reportImpact(double impact) {
        if (impact < IMPACT_MIN_SPEED) {
            return;
        }
        if (level().isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CarImpact.onImpact(this, impact));
            KurumaNetwork.CHANNEL.sendToServer(new CarImpactPacket(getId(), (float) impact));
        } else {
            CarImpactPacket.broadcast(this, (float) impact);
        }
    }

    /**
     * 各輪の真下へレイを飛ばして、接地面の高さを調べる。
     *
     * <p>ここが物理コアと Minecraft の境目。物理コアは {@link GroundContact} を読むだけで、
     * 地形の調べ方を知らない。</p>
     *
     * <p><b>レイはハードポイントからではなく、タイヤ下端 + 登坂限界から飛ばす。</b>
     * 車の当たり判定の箱は車体ぶんしかない（1.8 × 0.7 × 1.8）のに対し、タイヤは
     * 前後へ 0.5m 以上はみ出している。そのため<b>箱が壁に当たるより先にタイヤの
     * サンプル点が壁の中へ入る</b>。ハードポイントから飛ばすとレイの起点自体が
     * 壁の中に入り、{@code VoxelShape#clip} は<b>起点そのものを当たり位置として返す</b>
     * ので、「シャシーの高さに地面がある」＝サスが 1m 近く食い込んだ、と解釈されて
     * <b>8 倍の荷重でその輪が跳ね上がる</b>。斜めに突っ込むと片側だけが跳ね上がるので、
     * 車が壁を登りながら回転していく。</p>
     *
     * <p>起点を登坂限界に置けば、そこまで塞がっているかどうかで<b>乗り越えられる段差と
     * 壁を見分けられる</b>。塞がっていれば（＝起点が block の中）壁なので、その輪の
     * 接地面は<b>据え置く</b>——タイヤは路面に接したまま壁に当たっているのだから、
     * 高さは変わらないのが正しい。前へ進めないのはバニラの箱の衝突が止めてくれる。</p>
     */
    private void updateGroundContact() {
        double forwardX = state.forwardX();
        double forwardZ = state.forwardZ();
        double rightX = state.rightX();
        double rightZ = state.rightZ();
        double rayLength = spec.suspensionMaxLength() + spec.wheelRadius() + GROUND_RAY_MARGIN;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);

            double x = getX() + forwardX * forwardOffset + rightX * rightOffset;
            double z = getZ() + forwardZ * forwardOffset + rightZ * rightOffset;
            double top = state.hardpointHeight(forwardOffset, rightOffset);
            double bottom = top - rayLength;
            // 今のタイヤ下端。ここから登坂限界ぶん上までが「乗り越えられる」範囲
            double wheelBottom = top - state.suspensionLength[index] - spec.wheelRadius();
            double from = Math.max(wheelBottom + spec.maxClimbStep(), bottom + GROUND_RAY_MARGIN);

            BlockHitResult hit = level().clip(new ClipContext(
                    new Vec3(x, from, z),
                    new Vec3(x, bottom, z),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    this));

            if (hit.getType() == HitResult.Type.MISS) {
                setGroundHeight(wheel, Double.NEGATIVE_INFINITY);
                continue;
            }
            if (hit.isInside()) {
                // 起点が塞がっている＝登坂限界より上まで続く壁。乗せずに据え置く
                groundContact.set(wheel, previousGroundHeight[index],
                        surfaces[index].gripScale(wetness), surfaces[index].rollingScale());
                continue;
            }

            // 当たったブロックが路面。知らないブロックは舗装路に落ちるので、
            // 他 MOD の道路ブロックはそのままアスファルトとして走れる
            RoadSurface surface = SurfaceLookup.of(level().getBlockState(hit.getBlockPos()));
            surfaces[index] = surface;
            setGroundHeight(wheel, hit.getLocation().y,
                    surface.gripScale(wetness), surface.rollingScale());
        }
    }

    private void setGroundHeight(Wheel wheel, double height) {
        setGroundHeight(wheel, height, 1.0, 1.0);
    }

    /**
     * 接地面の高さを物理コアへ渡す。<b>上がる向きだけ 1 ティックあたりの量を制限する</b>。
     *
     * <p>段差の天面をいきなり渡すと、その食い込みがまるごとバンプストップに入って
     * 車が跳ね上がる。少しずつ上げれば、サスが縮みながら車体を持ち上げていく。
     * 下がる向き（崖から落ちる、着地する）は制限しない——遅らせると宙に浮く。</p>
     */
    private void setGroundHeight(Wheel wheel, double height, double gripScale, double rollingScale) {
        int index = wheel.ordinal();
        double previous = previousGroundHeight[index];
        double limited = height;
        if (previous > Double.NEGATIVE_INFINITY) {
            limited = Math.min(height, previous + GROUND_RISE_PER_TICK);
        }
        previousGroundHeight[index] = limited;
        groundContact.set(wheel, limited, gripScale, rollingScale);
    }

    /**
     * 路面がどれだけ濡れているかを進める。
     *
     * <p>{@link net.minecraft.world.level.Level#isRainingAt} は「その地点に実際に雨が
     * 当たっているか」を返す。空が見えるかとバイオームの降水種別まで見てくれるので、
     * <b>トンネルや橋の下は自動で乾いたまま</b>になり、寒冷地では雨ではなく雪が降る。</p>
     *
     * <p><b>切り替えは瞬時にしない。</b>トンネルの出入口でグリップが 1 ティックで 0.7 倍に
     * 飛ぶと、旋回中なら横 G が跳ねる。濡れるのは速く、乾くのは遅い時定数で追従させる。</p>
     */
    private void updateWetness() {
        boolean raining = level().isRainingAt(blockPosition());
        double seconds = raining ? WET_SECONDS : DRY_SECONDS;
        double rate = TICK_SECONDS / seconds;
        double target = raining ? 1.0 : 0.0;
        wetness += (target - wetness) * Math.min(1.0, rate);
    }

    /** その輪が今どの路面に乗っているか。手元で解いているときだけ意味がある。 */
    public RoadSurface getRenderSurface(Wheel wheel) {
        return surfaces[wheel.ordinal()];
    }

    /** 4 輪のうちいちばんグリップの低い路面。 */
    private RoadSurface worstSurface() {
        RoadSurface worst = surfaces[0];
        for (RoadSurface surface : surfaces) {
            if (surface.gripScale() < worst.gripScale()) {
                worst = surface;
            }
        }
        return worst;
    }

    /**
     * 表示と音に使う路面。いちばんグリップの低い輪のもの。
     *
     * <p>手元で解いていないときは同期された値。配らないと他人の車のロードノイズが
     * どこでも舗装路の音になる。</p>
     */
    public RoadSurface getRenderSurface() {
        return hasLocalState() ? worstSurface() : RoadSurface.byIndex(entityData.get(DATA_SURFACE));
    }

    /** 路面の濡れ具合。0 で乾いている、1 で濡れきっている。 */
    public double getRenderWetness() {
        return wetness;
    }

    /** サーバーから届いた位置へ数ティックかけて寄せる。運転していないクライアント用。 */
    private void tickLerp() {
        if (isControlledByLocalInstance()) {
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

    /**
     * タイヤの転がり角を進める。
     *
     * <p>手元で物理を解いているなら車輪の実際の角速度を使う。空転すれば路面より速く、
     * ロックすれば止まって見える。そうでなければ車速から均等に回す。</p>
     */
    private void updateWheelRoll() {
        boolean local = isControlledByLocalInstance();
        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            // 手元で解いていないときは同期された角速度を使う。
            // 車速から作ると空転もロックも見えなくなる
            double angularVelocity = local
                    ? state.wheelAngularVelocity[index]
                    : entityData.get(DATA_WHEEL_SPEED.get(index));
            wheelRoll[index] += (float) (angularVelocity * TICK_SECONDS);
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = Math.max(steps, 1);
    }

    // ------------------------------------------------------------------
    // 外部から状態を差し込む口
    // ------------------------------------------------------------------

    /** 運転クライアントが毎ティック呼ぶ。次の {@link #tick()} で使われる。 */
    public void setDriverInput(CarInput input) {
        this.driverInput = input;
    }

    /** 運転者が入れているヘッドライトの状態。物理を解いている側だけが持つ。 */
    private boolean headlightsOn;

    /** ヘッドライトの切替。運転クライアントから呼ぶ。 */
    public void setHeadlights(boolean on) {
        headlightsOn = on;
    }

    /** 運転クライアント（または無人時のサーバー）が配る灯火の状態。 */
    private byte driverLights() {
        int lights = headlightsOn ? LIGHT_HEAD : 0;
        if (state.brake > BRAKE_LIGHT_THRESHOLD) {
            lights |= LIGHT_BRAKE;
        }
        return (byte) lights;
    }

    /** 描画に使う灯火の状態。手元で解いていないときは同期された値。 */
    public byte getRenderLights() {
        return hasLocalState() ? driverLights() : entityData.get(DATA_LIGHTS);
    }

    /** ヘッドライトが点いているか（描画用）。 */
    public boolean isHeadlightOn() {
        return (getRenderLights() & LIGHT_HEAD) != 0;
    }

    /** ブレーキランプが点いているか（描画用）。 */
    public boolean isBrakeLightOn() {
        return (getRenderLights() & LIGHT_BRAKE) != 0;
    }

    /** バックランプが点いているか（描画用）。段から分かるので同期は要らない。 */
    public boolean isReverseLightOn() {
        return getRenderGear() < 0;
    }

    /** サーバーが {@link CarStatePacket} から受け取った値を反映する。 */
    public void applyDriverState(float steerAngle, float speed, float yaw, float pitch, float roll,
                                float[] suspension, float[] wheelSpeed, float[] friction,
                                float rpm, int gear, float throttle, float slipAngle, int surface,
                                byte lights) {
        entityData.set(DATA_STEER_ANGLE, steerAngle);
        entityData.set(DATA_SPEED, speed);
        entityData.set(DATA_YAW, yaw);
        entityData.set(DATA_PITCH, pitch);
        entityData.set(DATA_ROLL, roll);
        for (int i = 0; i < DATA_SUSPENSION.size() && i < suspension.length; i++) {
            entityData.set(DATA_SUSPENSION.get(i), suspension[i]);
            entityData.set(DATA_WHEEL_SPEED.get(i), wheelSpeed[i]);
            entityData.set(DATA_WHEEL_FRICTION.get(i), friction[i]);
        }
        entityData.set(DATA_RPM, rpm);
        entityData.set(DATA_GEAR, gear);
        entityData.set(DATA_THROTTLE, throttle);
        entityData.set(DATA_SLIP_ANGLE, slipAngle);
        entityData.set(DATA_SURFACE, surface);
        entityData.set(DATA_LIGHTS, lights);
    }

    // ------------------------------------------------------------------
    // 描画向けの取得
    // ------------------------------------------------------------------

    /** 自分の手元で物理を解いているか（＝同期を待たずに自前の値を描画に使えるか） */
    private boolean hasLocalState() {
        return level().isClientSide && isControlledByLocalInstance();
    }

    /** 描画に使う切れ角 [rad]。運転中は往復のラグを避けて手元の値を使う。 */
    public float getRenderSteerAngle() {
        return hasLocalState() ? (float) state.steerAngle : entityData.get(DATA_STEER_ANGLE);
    }

    /**
     * <b>描画専用</b>の前輪の切れ角 [rad]。
     *
     * <p>大きく流すと自動カウンターがフルロックまで当たる。そこから逆へ切ると、車体に対する
     * 角度は最大切れ角の範囲内（35 度）で正しいのに、<b>進行方向に対しては垂直を超える</b>
     * （車体スリップ角 -72 度＋切れ角 +35 度 = 進行方向から 107 度）。タイヤが横を向いて
     * 見えるのはこれが理由。</p>
     *
     * <p>そこで<b>進行方向に対する角度</b>で頭打ちにする。上限以下はそのまま通すので、
     * 通常の走行（この角度は数度しかない）にはまったく影響しない。</p>
     *
     * <p><b>タイヤは「線」なので、角度は ±90 度に畳んでから測る。</b>畳まないと後退で壊れる。
     * 車体スリップ角は {@code atan2(横速度, |前後速度|)} と絶対値を取っており進行方向を
     * 表さないため、バック中に切れ角 35 度が逆側の -12.6 度として表示されてしまう。
     * 畳んでおけば、後退は「前進の裏返し」として自動的に正しく扱われる。</p>
     *
     * <p><b>物理は実際の切れ角（{@link #getRenderSteerAngle()}）で解かれており、
     * この値は一切影響しない。</b>同期して配るのも実際の切れ角の方。</p>
     */
    public float getVisualSteerAngle(float partialTick) {
        return visualSteerAngle(getRenderSteerAngle(partialTick));
    }

    public float getVisualSteerAngle() {
        return visualSteerAngle(getRenderSteerAngle());
    }

    private float visualSteerAngle(float steer) {
        double limit = spec.visualSlipLimit();
        // 止まりかけでは進行方向が定まらないので、そのまま出す
        if (limit <= 0.0 || Math.abs(getRenderSpeed()) < VISUAL_SLIP_MIN_SPEED) {
            return steer;
        }
        // 進行方向を車体基準で出す。後退では ±180 度の近くになる
        double slip = getRenderSlipAngleDegrees() * Mth.DEG_TO_RAD;
        double heading = getRenderSpeed() >= 0.0 ? slip : Math.PI - slip;
        double max = spec.maxSteerAngle();

        // タイヤは線なので、進行方向の線は 180 度ごとに現れる。そのどれへ寄せるのが
        // 見た目の動きが小さいかを選ぶ。後退ではここで自動的に裏側の線が選ばれ、
        // 前進と同じ扱いになる。
        // 上限以下はそのまま通るので、通常の走行にはまったく影響しない
        double best = steer;
        double bestError = Double.MAX_VALUE;
        for (int k = -2; k <= 2; k++) {
            double axis = heading + k * Math.PI;
            double candidate = Mth.clamp(Mth.clamp(steer, axis - limit, axis + limit), -max, max);
            // 機械的な最大切れ角に阻まれて条件を満たせない側は捨てる
            if (Math.abs(foldToRightAngle(candidate - axis)) > limit + 1.0E-6) {
                continue;
            }
            double error = Math.abs(candidate - steer);
            if (error < bestError) {
                bestError = error;
                best = candidate;
            }
        }
        return (float) best;
    }

    /** 角度を ±90 度へ畳む。タイヤは線なので、180 度ずれた向きは同じものとして扱える。 */
    private static double foldToRightAngle(double angle) {
        double wrapped = Math.atan2(Math.sin(angle), Math.cos(angle));
        if (wrapped > Math.PI / 2.0) {
            return wrapped - Math.PI;
        }
        if (wrapped < -Math.PI / 2.0) {
            return wrapped + Math.PI;
        }
        return wrapped;
    }

    /** 描画に使う車速 [m/s]。 */
    public float getRenderSpeed() {
        return isControlledByLocalInstance() ? (float) state.forwardSpeed : entityData.get(DATA_SPEED);
    }

    /** 描画に使うピッチ角 [rad]。正で鼻上げ。 */
    public float getRenderPitch() {
        return hasLocalState() ? (float) state.pitch : entityData.get(DATA_PITCH);
    }

    /**
     * 描画に使うヨー角 [度]。ティックの間を補間する。
     *
     * <p><b>{@code EntityRenderer#render} に渡ってくる {@code entityYaw} を使ってはいけない。</b>
     * あちらはバニラが量子化した値から作られているので、旋回中にガタつく。
     * 折り返しがあるので補間は {@link Mth#rotLerp} で。</p>
     */
    public float getRenderYaw(float partialTick) {
        return Mth.rotLerp(partialTick, prevRenderYaw, renderYaw);
    }

    /** 描画に使うピッチ角 [rad]。ティックの間を補間する。 */
    public float getRenderPitch(float partialTick) {
        return lerpRadians(partialTick, prevRenderPitch, renderPitch);
    }

    /** 描画に使うロール角 [rad]。ティックの間を補間する。 */
    public float getRenderRoll(float partialTick) {
        return lerpRadians(partialTick, prevRenderRoll, renderRoll);
    }

    /**
     * 角度の補間。<b>近い方の回り方を選ぶ</b>。
     *
     * <p>ピッチとロールは ±180 度に巻き取ってあるので、横転して回っている車が
     * 折り返しをまたぐと、素の線形補間では<b>1 ティックで逆向きに 1 周する</b>。
     * ヨー角で {@link Mth#rotLerp} を使っているのと同じ話（あちらは度、こちらはラジアン）。</p>
     */
    private static float lerpRadians(float partialTick, float from, float to) {
        float difference = (to - from) % Mth.TWO_PI;
        if (difference >= Mth.PI) {
            difference -= Mth.TWO_PI;
        } else if (difference < -Mth.PI) {
            difference += Mth.TWO_PI;
        }
        return from + partialTick * difference;
    }

    /** 描画に使う切れ角 [rad]。ティックの間を補間する。 */
    public float getRenderSteerAngle(float partialTick) {
        return Mth.lerp(partialTick, prevRenderSteer, renderSteer);
    }

    /** 描画に使うサスペンション長 [m]。ティックの間を補間する。 */
    public double getRenderSuspensionLength(Wheel wheel, float partialTick) {
        int index = wheel.ordinal();
        return Mth.lerp(partialTick, prevRenderSuspension[index], renderSuspension[index]);
    }

    /** 描画に使うロール角 [rad]。正で右下がり。 */
    public float getRenderRoll() {
        return hasLocalState() ? (float) state.roll : entityData.get(DATA_ROLL);
    }

    /**
     * 描画に使うサスペンション長 [m]。
     *
     * <p>サスペンションの伸縮そのものは同期していない。遠くの車では見て分からない一方、
     * 4 輪ぶんを毎ティック送ると割に合わないため、手元で解いていないときは停車時の長さを返す。</p>
     */
    public double getRenderSuspensionLength(Wheel wheel) {
        return hasLocalState()
                ? state.suspensionLength[wheel.ordinal()]
                : entityData.get(DATA_SUSPENSION.get(wheel.ordinal()));
    }

    /** 補間込みのタイヤ回転角 [rad] */
    public float getWheelRoll(Wheel wheel, float partialTick) {
        int index = wheel.ordinal();
        return Mth.lerp(partialTick, wheelRollPrev[index], wheelRoll[index]);
    }

    /**
     * 描画と音に使う滑り率。
     *
     * <p>手元で解いていないときは、配ってある車輪の角速度と車速から組み立て直す。
     * 滑り率そのものを配る必要はない。</p>
     */
    public double getRenderSlipRatio(Wheel wheel) {
        if (hasLocalState()) {
            return state.wheelSlipRatio[wheel.ordinal()];
        }
        double rolling = entityData.get(DATA_SPEED);
        double reference = Math.max(Math.abs(rolling), spec.slipReferenceSpeed());
        double surfaceSpeed = entityData.get(DATA_WHEEL_SPEED.get(wheel.ordinal())) * spec.wheelRadius();
        return (surfaceSpeed - rolling) / reference;
    }

    /**
     * 煙に使う、その輪が接地面で捨てている摩擦の仕事率 [W]。
     *
     * <p>タイヤの力 × 滑り速度。<b>回っているだけでも、荷重が掛かっているだけでも
     * 大きくならない</b>ので、後輪駆動で後輪をブン回したときに前輪から煙が出ない。</p>
     */
    public double getRenderFrictionPower(Wheel wheel) {
        return hasLocalState()
                ? state.wheelFrictionPower[wheel.ordinal()]
                : entityData.get(DATA_WHEEL_FRICTION.get(wheel.ordinal()));
    }

    /** 音に使うアクセルの踏み込み量 0..1。 */
    public double getRenderThrottle() {
        return hasLocalState() ? state.throttle : entityData.get(DATA_THROTTLE);
    }

    /**
     * 表示に使うブレーキの踏み込み量 0..1。
     *
     * <p>アクセルと違って同期していないので、手元で解いていないときは<b>ブレーキランプから
     * 代用する</b>（踏んでいるか踏んでいないかだけ）。メーターの棒のためだけに 1 バイト
     * 増やす価値はない。</p>
     */
    public double getRenderBrake() {
        return hasLocalState() ? state.brake : (isBrakeLightOn() ? 1.0 : 0.0);
    }

    /** 表示に使う接地荷重 [N]。手元で解いていないときは停車時の値。 */
    public double getRenderWheelLoad(Wheel wheel) {
        return hasLocalState() ? state.wheelLoad[wheel.ordinal()] : spec.staticWheelLoad(wheel);
    }

    /** 表示に使う横加速度 [m/s^2]。 */
    public double getRenderLateralAcceleration() {
        return hasLocalState() ? state.lateralAcceleration : 0.0;
    }

    /** 表示に使うエンジン回転数 [rpm]。 */
    public double getRenderRpm() {
        return hasLocalState() ? state.engineRpm : entityData.get(DATA_RPM);
    }

    /** トラクションコントロールが介入しているか。 */
    public boolean isTractionControlActive() {
        return hasLocalState() && state.tractionControlThrottle < 0.99;
    }

    /** ABS が介入しているか。 */
    public boolean isAbsActive() {
        if (!hasLocalState()) {
            return false;
        }
        for (double release : state.brakeRelease) {
            if (release < 0.99) {
                return true;
            }
        }
        return false;
    }

    /** 表示に使う段。1 以上で前進、-1 で後退、0 でニュートラル。 */
    public int getRenderGear() {
        return hasLocalState() ? state.gear : entityData.get(DATA_GEAR);
    }

    /** 表示に使う車体スリップ角（ドリフト角） [度]。正なら右へ滑っている。 */
    public double getRenderSlipAngleDegrees() {
        return hasLocalState() ? state.slipAngle() * Mth.RAD_TO_DEG : entityData.get(DATA_SLIP_ANGLE);
    }

    // ------------------------------------------------------------------
    // 乗車まわり
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof LivingEntity living ? living : null;
    }

    /**
     * 運転席と助手席の 2 人まで。
     *
     * <p><b>先に乗った方が運転する。</b> 運転者かどうかの判定はすべて
     * {@link #getControllingPassenger()}（＝先頭の乗員）を通っているので、
     * 助手席の人は入力も送らないし、パケットも受け付けられないし、レースの計測にも乗らない。</p>
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) {
            return;
        }
        Vec3 seat = seatOffset(passenger).yRot(-getYRot() * Mth.DEG_TO_RAD);
        move.accept(passenger,
                getX() + seat.x,
                getY() + seat.y + passenger.getMyRidingOffset(),
                getZ() + seat.z);
    }

    /**
     * この乗員の席。先に乗った方が運転席、次が助手席。
     *
     * <p><b>クライアントは見た目の定義（{@code vehicles/car.json} の {@code seat}）から引く。</b>
     * 座面の高さは車体の形で決まるので車ごとに違い、物理は一切読まない値だから。
     * サーバーは {@code assets/} を読まないので下の既定値を使うが、<b>乗員の位置は
     * 各クライアントが自分で計算する</b>ので、サーバー側の値はどこにも見えない。</p>
     */
    private Vec3 seatOffset(Entity passenger) {
        boolean passengerSeat = getPassengers().indexOf(passenger) == 1;
        Vec3 seat = null;
        if (level().isClientSide) {
            seat = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                    () -> () -> CarSeat.seatOffset(carId, passengerSeat));
        }
        if (seat == null) {
            seat = passengerSeat ? PASSENGER_SEAT_OFFSET : DRIVER_SEAT_OFFSET;
        }
        // 席は車体の一部。車体メッシュの原点は前後輪の中点なので、席もそこを基準に書く。
        // エンティティの原点は重心なので、その差を足す（前 56% なら 0.187m 後ろ）。
        // これを忘れると、荷重配分のスライダーを動かしたとき車体だけが動いて席が取り残される
        return seat.add(0.0, 0.0, spec.axleMidpointOffset());
    }

    // ------------------------------------------------------------------
    // Entity のその他の振る舞い
    // ------------------------------------------------------------------

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
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
     * 当たったぶんは水平衝突として扱われるので、{@link #IMPACT_SPEED_LOSS} と衝突音・
     * 画面の揺れがそのまま乗る。</p>
     */
    @Override
    public boolean canCollideWith(Entity other) {
        if (!CarRules.carCollision() && other instanceof CarEntity) {
            return false;
        }
        return super.canCollideWith(other);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) {
            return true;
        }
        if (source.getEntity() instanceof Player player) {
            if (!player.getAbilities().instabuild) {
                spawnAtLocation(Kurumamod.CAR_ITEM.get());
            }
            discard();
            return true;
        }
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        // 拾ったアイテムからは同じ車が出る
        return CarSpawnItem.stackFor(carId);
    }

    @Override
    public Component getName() {
        return CarTypes.get(carId).displayName();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 車種を外していても落ちないこと。知らない id は CarTypes 側で既定の車に落ちるが、
        // id 自体は覚えておく（カーパックを入れ直せばまた元の車に戻る）
        if (tag.contains("CarId")) {
            ResourceLocation parsed = ResourceLocation.tryParse(tag.getString("CarId"));
            if (parsed != null) {
                carId = parsed;
            }
        }
        if (tag.contains("Spec")) {
            // ここは saveWithoutId 経由なので配信はしない（まだ誰も追跡していない）
            spec = CarSpecCodec.load(tag.getCompound("Spec"));
        }
        // 姿勢。物理を引き継ぐときに takeOverPhysics がここから読む。
        // 保存しないと、ひっくり返ったまま放置した車がチャンクの読み直しで水平に戻る。
        // 単位はラジアン（/summon で横転した車を出すのにも使う）
        entityData.set(DATA_PITCH, tag.getFloat("Pitch"));
        entityData.set(DATA_ROLL, tag.getFloat("Roll"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("CarId", carId.toString());
        // 諸元を保存しないと、リログやチャンクの読み直しで調整が消えて車高が変わる
        tag.put("Spec", CarSpecCodec.save(spec));
        tag.putFloat("Pitch", entityData.get(DATA_PITCH));
        tag.putFloat("Roll", entityData.get(DATA_ROLL));
    }

    /**
     * 車がクライアントに現れるときに諸元を一緒に送る。
     *
     * <p>諸元は寸法（＝見た目）に効くので、これが無いと他人の車が既定の寸法で描かれる。
     * 走行中の変更は {@link CarSpecPacket} が受け持つ。</p>
     */
    @Override
    public void writeSpawnData(FriendlyByteBuf buf) {
        // 車種も一緒に。これが無いと、他人の車が全部既定の見た目で描かれる
        buf.writeResourceLocation(carId);
        CarSpecCodec.write(buf, spec);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buf) {
        carId = buf.readResourceLocation();
        spec = CarSpecCodec.read(buf);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        // 諸元を相乗りさせるため Forge のスポーンパケットを使う
        return NetworkHooks.getEntitySpawningPacket(this);
    }

}
