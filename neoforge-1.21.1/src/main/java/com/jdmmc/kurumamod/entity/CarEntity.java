package com.jdmmc.kurumamod.entity;

import net.neoforged.neoforge.network.PacketDistributor;
import com.jdmmc.kurumamod.CarRules;
import com.jdmmc.kurumamod.Config;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.client.CarImpact;
import com.jdmmc.kurumamod.client.CarSparks;
import com.jdmmc.kurumamod.client.CarSeat;
import com.jdmmc.kurumamod.item.CarSpawnItem;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.PartAero;
import com.jdmmc.kurumamod.physics.Tire;
import com.jdmmc.kurumamod.physics.Turbo;
import com.jdmmc.kurumamod.network.CarImpactPacket;
import com.jdmmc.kurumamod.network.CarPartsPacket;
import com.jdmmc.kurumamod.network.CarPushPacket;
import com.jdmmc.kurumamod.network.CarScrapePacket;
import com.jdmmc.kurumamod.network.CarSpecPacket;
import com.jdmmc.kurumamod.network.CarStatePacket;
import com.jdmmc.kurumamod.physics.BlockField;
import com.jdmmc.kurumamod.physics.CarBody;
import com.jdmmc.kurumamod.physics.CarCollision;
import com.jdmmc.kurumamod.physics.CarHull;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
public class CarEntity extends Entity implements IEntityWithComplexSpawn {

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
    /**
     * 低い段（{@link CarBody#STEP_LIFT} 以内）は先読みして、縁の手前から斜面で地面を上げておく。
     * 斜面の長さは「この秒数で進む距離」——段の高さ（{@link #LOOKAHEAD_RISE}）をこの時間かけて登る。
     *
     * <p>上がる速さの制限（{@link #GROUND_RISE_PER_TICK}）だけだと、ハーフの段を登りきるのに
     * 4 ティックかかり、その間（20m/s なら 4m）タイヤが段の中を走った。スラブを積んだ坂では
     * 次の段がもう来ているので遅れが溜まり、タイヤが床を突き抜け、20m/s の 25% の坂で止まって
     * 後ろへ下がり、30m/s では宙返りした。先読みすると縁に着いたときには段の高さまで上がっていて、
     * 同じ坂を 17〜20m/s のまま登る（ピッチ最大 20 度前後）。</p>
     *
     * <p>1 ブロック以上の段は先読みしない（今までどおり上がる速さを制限する）。先読みすると
     * 壁の手前で車が持ち上がり、地上高を上げた車が 1 ブロックの段を跳ねて越える。</p>
     */
    private static final double LOOKAHEAD_SECONDS = 0.15;
    /** 先読みの斜面の長さの上限 [m]。 */
    private static final double LOOKAHEAD_MAX = 3.0;
    /**
     * 下りの斜面の長さの上限 [m]。段を降りた直後は、後ろの段から斜面を下ろして地面にする。
     *
     * <p><b>登りより短くする（＝急にする）こと。</b>降りていく坂より緩い斜面を置くと地面が実際より
     * 高く残り、段ごとに大きく揺さぶられて、25% の下りを 30m/s で走ると宙返りした（上限 3m）。
     * 2m（段 50cm を 25% で降りる）ならロールの揺れが 7 度から 0〜1 度になり、宙返りもしない。
     * 1m では先読みしないのと変わらない。</p>
     *
     * <p>崖や単独の段では効かない（車は自然に放物線で飛び、その軌道が斜面より上を通る）。
     * 55cm を超える落差は斜面を作らない——作ると崖の先で宙に支えられる。</p>
     */
    private static final double LOOKAHEAD_DOWN_MAX = 2.0;
    /** 車体の角がこれより深くブロックに入っていたら、埋まったと見て持ち上げる [m]（{@link #unbury}）。 */
    private static final double BURIED_DEPTH = 0.5;
    /** 起き上がり補助の最中に、潜ったぶんとして持ち上げる上限 [m]。これより深いのは壁（上へ乗せない）。 */
    private static final double RIGHTING_MAX_LIFT = 1.0;
    /** 起き上がり補助の最中に、潜った角の横をどこまで見るか [m]。壁に触れた角を地面と取り違えないよう。 */
    private static final double RIGHTING_SIDE_CHECK = 0.2;
    /** 持ち上げるとき、ブロックの天面からさらに離す量 [m]。 */
    private static final double BURIED_CLEARANCE = 0.05;
    /** 1 回で持ち上げる上限 [m]。地中深くに落ちたものを地上まで飛ばさない（次のティックでまた上げる）。 */
    private static final double BURIED_MAX_LIFT = 6.0;
    /** 接地を探すタイヤの端を、幅の端からどれだけ内へ寄せるか [m]。 */
    private static final double TIRE_EDGE_INSET = 0.03;
    /**
     * 階段を探す範囲 [m]。前後それぞれ。<b>段の幅の 2 倍は要る。</b>
     *
     * <p>坂の入口では、段の角を結んだ線は 1 つ目の縁から段の幅ぶん手前で地面と交わる。2 つ目の縁
     * （＝段の幅）が見えるのは 1 つ目の縁が「範囲 − 段の幅」まで近づいたときなので、それが段の幅より
     * 遠くないと、見えた時点で線がもう足元より上にあり、<b>地面が一瞬で跳ね上がる</b>。3m では幅 2m の
     * スラブの坂（14 度）の入口で 27cm 跳ね、8m/s で 4 輪の 4 割が浮いた（なめらかな坂では 0）。
     * 8m なら幅 4m の段まで入る。</p>
     */
    private static final double STAIR_REACH = 8.0;
    /**
     * これより先は {@link #STAIR_FAR_SPACING} おきに調べる [m]。範囲を広げたぶんレイが増えるので、
     * 遠くは粗くする（縁の位置は二分で詰めるので、粗くしても線の傾きは変わらない）。
     */
    private static final double STAIR_NEAR_REACH = 3.0;
    /** 遠くの段を調べる間隔 [m]。段の幅（1 ブロック）より狭くしておけば縁を取りこぼさない。 */
    private static final double STAIR_FAR_SPACING = 0.5;
    /**
     * 段の縁を詰める二分の回数。0.25m を 8 回で 1mm、0.5m でも 2mm。
     *
     * <p>3 回（3cm 幅）では縁の位置が ±1.5cm ずれ、段の角を越えるたびに地面が数 mm 跳ねた。ダンパーは
     * 地面の動く速さを見るので、1/80 秒の刻みでは数 mm でも 0.5m/s になり、<b>段の角ごとに荷重が
     * 1.6 倍に跳ねた</b>（坂の途中なのに 1m おきにガタつく）。</p>
     */
    private static final int STAIR_EDGE_BISECTIONS = 8;
    /**
     * これより小さい高さの違いは段と見ない [m]。roads の階層ブロックは 1/16（6.25cm）刻みなので、
     * どんな緩い坂でも段はこれより大きい。
     */
    private static final double STAIR_EPSILON = 0.05;
    /** 階段と見る段の高さの上限 [m]。1 ブロック（＋余裕）。 */
    private static final double STAIR_STEP_MAX = 1.05;
    /**
     * 階段と見る傾きの上限。35 度。roads の 30 度の坂（1 マスに 58cm）は入り、
     * 1 マスに 1 ブロックずつ上がる坂（45 度）は入らない——それは登れない決まり。
     */
    private static final double STAIR_SLOPE_MAX = Math.tan(Math.toRadians(35.0));
    /** 先読みで地面を調べる間隔 [m]。 */
    private static final double LOOKAHEAD_SPACING = 0.25;
    /** 先読みの斜面で登る高さ [m]。ハーフブロックの段。 */
    private static final double LOOKAHEAD_RISE = 0.5;
    /** 車体の上向きの鉛直成分がこれ以下（70 度より傾いた）なら、タイヤは地面に届かない。 */
    private static final double WHEEL_UPRIGHT_MIN = 0.34;
    /**
     * 車体の下面の接地点の、どれだけ上から地面を探すか [m]。これより深くめり込んでいる地面は
     * 見つけない（そこは壁として箱が止める）。
     */
    private static final double SCRAPE_REACH = 0.5;
    /** 車体の下面の接地点の、どれだけ下まで地面を探すか [m]。擦るのはめり込んだときだけなので少しでよい。 */
    private static final double SCRAPE_BELOW = 0.3;
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
     * 車体が壁とこれより速く擦れたら火花を出す [m/s]（{@code CarSparks}）。
     * ゆっくり押し付けているだけでは飛ばない。
     */
    public static final double SPARK_MIN_SPEED = 3.0;
    /** 車同士でぶつかった火花を出す高さ（重心のシャシー基準面から） [m]。車体の真ん中あたり。 */
    private static final double CAR_SPARK_HEIGHT = 0.25;
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
    /**
     * 装着している部品。<b>車種・諸元と並ぶ 3 つめの状態。</b>
     *
     * <p>いまは見た目だけを変える（{@code CarPartModel}）。物理には一切入らないので、
     * サーバーが無人の車を解く結果は履いているものに依らない。</p>
     *
     * <p>諸元と違って<b>サーバーが決める</b>——手元にあるだけの部品を勝手に履けると、
     * 他の人には見えない部品を履いた車が走ることになる（{@code CarPartRequestPacket}）。</p>
     */
    private PartFitment parts = PartFitment.EMPTY;
    /** 物理の状態。運転権を持っている側だけが進める。 */
    private final CarState state = new CarState();
    /** 各輪の接地面の高さ。毎ティック使い回す。 */
    private final GroundContact groundContact = new GroundContact();
    /**
     * 前ティックで各輪へ渡した接地面の高さ [m]。段差の立ち上がりを制限するのと、
     * 壁に食い込んだ輪を据え置くのに使う。
     */
    private final double[] previousGroundHeight = newGroundHeights();
    /**
     * 地面の中に埋まった輪（{@link #updateGroundContact}）。壁に当たった輪とは違い、
     * 上にある地面の天面まで少しずつ持ち上げる。
     */
    private final boolean[] buriedWheel = new boolean[Wheel.COUNT];

    private static double[] newGroundHeights() {
        double[] heights = new double[Wheel.COUNT];
        Arrays.fill(heights, Double.NEGATIVE_INFINITY);
        return heights;
    }

    /** 運転クライアントから毎ティック差し込まれる入力。サーバーや同乗者側では常に無操作。 */
    private CarInput driverInput = CarInput.NONE;
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

    /**
     * 自分の画面にだけいる、記録を再生している車（ゴースト）か。
     *
     * <p>ゴーストはサーバーに存在しない。ぶつからず、殴れず、乗れない。音・煙・タイヤ痕・
     * 路面を照らす光も出さない（それぞれの描き手がこれを見て飛ばす）。</p>
     */
    private boolean ghost;
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

    /**
     * 他人の車の姿勢を、届いた値へ何ティックかけて寄せるか。<b>位置と同じ 3</b>
     * （バニラはエンティティの移動を {@code lerpTo(..., 3, ...)} で寄せる）。
     *
     * <p>以前は届いた値をそのまま「今ティックの値」にしていたので、知らせの届き方のばらつきが
     * そのまま出た。届かなかったティックは止まり、2 つまとめて届いたティックは 2 ティックぶん
     * 飛ぶ。位置は寄せているので滑らかなのに、<b>ドリフト中のロールだけがカクついて見えた</b>。
     * 寄せておけば届かないティックも目標へ動き続け、遅れも位置と揃う。</p>
     */
    private static final int POSE_LERP_STEPS = 3;
    /** 姿勢の目標（最後に届いた値）と、あと何ティックで寄せきるか。 */
    private float targetYaw;
    private float targetPitch;
    private float targetRoll;
    private float targetSteer;
    private final double[] targetSuspension = new double[Wheel.COUNT];
    private int poseLerpSteps;

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
    protected AABB makeBoundingBox() {
        if (state == null) {
            // Entity のコンストラクタから呼ばれた。まだ姿勢が無い
            return super.makeBoundingBox();
        }
        double[] box = currentBody().inscribedBox(VANILLA_BOX_MARGIN);
        return new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
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
        boolean local = isControlledByLocalInstance();
        double yaw = local ? state.yaw : remoteYaw();
        double pitch = local ? state.pitch : entityData.get(DATA_PITCH);
        double roll = local ? state.roll : entityData.get(DATA_ROLL);
        return body
                ? CarHull.body(getSpec(), bodyShape(), getX(), getY(), getZ(), yaw, pitch, roll)
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
        builder.define(DATA_SURFACE, packSurfaces(RoadSurface.PAVED));
        builder.define(DATA_LIGHTS, (byte) 0);
        builder.define(DATA_BOOST, 0.0F);
        builder.define(DATA_YAW, 0.0F);
    }

    /** 停車時のシャシー基準面の地上高 [m]。スポーンさせるときにこの高さぶん持ち上げる。 */
    public double getStaticRideHeight() {
        return spec.staticRideHeight();
    }

    /**
     * 物理が解く諸元。<b>調整画面の諸元に、装着している部品の性能を載せたもの。</b>
     *
     * <p>重ねる順は「車種の諸元 → 調整画面 → 部品」。部品の性能（ターボとタイヤ）は諸元の側に
     * 焼き込まない——焼き込むと、外しても戻らない・調整画面の値と食い違う、の二重管理になる。
     * 載せ直すのは諸元か部品か部品の一覧（データパックの再読み込み）が変わったときだけ。</p>
     *
     * <p>ターボは調整画面の項目ではないので保存も同期もされない（{@code CarSpecCodec} は
     * {@code Tunables} しか読み書きしない）。部品の装着状態は別に保存・同期されているので、
     * どちらの側でも同じものが組み上がる。</p>
     */
    public CarSpec getSpec() {
        PartFitment fitted = getEffectiveFitment();
        if (effectiveBase != spec || effectiveParts != fitted || effectiveGeneration != CarParts.generation()) {
            effectiveSpec = spec.toBuilder().turbo(turboOf(fitted)).tire(tireOf(fitted))
                    .partAero(totalAero(fitted))
                    .build();
            effectiveBase = spec;
            effectiveParts = fitted;
            effectiveGeneration = CarParts.generation();
        }
        return effectiveSpec;
    }

    /**
     * 初期装備（車種の {@code default_parts}）を足した、<b>実際に付いている状態</b>。
     * 描画・諸元・音はこちらを読む。保存・通信・換装画面の「装着中」の印は {@link #getFitment()}（元の状態）。
     *
     * <p>装着状態と車種（読み直すと別のインスタンスになる）が変わったときだけ組み直す。</p>
     */
    public PartFitment getEffectiveFitment() {
        CarType type = CarTypes.get(carId);
        if (resolvedFrom != parts || resolvedType != type) {
            resolvedFitment = parts.resolve(type.defaultPartIds());
            resolvedFrom = parts;
            resolvedType = type;
        }
        return resolvedFitment;
    }

    private PartFitment resolvedFitment = PartFitment.EMPTY;
    private PartFitment resolvedFrom;
    private CarType resolvedType;

    private CarSpec effectiveSpec = CarSpec.DEFAULT;
    private CarSpec effectiveBase;
    private PartFitment effectiveParts;
    private int effectiveGeneration = -1;

    /** 装着しているターボの性能。付いていない・知らない部品なら無し。 */
    private static Turbo turboOf(PartFitment parts) {
        ResourceLocation id = parts.getPart(PartSlot.TURBO);
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.turbo() == null ? Turbo.NONE : part.turbo();
    }

    /** 履いているタイヤの性能。付いていない・知らない部品ならノーマル。 */
    private static Tire tireOf(PartFitment parts) {
        ResourceLocation id = parts.getPart(PartSlot.TIRE);
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.tire() == null ? Tire.NORMAL : part.tire();
    }

    /** 車体の部品すべて（バンパー・スカート・フェンダー・ウイング・ディフューザー）が足すダウンフォース。 */
    private static PartAero totalAero(PartFitment parts) {
        PartAero total = PartAero.NONE;
        for (PartSlot slot : PartSlot.VALUES) {
            if (slot.isBodyPart()) {
                total = total.plus(aeroOf(parts, slot));
            }
        }
        return total;
    }

    /**
     * その場所の空力部品が足すダウンフォース。付いていない・知らない部品なら無し。
     *
     * <p>角度は装着状態の上書き、無ければ部品の既定。範囲は部品が持ち、角度はその中の位置。</p>
     */
    private static PartAero aeroOf(PartFitment parts, PartSlot slot) {
        ResourceLocation id = parts.getPart(slot);
        CarPart part = id == null ? null : CarParts.get(id);
        if (part == null || part.aero() == null) {
            return PartAero.NONE;
        }
        Double angle = parts.getAngle(slot);
        return part.aero().at(angle != null ? angle : part.aero().defaultAngle());
    }

    /** ターボが付いているか。 */
    public boolean hasTurbo() {
        return getSpec().turbo().present();
    }

    /**
     * ブローオフバルブが付いているか（<b>音だけ</b>。ターボが無ければ鳴らない）。
     * 知らない部品（カーパックを外した）なら付いていないものとする。
     */
    public boolean hasBlowoff() {
        ResourceLocation id = getEffectiveFitment().getPart(PartSlot.BLOWOFF);
        return id != null && CarParts.get(id) != null && hasTurbo();
    }

    /** ブースト 0..1。手元で解いていないときは同期された値。 */
    public double getRenderBoost() {
        return hasLocalState() ? state.boost : entityData.get(DATA_BOOST);
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
            PacketDistributor.sendToPlayersTrackingEntity(this, new CarSpecPacket(getId(), spec));
        }
    }

    /**
     * 装着している部品。付けていない場所は車種の既定の見た目になる。
     *
     * <p><b>{@code getParts()} という名前は使えない。</b>Forge の {@code IForgeEntity} が
     * 多分割エンティティ用に同じ名前（戻り値 {@code PartEntity<?>[]}）を持っているため。</p>
     */
    public PartFitment getFitment() {
        return parts;
    }

    /**
     * 装着している部品を差し替える。
     *
     * <p>見た目に効くので、サーバー側で変わったときは周囲のクライアントへ配る。
     * 配らないと、換えた本人の画面でしか見た目が変わらない。車が現れるときのぶんは
     * {@link #writeSpawnData} が運ぶ。</p>
     */
    public void setFitment(PartFitment parts) {
        this.parts = parts;
        if (!level().isClientSide) {
            PacketDistributor.sendToPlayersTrackingEntity(this, new CarPartsPacket(getId(), parts));
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
            lastSentState = new CarStatePacket(getId(),
                    (float) state.steerAngle, (float) state.forwardSpeed,
                    getYRot(), (float) state.pitch, (float) state.roll,
                    suspensionSnapshot(), wheelSpeedSnapshot(), frictionSnapshot(),
                    (float) state.engineRpm, state.gear, (float) state.throttle,
                    (float) (state.slipAngle() * Mth.RAD_TO_DEG), packSurfaces(),
                    driverLights(), (float) state.boost);
            PacketDistributor.sendToServer(lastSentState);
        } else {
            lastSentState = null;
        }

        if (!level().isClientSide) {
            tickAbandoned();
        }

        // 位置が変わらなくても姿勢が変われば箱の形が変わる
        setBoundingBox(makeBoundingBox());
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

        if (local) {
            renderYaw = getYRot();
            renderPitch = (float) state.pitch;
            renderRoll = (float) state.roll;
            renderSteer = (float) state.steerAngle;
            System.arraycopy(state.suspensionLength, 0, renderSuspension, 0, Wheel.COUNT);
            poseLerpSteps = 0;
        } else {
            approachSyncedPose();
        }

        // 起き上がり補助は姿勢の表し方を置き換える（ヨー +180 度・ピッチ π−p・ロール +π）。
        // 同じ向きでも数字が飛ぶので、前の姿勢も同じ表し方へ揃えてから補間する
        if (isOtherRepresentationCloser(prevRenderYaw, prevRenderPitch, prevRenderRoll,
                renderYaw, renderPitch, renderRoll)) {
            prevRenderYaw += 180.0F;
            prevRenderPitch = wrapRadians(Mth.PI - prevRenderPitch);
            prevRenderRoll = wrapRadians(prevRenderRoll + Mth.PI);
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

    /**
     * 同期された姿勢へ寄せる（他人の車）。新しい値が届いていたら目標を差し替えて数え直し、
     * 残りティック数で割った分だけ進める（バニラの位置の寄せ方と同じ）。
     */
    private void approachSyncedPose() {
        float yaw = entityData.get(DATA_YAW);
        float pitch = entityData.get(DATA_PITCH);
        float roll = entityData.get(DATA_ROLL);
        float steer = entityData.get(DATA_STEER_ANGLE);
        boolean changed = !renderSnapshotReady || yaw != targetYaw || pitch != targetPitch
                || roll != targetRoll || steer != targetSteer;
        for (int i = 0; i < Wheel.COUNT; i++) {
            double length = entityData.get(DATA_SUSPENSION.get(i));
            changed |= length != targetSuspension[i];
            targetSuspension[i] = length;
        }
        targetYaw = yaw;
        targetPitch = pitch;
        targetRoll = roll;
        targetSteer = steer;
        if (!renderSnapshotReady) {
            // 最初は寄せずにそのまま置く
            renderYaw = yaw;
            renderPitch = pitch;
            renderRoll = roll;
            renderSteer = steer;
            System.arraycopy(targetSuspension, 0, renderSuspension, 0, Wheel.COUNT);
            poseLerpSteps = 0;
            return;
        }
        if (changed) {
            poseLerpSteps = POSE_LERP_STEPS;
        }
        if (poseLerpSteps <= 0) {
            return;
        }
        // 届いた姿勢が別の表し方に切り替わっていたら、寄せる前にこちらも揃える。
        // 揃えずに寄せると、同じ向きのはずの車が数ティックかけてでんぐり返る
        if (isOtherRepresentationCloser(renderYaw, renderPitch, renderRoll, yaw, pitch, roll)) {
            renderYaw += 180.0F;
            renderPitch = wrapRadians(Mth.PI - renderPitch);
            renderRoll = wrapRadians(renderRoll + Mth.PI);
        }
        float step = 1.0F / poseLerpSteps;
        renderYaw += Mth.wrapDegrees(yaw - renderYaw) * step;
        renderPitch = wrapRadians(renderPitch + wrapRadians(pitch - renderPitch) * step);
        renderRoll = wrapRadians(renderRoll + wrapRadians(roll - renderRoll) * step);
        renderSteer += (steer - renderSteer) * step;
        for (int i = 0; i < Wheel.COUNT; i++) {
            renderSuspension[i] += (targetSuspension[i] - renderSuspension[i]) * step;
        }
        poseLerpSteps--;
    }

    /**
     * 姿勢 (yaw, pitch, roll) を別の表し方（ヨー +180 度・ピッチ π−p・ロール +π。回転としては同じ）に
     * 直したほうが、目標の姿勢に数字の上で近いか。ヨーは度、ピッチとロールはラジアン。
     */
    private static boolean isOtherRepresentationCloser(float yaw, float pitch, float roll,
                                                      float targetYaw, float targetPitch, float targetRoll) {
        float same = poseDistance(yaw, pitch, roll, targetYaw, targetPitch, targetRoll);
        float other = poseDistance(yaw + 180.0F, Mth.PI - pitch, roll + Mth.PI, targetYaw, targetPitch, targetRoll);
        return other < same;
    }

    private static float poseDistance(float yaw, float pitch, float roll,
                                      float targetYaw, float targetPitch, float targetRoll) {
        return Math.abs(Mth.wrapDegrees(targetYaw - yaw)) * Mth.DEG_TO_RAD
                + Math.abs(wrapRadians(targetPitch - pitch))
                + Math.abs(wrapRadians(targetRoll - roll));
    }

    /** 角度 [rad] を -π..π へ巻き取る。 */
    private static float wrapRadians(float angle) {
        float wrapped = angle % Mth.TWO_PI;
        if (wrapped >= Mth.PI) {
            wrapped -= Mth.TWO_PI;
        } else if (wrapped < -Mth.PI) {
            wrapped += Mth.TWO_PI;
        }
        return wrapped;
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
        // 向きは描いているのと同じ値から取る。クライアントの getYRot() はバニラの位置同期
        // （1.4 度刻み・3 ティックかけて寄せる）を補間した途中の値で、ピッチ・ロール（同期値）とも
        // 揃っていないので、乗った瞬間に車が水平に回った。サーバーの getYRot() は運転者の移動
        // パケットそのもので、最初のティックはまだ同期値が無いので getYRot() のまま
        float yaw = level().isClientSide ? entityData.get(DATA_YAW) : getYRot();
        setYRot(yaw);
        state.yaw = yaw * Mth.DEG_TO_RAD;
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
        updateWetness();

        // 車体（CarBody）とブロックの当たりは物理の 1 ステップごとに解き、位置もここで積む。
        // 車体は硬い箱で、ブロックと交わらない。当たった点に力積を掛けるので、当たったところを
        // 支点に車が回る（腹が乗れば亀、転がれば屋根の角が当たる）
        CarSpec carSpec = getSpec();
        double[] span = tireSpan();
        CarBody body = CarBody.of(carSpec, bodyShape()).tireSpan(span[0], span[1]);
        double x = getX();
        double z = getZ();
        fillBlockField(body, x, z);
        double dt = TICK_SECONDS / PHYSICS_SUBSTEPS;
        double impact = 0.0;
        boolean wall = false;
        boolean floor = false;
        double scrapeSpeed = 0.0;
        // 地形とぶつかった強さ（車体と、底を打ったサスペンション）。車同士のぶんは入れない
        double terrainImpact = 0.0;
        float[] scrapeOffset = null;
        float[] scrapeNormal = null;
        float[] scrapeVelocity = null;
        for (int i = 0; i < PHYSICS_SUBSTEPS; i++) {
            // 接地は物理の 1 刻みごとに探す。1 ティックに 1 回だと、20m/s では次に探すまでに 1m 進み、
            // その間に越えた段の縁を知らないまま新しい段の中を走って、スラブの坂でタイヤが床に埋まった
            updateGroundContact(x, z, dt);
            CarPhysics.step(carSpec, state, groundContact, dt, input);
            CarBody.Result hit = body.solve(carSpec, state, groundContact, x, z, blockField, dt);
            impact = Math.max(impact, hit.impact);
            terrainImpact = Math.max(terrainImpact, hit.impact);
            // 火花は 1 ティックのうちいちばん速く擦れた刻みから出す。位置はその刻みの車からのずれで持つ
            if (hit.scrapeSpeed > scrapeSpeed) {
                scrapeSpeed = hit.scrapeSpeed;
                scrapeOffset = new float[]{(float) (hit.scrapeX - x), (float) (hit.scrapeY - state.height),
                        (float) (hit.scrapeZ - z)};
                scrapeNormal = new float[]{(float) hit.scrapeNormalX, (float) hit.scrapeNormalZ};
                scrapeVelocity = new float[]{(float) hit.scrapeVelocityX, (float) hit.scrapeVelocityY,
                        (float) hit.scrapeVelocityZ};
            }
            wall |= hit.horizontal;
            floor |= hit.vertical;
            x += state.velocityX() * dt;
            z += state.velocityZ() * dt;
        }

        setYRot((float) (state.yaw * Mth.RAD_TO_DEG));
        setRot(getYRot(), getXRot());

        // 車同士の当たりは、車同士用の箱（CarHull#body）で解く
        Vec3 velocity = new Vec3(x - getX(), state.height - getY(), z - getZ());
        setDeltaMovement(velocity);
        HullMove hullMove = moveCars(velocity);
        float[][] carSparks = null;
        horizontalCollision = wall || hullMove.motion().horizontal;
        verticalCollision = floor;
        if (hullMove.car() != null) {
            CarHull.Motion motion = hullMove.motion();
            CarEntity other = hullMove.car();
            // 衝撃は相手へ近づいていた速さ
            double closing = (state.velocityX() - other.worldVelocityX()) * motion.carNormalX
                    + (state.velocityZ() - other.worldVelocityZ()) * motion.carNormalZ;
            impact = Math.max(impact, closing);
            // 相手が車なら運動量のやりとりで決める（壁と違って相手も動く）
            resolveCarImpact(other, motion);
            if (closing >= IMPACT_MIN_SPEED) {
                carSparks = carImpactSparks(other, motion, closing);
            }
        }
        reportImpact(impact);
        if (carSparks != null) {
            // 車同士でぶつかったティックは、そちらを優先する（T ボーンなど、いちばん派手に散る場面）
            reportScrape(carSparks[0], carSparks[1], carSparks[2], carSparks[3][0]);
        } else if (scrapeOffset != null
                && (scrapeSpeed >= SPARK_MIN_SPEED || terrainImpact >= IMPACT_MIN_SPEED)) {
            reportScrape(scrapeOffset, scrapeNormal, scrapeVelocity,
                    terrainImpact >= IMPACT_MIN_SPEED ? (float) terrainImpact : 0.0F);
        }
        state.height = getY();
        unbury(body);

        if (!level().isClientSide) {
            // 無人の車をサーバーが動かしている場合。運転者から届く値の代わりに自分の結果を配る
            applyDriverState((float) state.steerAngle, (float) state.forwardSpeed,
                    getYRot(), (float) state.pitch, (float) state.roll,
                    suspensionSnapshot(), wheelSpeedSnapshot(), frictionSnapshot(),
                    (float) state.engineRpm, state.gear, (float) state.throttle,
                    (float) (state.slipAngle() * Mth.RAD_TO_DEG), packSurfaces(),
                    driverLights(), (float) state.boost);
        }
    }

    /**
     * 車体が地形に深く埋まっていたら、真上へ持ち上げて出す。<b>原因を問わない最後の安全策。</b>
     *
     * <p>車体の当たり（{@link CarBody}）は、ブロックに入ったぶんを少しずつ押し戻すだけで、
     * 深く入り込んだものを引き上げる仕組みは持っていない（地面の中のブロックは上も横も
     * 隣がブロックなので、押し出す向きが見つからない）。いったん深く入ると、そのまま
     * 地面に刺さって動けなくなった。落下や転がりでは物理の検算で再現しない入り方をしているので、
     * 入り方を塞ぐ代わりに、入ってしまったものを出す。</p>
     *
     * <p>埋まったと見るのは、車体の角のどれかがブロックの中に {@link #BURIED_DEPTH} より深く
     * 入っていて、<b>その角の前後左右も同じだけブロックに囲まれている</b>とき（地面や壁に
     * 触れているだけ・食い込みかけを押し戻している最中は対象外）。
     * 持ち上げる量は、入っている角のうち、その上に積み重なったブロックの天面までがいちばん遠いもの。
     * 傾きはそのまま（起こすのは {@code CarPhysics} の起き上がり補助）。落ちる速さは捨てる。</p>
     *
     * <p><b>起き上がり補助の最中は、浅く潜ったぶんも持ち上げる</b>（{@link #RIGHTING_MAX_LIFT} まで）。
     * 補助は重心まわりに回すので地面側の縁が潜り、毎刻み角速度を置き直すので車体の当たりの
     * 押し返しが追いつかず、起こしながら地面へ刺さっていった。補助そのものが物理の外の
     * 手助けなので、位置を直接動かしてよい。</p>
     */
    private void unbury(CarBody body) {
        double[][] axes = CarBody.axes(state.yaw, state.pitch, state.roll);
        double x = getX();
        double y = getY();
        double z = getZ();
        double lift = 0.0;
        // 起き上がり補助は重心まわりに回すので、地面側の縁が潜る（車体の当たりが押し返しても、
        // 補助が毎刻み角速度を置き直すので追いつかない）。その間は浅く潜ったぶんも持ち上げる
        boolean righting = CarPhysics.isRighting(state);
        for (double[] corner : body.corners()) {
            double cx = x + axes[0][0] * corner[0] + axes[1][0] * corner[1] + axes[2][0] * corner[2];
            double cy = y + axes[0][1] * corner[0] + axes[1][1] * corner[1] + axes[2][1] * corner[2];
            double cz = z + axes[0][2] * corner[0] + axes[1][2] * corner[1] + axes[2][2] * corner[2];
            double top = solidTopAbove(cx, cy, cz);
            // 縦に深いだけでは足りない。壁に数 cm 入った角も「上まで 0.5m 以上ある」ので、
            // 横にも深く囲まれていること（前後左右に離れた点もブロックの中）を見る。
            // そうでないと、壁に当たっただけの車を壁の上へ持ち上げてしまう
            double depth = top - cy;
            if (depth > BURIED_DEPTH && surroundedSideways(cx, cy, cz, BURIED_DEPTH)) {
                lift = Math.max(lift, depth + BURIED_CLEARANCE);
            } else if (righting && depth > 0.0 && depth <= RIGHTING_MAX_LIFT
                    && surroundedSideways(cx, cy, cz, RIGHTING_SIDE_CHECK)) {
                lift = Math.max(lift, depth);
            }
        }
        if (lift <= 0.0) {
            return;
        }
        lift = Math.min(lift, BURIED_MAX_LIFT);
        setPos(x, y + lift, z);
        state.height = getY();
        state.verticalSpeed = Math.max(0.0, state.verticalSpeed);
        // 地面の高さの記憶は埋まっていたときのもの。上がる速さの制限が古い値から効かないよう捨てる
        Arrays.fill(previousGroundHeight, Double.NEGATIVE_INFINITY);
    }

    /** 点から東西南北へ {@code distance} 離れた 4 点が、すべてブロックの中にあるか。壁に触れているだけの点を除く。 */
    private boolean surroundedSideways(double x, double y, double z, double distance) {
        return solidTopAbove(x + distance, y, z) > y
                && solidTopAbove(x - distance, y, z) > y
                && solidTopAbove(x, y, z + distance) > y
                && solidTopAbove(x, y, z - distance) > y;
    }

    /**
     * 点がブロックの当たり判定の中にあれば、そこから上へすき間なく続くブロックの天面。
     * 中に無ければ負の無限大。
     */
    private double solidTopAbove(double x, double y, double z) {
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
                    car -> car != this && !car.isRemoved() && !car.ghost)) {
                cars.add(other);
                hulls.add(other.currentBody());
            }
        }
        CarHull.Motion motion = hull.move(delta.x, delta.y, delta.z, List.of(), currentBody(), hulls);
        setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);
        // バニラの move() がしていた、踏んだブロックへの通知（感圧板・ポータルなど）
        checkInsideBlocks();
        return new HullMove(motion.car >= 0 ? cars.get(motion.car) : null, motion);
    }

    /**
     * 車体の大きさを決める諸元。<b>車種の元の諸元</b>（調整画面の値を含まない）。
     *
     * <p>調整画面でタイヤ半径・ホイールベース・トレッドを動かしても、見た目の車体は伸び縮み
     * しない（タイヤだけが動く）。当たり判定の大きさもそれに合わせて変えない。</p>
     */
    private CarSpec bodyShape() {
        return CarTypes.get(getCarId()).spec();
    }

    /** 車体の周りのブロックの当たり判定の箱。物理の 1 ティックの間、使い回す。 */
    private final BlockField blockField = new BlockField();

    /**
     * 車体の周りのブロックの当たり判定を {@link #blockField} に集める。1 ティックで動ける範囲
     * （速さ × 1 ティック + 1m）まで広げて取る。
     */
    private void fillBlockField(CarBody body, double x, double z) {
        blockField.clear();
        double[] b = body.bounds(x, state.height, z, CarBody.axes(state.yaw, state.pitch, state.roll));
        double reach = Math.hypot(state.velocityX(), state.velocityZ()) * TICK_SECONDS
                + Math.abs(state.verticalSpeed) * TICK_SECONDS + 1.0;
        AABB area = new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).inflate(reach);
        for (VoxelShape shape : level().getBlockCollisions(this, area)) {
            for (AABB box : shape.toAabbs()) {
                blockField.add(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            }
        }
    }

    /**
     * タイヤの当たりを見る点のワールド座標（{@link CarBody#tireRims}）。描画用。
     * サスペンションの伸びは手元で物理を解いている車しか持っていないので、それ以外は null。
     */
    @Nullable
    public double[][][] tireRims() {
        if (!isControlledByLocalInstance()) {
            return null;
        }
        double[] span = tireSpan();
        return CarBody.tireRims(getSpec(), state, getX(), getZ(), span[0], span[1]);
    }

    /**
     * 描画で測ったタイヤの幅（{@link CarBody#tireSpan}）。物理上のタイヤの位置から外向きに {内側の端, 外側の端}。
     * 描いていない車（サーバーが動かす無人の車）は null のままで、{@link CarBody#defaultTireSpan} を使う。
     */
    @Nullable
    private double[] visualTireSpan;

    /** 描画側が、いま描いたタイヤの幅を知らせる。 */
    public void setVisualTireSpan(double inner, double outer) {
        visualTireSpan = new double[]{inner, outer};
    }

    private double[] tireSpan() {
        return visualTireSpan != null ? visualTireSpan : CarBody.defaultTireSpan(getSpec());
    }

    /**
     * 接地を探すタイヤの内側と外側の端。幅の端そのものだと、隣のブロックの面にちょうど
     * 触れているだけで乗り上げるので、少し内へ寄せる。
     */
    private double[] tireGroundEdges() {
        double[] span = tireSpan();
        double inset = Math.min(TIRE_EDGE_INSET, (span[1] - span[0]) / 2.0);
        return new double[]{span[0] + inset, span[1] - inset};
    }

    /** 車体（{@link CarBody}）の角のワールド座標。描画用。順番は {@link CarBody#corners()} と同じ。 */
    public double[][] bodyCorners() {
        boolean local = isControlledByLocalInstance();
        double yaw = local ? state.yaw : remoteYaw();
        double pitch = local ? state.pitch : entityData.get(DATA_PITCH);
        double roll = local ? state.roll : entityData.get(DATA_ROLL);
        double[][] ax = CarBody.axes(yaw, pitch, roll);
        double[][] corners = CarBody.of(getSpec(), bodyShape()).corners();
        double[][] out = new double[corners.length][];
        for (int i = 0; i < corners.length; i++) {
            double[] c = corners[i];
            out[i] = new double[]{
                    getX() + ax[0][0] * c[0] + ax[1][0] * c[1] + ax[2][0] * c[2],
                    getY() + ax[0][1] * c[0] + ax[1][1] * c[1] + ax[2][1] * c[2],
                    getZ() + ax[0][2] * c[0] + ax[1][2] * c[1] + ax[2][2] * c[2]};
        }
        return out;
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
     * <p>当たった向きと接触点は車体の箱が押し出したときのもの（{@link CarHull.Motion}）。
     * 接触点が相手の重心から離れていればその腕の長さぶんヨーのモーメントになるので、
     * <b>側面に当てれば相手は回る</b>。</p>
     *
     * <p>速度は「出そうとした速度」から解く。{@link #moveCars} が既に壁と同じように
     * 止めてしまっているが、車同士では止まるのが正しいとは限らない（弾かれる）。</p>
     */
    private void resolveCarImpact(CarEntity other, CarHull.Motion motion) {
        double nx = motion.carNormalX;
        double nz = motion.carNormalZ;
        double contactX = motion.contactX;
        double contactZ = motion.contactZ;

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
        PacketDistributor.sendToServer(new CarPushPacket(other.getId(),
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
            CarImpact.onImpact(this, impact);
            PacketDistributor.sendToServer(new CarImpactPacket(getId(), (float) impact));
        } else {
            CarImpactPacket.broadcast(this, (float) impact);
        }
    }

    /**
     * 車体が擦れた・ぶつかったことを火花に変える。衝突（{@link #reportImpact}）と同じく、
     * 気づけるのは物理を解いている側だけなので、ここで拾って {@link CarScrapePacket} で周りへ配る。
     * 運転者は自分の画面へ先に出す。
     */
    private void reportScrape(float[] offset, float[] normal, float[] velocity, float impact) {
        if (level().isClientSide) {
            CarSparks.emit(this, offset, normal, velocity, impact);
            PacketDistributor.sendToServer(new CarScrapePacket(getId(), offset, normal, velocity, impact));
        } else {
            CarScrapePacket.broadcast(this, offset, normal, velocity, impact);
        }
    }

    /**
     * 車同士でぶつかったときの火花の出どころ。{@link #reportScrape} へ渡す形（ずれ・外向き・擦れる速度・強さ）。
     *
     * <p>接点は車同士の箱が押し出したときのもの（{@link CarHull.Motion}）。高さは車体の真ん中あたり
     * （箱は高さの情報を持たない）。外向きは相手の面からこちらへ戻る向き、擦れる速度は相手に対する
     * こちらの速さのうち、面に沿った成分。</p>
     */
    private float[][] carImpactSparks(CarEntity other, CarHull.Motion motion, double closing) {
        double relativeX = state.velocityX() - other.worldVelocityX();
        double relativeZ = state.velocityZ() - other.worldVelocityZ();
        double toward = relativeX * motion.carNormalX + relativeZ * motion.carNormalZ;
        return new float[][]{
                {(float) (motion.contactX - getX()), (float) CAR_SPARK_HEIGHT, (float) (motion.contactZ - getZ())},
                {(float) -motion.carNormalX, (float) -motion.carNormalZ},
                {(float) (relativeX - toward * motion.carNormalX), 0.0F, (float) (relativeZ - toward * motion.carNormalZ)},
                {(float) closing}};
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
    private void updateGroundContact(double carX, double carZ, double dt) {
        // 大きく傾いた（70 度を超えた）車のタイヤは地面に届かない。サスペンションは真下へしか
        // 伸びないので、そのままだと横倒しや裏返しの車がタイヤで屋根を突き抜けて地面を押す。
        // 横倒しの車は側面で、裏返しの車は屋根で地面に乗る（車体の仕事）
        if (Math.cos(state.pitch) * Math.cos(state.roll) <= WHEEL_UPRIGHT_MIN) {
            for (Wheel wheel : Wheel.VALUES) {
                setGroundHeight(wheel, Double.NEGATIVE_INFINITY, 0.0);
            }
            return;
        }
        double forwardX = state.forwardX();
        double forwardZ = state.forwardZ();
        double rightX = state.rightX();
        double rightZ = state.rightZ();
        // 落ちているときは、この 1 刻みで落ちる距離だけ先まで探す。届かないと、地面を見つける
        // 前に車体が地面へ着き、縮みきったサスペンションを止める当たり（CarBody）も間に合わず埋まった
        // （10m の落下で 1 ティックに 68cm 落ちる）
        double fallReach = Math.max(0.0, -state.verticalSpeed) * dt;
        // 段差の立ち上がり制限は 1 ティックあたりの量なので、刻みの長さで割り振る
        double rise = GROUND_RISE_PER_TICK * dt / TICK_SECONDS;
        double rayLength = spec.suspensionMaxLength() + spec.wheelRadius() + GROUND_RAY_MARGIN + fallReach;

        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            double forwardOffset = spec.wheelForwardOffset(wheel);
            double rightOffset = spec.wheelRightOffset(wheel);

            // 水平の位置は傾きの cos ぶん縮む。高さだけ傾けると、坂の上で前輪が地面を先で探す
            double forward = state.horizontalForwardOffset(forwardOffset);
            double right = state.horizontalRightOffset(rightOffset);
            double x = carX + forwardX * forward + rightX * right;
            double z = carZ + forwardZ * forward + rightZ * right;
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
            // タイヤには幅がある。内側と外側の端でも地面を探し、いちばん高いところに乗せる。
            // 中心だけで見ると、台の端から半分はみ出したタイヤが台の下まで落ち、
            // 内側の面が台の側面に刺さった（そこをタイヤの当たりが横へ押し返して車が揺れた）。
            // 中心が壁の中なら壁のまま（据え置き）
            if (!hit.isInside()) {
                double outward = wheel.isLeft() ? -1.0 : 1.0;
                for (double edge : tireGroundEdges()) {
                    double ex = x + rightX * outward * edge;
                    double ez = z + rightZ * outward * edge;
                    BlockHitResult side = level().clip(new ClipContext(
                            new Vec3(ex, from, ez),
                            new Vec3(ex, bottom, ez),
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            this));
                    if (side.getType() == HitResult.Type.MISS || side.isInside()) {
                        continue;
                    }
                    if (hit.getType() == HitResult.Type.MISS || side.getLocation().y > hit.getLocation().y) {
                        hit = side;
                    }
                }
            }

            if (hit.getType() == HitResult.Type.MISS) {
                // 階段の下りでは、縁を越えた直後の下の段がレイの先にある。1 段ぶん深く探して、
                // 階段なら段の角を結んだ斜面で渡す。崖なら今までどおり地面なし（落ちる）
                BlockHitResult deep = level().clip(new ClipContext(
                        new Vec3(x, from, z),
                        new Vec3(x, bottom - STAIR_STEP_MAX, z),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        this));
                if (deep.getType() != HitResult.Type.MISS && !deep.isInside()) {
                    double height = deep.getLocation().y;
                    double stair = stairHeight(x, z, from, bottom, height);
                    if (stair >= bottom) {
                        RoadSurface surface = SurfaceLookup.of(level().getBlockState(deep.getBlockPos()));
                        surfaces[index] = surface;
                        double previous = previousGroundHeight[index];
                        double base = previous > Double.NEGATIVE_INFINITY ? previous : Math.min(height, wheelBottom);
                        setGroundHeight(wheel, height, tireGrip(surface), tireRolling(surface), base, stair, rise);
                        continue;
                    }
                }
                setGroundHeight(wheel, Double.NEGATIVE_INFINITY, rise);
                continue;
            }
            if (!hit.isInside()) {
                buriedWheel[index] = false;
            } else if (buriedWheel[index] || previousGroundHeight[index] == Double.NEGATIVE_INFINITY) {
                // 地面を見ていなかった輪が、いきなり登坂限界より深く地面の中にある＝走って壁に
                // 当たったのではなく、上から（回って）地面に入った。起き上がり補助が横倒しの車を
                // 起こすとき、伸びきったサスペンションの先のタイヤが地面へ 1.5m ほど潜る
                // （大きいタイヤほど深い）。据え置くと地面が無いままで、タイヤを埋めたまま
                // 車体の角で斜めに止まった。上にある地面の天面を、上がる速さを制限して渡す
                double surface = solidTopAbove(x, from, z);
                if (surface > Double.NEGATIVE_INFINITY && surface <= top + spec.wheelRadius()) {
                    buriedWheel[index] = true;
                    double previous = previousGroundHeight[index];
                    double base = previous > Double.NEGATIVE_INFINITY ? previous : wheelBottom;
                    setGroundHeight(wheel, surface, tireGrip(surfaces[index]), tireRolling(surfaces[index]),
                            base, Double.NEGATIVE_INFINITY, rise);
                    continue;
                }
            }
            if (hit.isInside()) {
                // 起点が塞がっている＝登坂限界より上まで続く壁。乗せずに据え置く
                groundContact.set(wheel, previousGroundHeight[index],
                        tireGrip(surfaces[index]), tireRolling(surfaces[index]));
                continue;
            }

            // 当たったブロックが路面。知らないブロックは舗装路に落ちるので、
            // 他 MOD の道路ブロックはそのままアスファルトとして走れる
            RoadSurface surface = SurfaceLookup.of(level().getBlockState(hit.getBlockPos()));
            surfaces[index] = surface;
            double height = hit.getLocation().y;
            double previous = previousGroundHeight[index];
            // 段の高さを測る基準。地面を見失っていた輪は、いまのタイヤ下端から
            double base = previous > Double.NEGATIVE_INFINITY ? previous : Math.min(height, wheelBottom);
            // 階段（段の角を結んだ斜面）が取れればそちらを使う。先読みの斜面は 0.25m おきの
            // 粗いもので、混ぜると地面が数 cm ずつ跳ね、ダンパーがそれを速度として拾って車を跳ね上げる
            double stair = stairHeight(x, z, from, bottom, height);
            double ahead = stair > Double.NEGATIVE_INFINITY ? stair : lookAhead(x, z, from, bottom, base, height);
            setGroundHeight(wheel, height, tireGrip(surface), tireRolling(surface), base, ahead, rise);
        }
    }

    /**
     * 段から斜面を下ろしたときの、いまの位置での高さ。前にある低い段からは手前へ（登り）、
     * 後ろにある低い段からはこちらへ（下り）。低い段が無ければ負の無限大。
     *
     * @param base   この輪のいまの地面。前の段はこれより {@link CarBody#STEP_LIFT} を超えて高いものは見ない
     * @param height この輪の真下の地面。後ろの段はこれより {@link CarBody#STEP_LIFT} を超えて高いものは見ない（崖）
     */
    private double lookAhead(double x, double z, double from, double bottom, double base, double height) {
        double velocityX = state.velocityX();
        double velocityZ = state.velocityZ();
        double speed = Math.hypot(velocityX, velocityZ);
        double best = Double.NEGATIVE_INFINITY;
        if (speed < 0.1) {
            return best;
        }
        double reach = Math.min(LOOKAHEAD_MAX, speed * LOOKAHEAD_SECONDS);
        double slope = LOOKAHEAD_RISE / reach;
        for (double distance = LOOKAHEAD_SPACING; distance <= reach + 1.0e-9; distance += LOOKAHEAD_SPACING) {
            double px = x + velocityX / speed * distance;
            double pz = z + velocityZ / speed * distance;
            BlockHitResult hit = level().clip(new ClipContext(
                    new Vec3(px, from, pz),
                    new Vec3(px, bottom, pz),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    this));
            if (hit.getType() == HitResult.Type.MISS || hit.isInside()) {
                continue;
            }
            double ground = hit.getLocation().y;
            if (ground - base > CarBody.STEP_LIFT) {
                continue;
            }
            best = Math.max(best, ground - slope * distance);
        }
        // 下り。後ろの段から斜面を下ろす
        double downReach = Math.min(LOOKAHEAD_DOWN_MAX, speed * LOOKAHEAD_SECONDS);
        double downSlope = LOOKAHEAD_RISE / downReach;
        for (double distance = LOOKAHEAD_SPACING; distance <= downReach + 1.0e-9; distance += LOOKAHEAD_SPACING) {
            double px = x - velocityX / speed * distance;
            double pz = z - velocityZ / speed * distance;
            BlockHitResult hit = level().clip(new ClipContext(
                    new Vec3(px, from, pz),
                    new Vec3(px, bottom, pz),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    this));
            if (hit.getType() == HitResult.Type.MISS || hit.isInside()) {
                continue;
            }
            double ground = hit.getLocation().y;
            if (ground - height > CarBody.STEP_LIFT) {
                continue;
            }
            best = Math.max(best, ground - downSlope * distance);
        }
        return best;
    }

    /**
     * 階段を斜面として見たときの、いまの位置での高さ。段の角（縁の上端）を結んだ線の上。
     * 階段でなければ負の無限大。
     *
     * <p>roads の階層ブロックで作った坂は、1 マスごとに平らな段が並んだもの。30 度の坂なら
     * 1 マスに 58cm の段になり、低い段の先読み（{@link #lookAhead}、55cm まで）から外れて
     * <b>崖が並んでいるのと同じ扱いになっていた</b>——段を降りるたびに前輪が宙に出て、
     * 下りでは 4 輪とも浮いている時間が 5〜8 割に達した。実際の車が走るのは段の角どうしを
     * 結んだ面なので、その面を地面として渡す。</p>
     *
     * <p>階段と見るのは、段の幅が測れるとき（こちらの段の反対側にも縁があるか、高い側の
     * 向こうに次の段があるとき）だけ。<b>崖・単独の段は今までどおり</b>（放物線で飛ぶ・
     * 段差として登る）。急すぎるもの（{@link #STAIR_SLOPE_MAX} 超え、1 マスに 1 ブロックの坂など）も
     * 階段とは見ない。</p>
     *
     * <p>向きは車体の前後。止まっていても坂の形は変わらないので、速度の向きには依らない。</p>
     *
     * @param height この輪の真下の地面
     */
    private double stairHeight(double x, double z, double from, double bottom, double height) {
        double dirX = state.forwardX();
        double dirZ = state.forwardZ();
        double deep = bottom - STAIR_STEP_MAX;
        double[] ahead = stairEdges(x, z, dirX, dirZ, from, deep, height);
        double[] behind = stairEdges(x, z, -dirX, -dirZ, from, deep, height);
        // 後ろが高い（下り）か、前が高い（上り）か。谷ならどちらも立つので高いほう
        return Math.max(stairSide(height, behind, ahead), stairSide(height, ahead, behind));
    }

    /**
     * 片側へ歩いて、段の縁を手前から 2 つまで探す。{縁 1 までの距離, その先の高さ, 縁 2 までの距離, その先の高さ}。
     * 見つからなければ NaN。縁の位置は二分して 1〜2mm まで詰める（{@link #STAIR_EDGE_BISECTIONS}）。
     */
    private double[] stairEdges(double x, double z, double dirX, double dirZ, double from, double bottom,
                                double height) {
        double[] edges = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double level = height;
        double previous = 0.0;
        int found = 0;
        for (double distance = LOOKAHEAD_SPACING; distance <= STAIR_REACH + 1.0e-9 && found < 2;
             distance += distance < STAIR_NEAR_REACH - 1.0e-9 ? LOOKAHEAD_SPACING : STAIR_FAR_SPACING) {
            double ground = probeGround(x, z, dirX, dirZ, distance, from, bottom);
            if (Double.isNaN(ground)) {
                break;
            }
            if (Math.abs(ground - level) > STAIR_EPSILON) {
                double near = previous;
                double far = distance;
                for (int i = 0; i < STAIR_EDGE_BISECTIONS; i++) {
                    double middle = (near + far) / 2.0;
                    double g = probeGround(x, z, dirX, dirZ, middle, from, bottom);
                    if (!Double.isNaN(g) && Math.abs(g - level) <= STAIR_EPSILON) {
                        near = middle;
                    } else {
                        far = middle;
                    }
                }
                // 縁は段差（不連続）でなければならない。なめらかな斜面は詰めても差が縮むので段とは見ない
                double nearGround = probeGround(x, z, dirX, dirZ, near, from, bottom);
                double farGround = probeGround(x, z, dirX, dirZ, far, from, bottom);
                if (Double.isNaN(nearGround) || Double.isNaN(farGround)
                        || Math.abs(farGround - nearGround) <= STAIR_EPSILON) {
                    break;
                }
                edges[found * 2] = (near + far) / 2.0;
                edges[found * 2 + 1] = ground;
                found++;
                level = ground;
            }
            previous = distance;
        }
        return edges;
    }

    /**
     * {@code high} の側に高い段があるときの、段の角を結んだ線の高さ。斜面の長さ（段の幅）は、
     * 反対側の縁（こちらの段の始まり）か、高い側の次の縁（向こうの段の幅）から取る。
     */
    private static double stairSide(double height, double[] high, double[] low) {
        double edge = high[0];
        double top = high[1];
        double rise = top - height;
        if (Double.isNaN(edge) || rise <= STAIR_EPSILON || rise > STAIR_STEP_MAX) {
            return Double.NEGATIVE_INFINITY;
        }
        double run;
        double ground;
        if (!Double.isNaN(low[0]) && low[1] < height - STAIR_EPSILON) {
            // こちらの段は反対側の縁から始まる。その角（高さ height）から高い段の角まで
            run = edge + low[0];
            ground = height + rise * low[0] / run;
        } else if (!Double.isNaN(high[2]) && high[3] > top + STAIR_EPSILON) {
            // 階段の端の段。向こうの段の幅で同じ傾きの斜面を延ばす
            run = high[2] - edge;
            ground = top - rise / run * edge;
        } else {
            return Double.NEGATIVE_INFINITY;
        }
        if (rise / run > STAIR_SLOPE_MAX) {
            return Double.NEGATIVE_INFINITY;
        }
        return Math.max(height, ground);
    }

    /** 真下の地面の高さ。当たらない・起点が埋まっているときは NaN。 */
    private double probeGround(double x, double z, double dirX, double dirZ, double distance,
                               double from, double bottom) {
        double px = x + dirX * distance;
        double pz = z + dirZ * distance;
        BlockHitResult hit = level().clip(new ClipContext(
                new Vec3(px, from, pz),
                new Vec3(px, bottom, pz),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this));
        if (hit.getType() == HitResult.Type.MISS || hit.isInside()) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }

    /**
     * その路面で物理へ渡すグリップの倍率。路面（と濡れ具合）に、履いているタイヤのその路面での倍率を掛ける。
     *
     * <p><b>タイヤの路面ごとの得意・不得意はここで掛ける</b>（物理コアは路面の種類を知らない）。
     * ノーマルタイヤはどの路面でも 1 なので、路面の値がそのまま渡る。</p>
     */
    private double tireGrip(RoadSurface surface) {
        return surface.gripScale(wetness) * getSpec().tire().gripOn(surface.surfaceName());
    }

    /** その路面で物理へ渡す転がり抵抗の倍率。路面に、タイヤのその路面での倍率を掛ける。 */
    private double tireRolling(RoadSurface surface) {
        return surface.rollingScale() * getSpec().tire().rollingOn(surface.surfaceName());
    }

    private void setGroundHeight(Wheel wheel, double height, double rise) {
        int index = wheel.ordinal();
        double previous = previousGroundHeight[index];
        setGroundHeight(wheel, height, 1.0, 1.0, previous > Double.NEGATIVE_INFINITY ? previous : height,
                Double.NEGATIVE_INFINITY, rise);
    }

    /**
     * 接地面の高さを物理コアへ渡す。<b>上がる向きだけ 1 回あたりの量を制限する</b>。
     *
     * <p>段差の天面をいきなり渡すと、その食い込みがまるごとバンプストップに入って
     * 車が跳ね上がる。少しずつ上げれば、サスが縮みながら車体を持ち上げていく。
     * 下がる向き（崖から落ちる、着地する）は制限しない——遅らせると宙に浮く。</p>
     *
     * <p><b>地面を見失っていた輪（穴の上で宙に浮いていた輪）が再び見つけたときも制限する。</b>
     * そのときは前ティックの地面が無いので、いまのタイヤ下端から上げる。制限しないと、穴から
     * 出た瞬間に地面が 1m 一気に上がってバンプストップに乗り、その輪に停車時の 8 倍の荷重が
     * 跳ねた（1 輪を 1 ブロックの穴へ落として抜けるとき）。</p>
     *
     * <p><b>低い段（{@link CarBody#STEP_LIFT} 以内）は制限しない。</b>先読み（{@link #lookAhead}）の
     * 斜面で縁の手前から上げてあるので、跳ね上がらない。制限するのは 1 ブロック以上の段だけ。</p>
     *
     * @param base  この輪のいまの地面（地面を見失っていた輪はいまのタイヤ下端）。段の高さをここから測る
     * @param ahead 先読みの斜面の高さ。無ければ負の無限大
     * @param rise  この 1 回で上げてよい量 [m]（{@link #GROUND_RISE_PER_TICK} を刻みで割り振ったもの）
     */
    private void setGroundHeight(Wheel wheel, double height, double gripScale, double rollingScale,
                                 double base, double ahead, double rise) {
        int index = wheel.ordinal();
        double limited;
        if (height - base <= CarBody.STEP_LIFT) {
            // 低い段は先読みの斜面で登っているので、上がる速さは制限しない
            limited = Math.max(height, ahead);
        } else {
            limited = Math.min(height, Math.max(ahead, base + rise));
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

    /** 1 輪ぶんの路面を詰めるビット数。{@link RoadSurface} が 16 種を超えたら広げること。 */
    private static final int SURFACE_BITS = 4;
    /**
     * 1 輪ぶんの 4 ビットのうち、最上位を「その輪が浮いている」に使う。路面は 6 種なので下の 3 ビットで足りる。
     * <b>立っていると浮いている</b>（0 を接地にしておけば、まだ何も届いていない車は接地扱いになる）。
     */
    private static final int AIRBORNE_BIT = 1 << (SURFACE_BITS - 1);
    private static final int SURFACE_MASK = AIRBORNE_BIT - 1;

    /** 4 輪の路面を 1 つの整数へ詰める。{@link CarStatePacket} は 2 バイトで運ぶ。 */
    private int packSurfaces() {
        int packed = 0;
        for (int i = 0; i < surfaces.length; i++) {
            int bits = surfaces[i].ordinal();
            if (state.wheelLoad[i] <= 0.0) {
                bits |= AIRBORNE_BIT;
            }
            packed |= bits << (i * SURFACE_BITS);
        }
        return packed;
    }

    /** 4 輪とも同じ路面を詰めた値。 */
    private static int packSurfaces(RoadSurface surface) {
        int packed = 0;
        for (int i = 0; i < Wheel.COUNT; i++) {
            packed |= surface.ordinal() << (i * SURFACE_BITS);
        }
        return packed;
    }

    /**
     * その輪が今どの路面に乗っているか。
     *
     * <p>煙・土煙・タイヤ痕のように<b>輪の足元で起きること</b>はこちらを使う。
     * 手元で解いていないときは同期された値。</p>
     */
    public RoadSurface getRenderSurface(Wheel wheel) {
        if (hasLocalState()) {
            return surfaces[wheel.ordinal()];
        }
        int packed = entityData.get(DATA_SURFACE);
        return RoadSurface.byIndex((packed >> (wheel.ordinal() * SURFACE_BITS)) & SURFACE_MASK);
    }

    /**
     * その輪が路面から浮いているか。
     *
     * <p>車輪の回転と車体の滑り角から組み立てる値（{@link #getRenderSlideSpeed}・{@link #getRenderScrubSpeed}）は、
     * 空中でも計算できてしまう。<b>ジャンプ中にスキール音と砂利の音が鳴り続けた</b>ので、これで止める。
     * 手元で解いていないときは、路面と一緒に配られたビットを見る。</p>
     */
    public boolean isRenderWheelAirborne(Wheel wheel) {
        if (hasLocalState()) {
            return state.wheelLoad[wheel.ordinal()] <= 0.0;
        }
        int packed = entityData.get(DATA_SURFACE);
        return ((packed >> (wheel.ordinal() * SURFACE_BITS)) & AIRBORNE_BIT) != 0;
    }

    /**
     * 表示と音に使う、車全体で 1 つの路面。いちばんグリップの低い輪のもの。
     *
     * <p>ロードノイズや HUD のように車に 1 つしかないもの向け。<b>輪の足元で起きることに
     * 使ってはいけない</b>（{@link #getRenderSurface(Wheel)}）。</p>
     */
    public RoadSurface getRenderSurface() {
        RoadSurface worst = getRenderSurface(Wheel.VALUES[0]);
        for (Wheel wheel : Wheel.VALUES) {
            RoadSurface surface = getRenderSurface(wheel);
            if (surface.gripScale() < worst.gripScale()) {
                worst = surface;
            }
        }
        return worst;
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
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = Math.max(steps, 1);
    }

    // ------------------------------------------------------------------
    // 外部から状態を差し込む口
    // ------------------------------------------------------------------

    /** このティックに送った状態。運転していなければ null。 */
    @Nullable
    public CarStatePacket lastSentState() {
        return lastSentState;
    }

    public boolean isGhost() {
        return ghost;
    }

    /**
     * 記録を再生する車にする。<b>クライアントでだけ、ワールドへ足す前に呼ぶ。</b>
     *
     * <p>車種・諸元・装着は記録したときのもの。いま乗っている車とは関係なく、
     * 記録した車の姿で走る。</p>
     */
    public void becomeGhost(ResourceLocation carId, CarSpec spec, PartFitment parts) {
        this.ghost = true;
        this.carId = carId;
        this.spec = spec;
        this.parts = parts;
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
        updateRenderSnapshot();
    }

    /**
     * 記録の 1 ティックぶんを流し込む。他人の車がサーバーから受け取るのと同じ道
     * （位置は {@link #lerpTo}、姿勢と車輪は同期値）なので、描き方も補間も同じになる。
     */
    public void playGhostFrame(double x, double y, double z, CarStatePacket frame) {
        lerpTo(x, y, z, frame.yaw(), 0.0F, 3);
        applyDriverState(frame.steerAngle(), frame.speed(), frame.yaw(), frame.pitch(), frame.roll(),
                frame.suspension(), frame.wheelSpeed(), frame.friction(), frame.rpm(), frame.gear(),
                frame.throttle(), frame.slipAngle(), frame.surface(), frame.lights(), frame.boost());
    }

    /**
     * 運転者がいまハンドルを操作しているか（A/D・スティック）。入力を持っているのは運転しているクライアントだけなので、
     * それ以外（同乗者・他人の車・サーバー）では null。ハンドルを握る手の見た目に使う（{@code SteeringHands}）。
     */
    @Nullable
    public Boolean steeringInputHeld() {
        if (!hasLocalState()) {
            return null;
        }
        return driverInput.analog()
                ? Math.abs(driverInput.steerAxis()) > CarInput.AXIS_THRESHOLD
                : driverInput.left() || driverInput.right();
    }

    /** 運転クライアントが毎ティック呼ぶ。次の {@link #tick()} で使われる。 */
    public void setDriverInput(CarInput input) {
        this.driverInput = input;
    }

    /**
     * ヘッドライトの状態。<b>車が持つ。</b>
     *
     * <p>運転クライアントが入れ、サーバーは届いた状態を覚えておく（{@link #applyDriverState}）。
     * 覚えておかないと、降りた瞬間に物理を引き継いだサーバーが「消えている」を配り直して
     * <b>降りるとライトが消える</b>。保存もするので、点けたまま置いた車はリログしても点いている。</p>
     */
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

    /**
     * 配られてきたヘッドライトの状態。乗り込んだ運転者がこれを<b>引き継ぐ</b>
     * （点けたまま降りた車は、乗り直しても点いている）。
     */
    public boolean isHeadlightOnSynced() {
        return (entityData.get(DATA_LIGHTS) & LIGHT_HEAD) != 0;
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
                                byte lights, float boost) {
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
        entityData.set(DATA_BOOST, Math.max(0.0F, Math.min(1.0F, boost)));
        if (!level().isClientSide) {
            // 運転者が入れた状態を覚えておく。降りた後もサーバーがこれを配り続ける
            headlightsOn = (lights & LIGHT_HEAD) != 0;
        }
    }

    // ------------------------------------------------------------------
    // 描画向けの取得
    // ------------------------------------------------------------------

    /** 滑りが「撫でる速さ」へ上乗せされる強さ。{@link #getRenderScrubSpeed(Wheel)} */
    private static final double SCRUB_SLIP_GAIN = 1.2;

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
        double reference = Math.max(Math.abs(rolling), CarPhysics.SLIP_REFERENCE_SPEED);
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

    /**
     * 接地面が路面を撫でる速さ [m/s]。
     *
     * <p>転がっているだけなら車速そのもの。縦に滑れば（ホイールスピン・ロック）その滑り率ぶん、
     * 横を向いていれば車体スリップ角ぶんが上乗せされる。<b>タイヤが路面をどれだけ擦っているか</b>
     * という 1 つの量で、未舗装で舞う砂塵（{@code CarDust}）と砂利の音（{@code CarSound}）の
     * 両方がこれで決まる。<b>2 か所で別々に組み立てないこと</b>——片方だけ直すと、
     * 見えている土煙と聞こえている音が食い違う。</p>
     *
     * <p>縦の滑り率は配ってある車輪の角速度と車速から組み直され、スリップ角はそのまま
     * 配られているので、<b>他人の車でも読める</b>。</p>
     */
    public double getRenderScrubSpeed(Wheel wheel) {
        if (isRenderWheelAirborne(wheel)) {
            return 0.0;
        }
        double speed = Math.abs(getRenderSpeed());
        double slip = Math.min(1.0, Math.abs(getRenderSlipRatio(wheel)));
        double sideways = Math.abs(Math.sin(getRenderSlipAngleDegrees() * Mth.DEG_TO_RAD));
        return speed * (1.0 + SCRUB_SLIP_GAIN * (slip + sideways));
    }

    /** 滑る速さの横成分を出すときのドリフト角の上限 [度]。90 度に近づくと tan が発散する。 */
    private static final double SLIDE_MAX_ANGLE = 80.0;

    /**
     * タイヤの接地面が路面の上を滑っている速さ [m/s]。スキール音の大きさに使う。
     *
     * <p>縦（タイヤの表面の速さ − 車速。ホイールスピンで回すほど、ロックさせるほど大きい）と
     * 横（車速 × tan(ドリフト角)）の合成。<b>角度・車速・回転数のどれを上げても大きくなる</b>。
     * 滑り率やスリップ角のままだと、ドリフトでは後輪の空転だけですぐ頭打ちになり、
     * 角度にも速さにも応じなくなる。</p>
     *
     * <p>{@link #getRenderScrubSpeed(Wheel)} とは別物。あちらは転がっているだけでも車速ぶんある
     * （土煙は滑らなくても舞う）が、こちらは<b>滑っていなければ 0</b>。</p>
     */
    public double getRenderSlideSpeed(Wheel wheel) {
        if (isRenderWheelAirborne(wheel)) {
            return 0.0;
        }
        int index = wheel.ordinal();
        double forward = getRenderSpeed();
        double angularVelocity = hasLocalState()
                ? state.wheelAngularVelocity[index]
                : entityData.get(DATA_WHEEL_SPEED.get(index));
        double longitudinal = angularVelocity * spec.wheelRadius() - forward;
        double angle = Math.min(SLIDE_MAX_ANGLE, Math.abs(getRenderSlipAngleDegrees()));
        double lateral = Math.abs(forward) * Math.tan(angle * Mth.DEG_TO_RAD);
        return Math.hypot(longitudinal, lateral);
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

    /**
     * サイドブレーキを引いているか。メーターの灯に使う。
     *
     * <p>操作そのものなので<b>運転している本人にしか分からない</b>（同期していない）。
     * メーターは自分の車にしか出さないので、それで足りる。</p>
     */
    public boolean isHandbrakeOn() {
        return hasLocalState() && driverInput.handbrake();
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
        if (ghost || player.isSecondaryUseActive()) {
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
        // 席は車体に付いているので、車体と一緒に傾ける。ヨーだけで回していた頃は
        // 車がロールしても乗員は水平のまま浮いていて、一人称の視点だけが車体から取り残された
        Vec3 seat = toWorld(seatOffset(passenger).add(0.0, ridingOffset(passenger), 0.0),
                hasLocalState() ? getYRot() : entityData.get(DATA_YAW), getRenderPitch(), getRenderRoll());
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
        boolean passengerSeat = getPassengers().indexOf(passenger) == 1;
        Vec3 seat = null;
        if (level().isClientSide) {
            // クライアントの側でしか通らない（isClientSide）ので、クライアント専用のクラスを直接呼んでよい
            seat = CarSeat.seatOffset(carId, passengerSeat);
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
        return !isRemoved() && !ghost;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !ghost;
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
        if (ghost || other instanceof CarEntity car && car.ghost) {
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
     * <p>乗っている人がいる車はどちらもさせない。座っている人ごと消えることになるため。</p>
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (ghost) {
            return false;
        }
        if (level().isClientSide || isRemoved()) {
            return true;
        }
        if (!(source.getEntity() instanceof Player player) || !getPassengers().isEmpty()) {
            return false;
        }
        if (!player.isShiftKeyDown()) {
            discard();
            return true;
        }
        ItemStack stack = CarSpawnItem.stackFor(this);
        // 手が塞がっていたら足元へ落とす。回収できずに車だけ消えるのが最悪なので
        if (!player.getInventory().add(stack)) {
            spawnAtLocation(stack);
        }
        discard();
        return true;
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
        // 装着している部品。知らない id もそのまま覚えておく（カーパックを入れ直せば戻る）
        if (tag.contains("Parts")) {
            parts = PartFitment.load(tag.getCompound("Parts"));
        }
        // 姿勢。物理を引き継ぐときに takeOverPhysics がここから読む。
        // 保存しないと、ひっくり返ったまま放置した車がチャンクの読み直しで水平に戻る。
        // 単位はラジアン（/summon で横転した車を出すのにも使う）
        entityData.set(DATA_PITCH, tag.getFloat("Pitch"));
        entityData.set(DATA_ROLL, tag.getFloat("Roll"));
        headlightsOn = tag.getBoolean("Headlights");
        entityData.set(DATA_LIGHTS, (byte) (headlightsOn ? LIGHT_HEAD : 0));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("CarId", carId.toString());
        // 諸元を保存しないと、リログやチャンクの読み直しで調整が消えて車高が変わる
        tag.put("Spec", CarSpecCodec.save(spec));
        // 保存しないと、リログやチャンクの読み直しで換装が消えて既定の見た目に戻る
        if (!parts.isEmpty()) {
            tag.put("Parts", parts.save());
        }
        tag.putFloat("Pitch", entityData.get(DATA_PITCH));
        tag.putFloat("Roll", entityData.get(DATA_ROLL));
        tag.putBoolean("Headlights", headlightsOn);
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
        buf.writeResourceLocation(carId);
        CarSpecCodec.write(buf, spec);
        // 装着した部品も。これが無いと、後から見た人には既定のホイールで見える
        parts.write(buf);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        carId = buf.readResourceLocation();
        spec = CarSpecCodec.read(buf);
        parts = PartFitment.read(buf);
    }

}
