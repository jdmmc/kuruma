package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.physics.CarBody;
import com.jdmmc.kurumamod.physics.BlockField;
import com.jdmmc.kurumamod.ground.GroundSensor;
import com.jdmmc.kurumamod.debug.GroundTrace;
import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.client.effect.SparkEmitter;
import org.jetbrains.annotations.Nullable;
import com.jdmmc.kurumamod.physics.CarInput;
import com.jdmmc.kurumamod.util.Mth;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.physics.CarState;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.PartAero;
import com.jdmmc.kurumamod.physics.Tire;
import com.jdmmc.kurumamod.physics.Turbo;

/**
 * 車 1 台ぶんの中身。Minecraft に依存しない。版の {@code CarEntity} が 1 つ持つ。
 *
 * <p>いまは<b>車種・諸元・装着している部品</b>と、そこから組み上げる<b>実効の諸元・実際に付いている部品</b>を持つ。
 * 描画用の値・灯火・物理を回すところは順に版から移してくる（{@code docs/architecture.md}）。</p>
 *
 * <p>ここは値を持つだけで、<b>周りへ配らない。</b>サーバー側で変わったときに配るのは版の {@code CarEntity}
 * （{@code setSpec}・{@code setFitment}）。</p>
 */
public final class CarCore {

    /**
     * 車種の id。
     *
     * <p>諸元は調整画面でいくらでも変わるので、「もとはどの車か」は諸元からは分からない。
     * 見た目（{@code assets/<ns>/vehicles/<path>.json}）を引くのも、名前を出すのも、
     * 調整画面の「戻す」でどこへ戻すかも、すべてこの id が決める。</p>
     */
    private CarId carId = CarTypes.DEFAULT_ID;
    /** 車両諸元。調整画面から差し替えられる。部品の性能は載っていない（{@link #spec()} が載せる）。 */
    private CarSpec baseSpec = CarSpec.DEFAULT;
    /**
     * 装着している部品。<b>車種・諸元と並ぶ 3 つめの状態。</b>
     *
     * <p>諸元と違って<b>サーバーが決める</b>——手元にあるだけの部品を勝手に履けると、
     * 他の人には見えない部品を履いた車が走ることになる（{@code CarPartRequestPacket}）。</p>
     */
    private PartFitment fitment = PartFitment.EMPTY;


    /** 1 ティックの長さ [s]。 */
    private static final double TICK_SECONDS = 0.05;

    /** 1 輪ぶんの路面を詰めるビット数。{@code RoadSurface} が 16 種を超えたら広げること。 */
    public static final int SURFACE_BITS = 4;
    /**
     * 1 輪ぶんの 4 ビットのうち、最上位を「その輪が浮いている」に使う。路面は 6 種なので下の 3 ビットで足りる。
     * <b>立っていると浮いている</b>（0 を接地にしておけば、まだ何も届いていない車は接地扱いになる）。
     */
    public static final int AIRBORNE_BIT = 1 << (SURFACE_BITS - 1);
    public static final int SURFACE_MASK = AIRBORNE_BIT - 1;

    /** 滑りが「撫でる速さ」へ上乗せされる強さ。{@link #getRenderScrubSpeed(Wheel)} */
    private static final double SCRUB_SLIP_GAIN = 1.2;

    /** 滑る速さの横成分を出すときのドリフト角の上限 [度]。90 度に近づくと tan が発散する。 */
    private static final double SLIDE_MAX_ANGLE = 80.0;

    /** ヘッドライト点灯。 */
    public static final int LIGHT_HEAD = 1;
    /** ブレーキランプ点灯。 */
    public static final int LIGHT_BRAKE = 2;
    /** これ以上踏んでいればブレーキランプを点ける。 */
    private static final double BRAKE_LIGHT_THRESHOLD = 0.05;

    /** 運転クライアントから毎ティック差し込まれる入力。サーバーや同乗者側では常に無操作。 */
    private CarInput driverInput = CarInput.NONE;

    /**
     * 自分の画面にだけいる、記録を再生している車（ゴースト）か。
     *
     * <p>ゴーストはサーバーに存在しない。ぶつからず、殴れず、乗れない。音・煙・タイヤ痕・
     * 路面を照らす光も出さない（それぞれの描き手がこれを見て飛ばす）。</p>
     */
    private boolean ghost;
    /** 誰も乗っていないまま経過したティック数。サーバー側だけで数える。 */
    private int abandonedTicks;

    /**
     * ヘッドライトの状態。<b>車が持つ。</b>
     *
     * <p>運転クライアントが入れ、サーバーは届いた状態を覚えておく（{@link #applyDriverState}）。
     * 覚えておかないと、降りた瞬間に物理を引き継いだサーバーが「消えている」を配り直して
     * <b>降りるとライトが消える</b>。保存もするので、点けたまま置いた車はリログしても点いている。</p>
     */
    private boolean headlightsOn;

    /** 1 ティック = 50ms は粗いので、物理はこの回数に分割して解く */
    private static final int PHYSICS_SUBSTEPS = 4;
    /** 接地を探すタイヤの端を、幅の端からどれだけ内へ寄せるか [m]。 */
    private static final double TIRE_EDGE_INSET = 0.03;
    /** 雨に入ってから濡れきるまでの時定数 [s]。 */
    private static final double WET_SECONDS = 3.0;
    /** 雨から出てから乾くまでの時定数 [s]。濡れるより遅い。 */
    private static final double DRY_SECONDS = 20.0;
    /**
     * これ以下の速度差では衝突として扱わない [m/s]。
     *
     * <p>縁石を擦った程度で毎回鳴ると安っぽくなる。クライアント側の
     * {@code ImpactShake.MIN_IMPACT_SPEED} と同じ値だが、<b>ここで弾いておかないと
     * 毎ティック無駄なパケットが飛ぶ</b>ので両方に置いてある。</p>
     */
    public static final double IMPACT_MIN_SPEED = 2.5;
    /**
     * 車体が壁とこれより速く擦れたら火花を出す [m/s]（{@code CarSparks}）。
     * ゆっくり押し付けているだけでは飛ばない。
     */
    public static final double SPARK_MIN_SPEED = SparkEmitter.MIN_SPEED;
    /** F3+B で見えない壁を描く、車の中心からの距離 [m]。 */
    private static final double STAIRCASE_LINE_RANGE = 6.0;

    /** 4 輪の接地面を探すもの。世界を読む口は版の車が持つ（{@link GroundSensor.World}）。 */
    private final GroundSensor ground;
    /** 車体の周りのブロックの当たり判定の箱。物理の 1 ティックの間、使い回す。 */
    private final BlockField blockField = new BlockField();
    /** 路面の濡れ具合 0..1。天候から作るので同期も保存もしない。 */
    private double wetness;
    /**
     * 描画で測ったタイヤの幅（{@link CarBody#tireSpan}）。物理上のタイヤの位置から外向きに {内側の端, 外側の端}。
     * 描いていない車（サーバーが動かす無人の車）は null のままで、{@link CarBody#defaultTireSpan} を使う。
     */
    @Nullable
    private double[] visualTireSpan;

    /** 版の {@code CarEntity}。同期した値・向き・どちらの側で解いているかを聞く。 */
    private final CarHost host;
    /** 物理の状態。運転権を持っている側だけが進める。 */
    private final CarState state = new CarState();

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

    public CarCore(CarHost host) {
        this.host = host;
        this.ground = new GroundSensor(host);
    }

    /** 物理の状態。 */
    public CarState state() {
        return state;
    }

    /** ティックの始めに呼ぶ。タイヤの回転角を 1 つずらして、描画で前のティックとの間を取れるようにする。 */
    public void shiftWheelRoll() {
        System.arraycopy(wheelRoll, 0, wheelRollPrev, 0, wheelRoll.length);
    }

    /** 自分の手元で物理を解いているか（＝同期を待たずに自前の値を描画に使えるか） */
    private boolean hasLocalState() {
        return host.simulatedOnThisClient();
    }

    private PartFitment resolvedFitment = PartFitment.EMPTY;
    private PartFitment resolvedFrom;
    private CarType resolvedType;
    private int resolvedGeneration = -1;

    private CarSpec effectiveSpec = CarSpec.DEFAULT;
    private CarSpec effectiveBase;
    private PartFitment effectiveParts;
    private int effectiveGeneration = -1;

    /** 車種の id。見た目の定義を引くのと、名前を出すのに使う。 */
    public CarId carId() {
        return carId;
    }

    public void setCarId(CarId carId) {
        this.carId = carId;
    }

    /** 調整画面の諸元（部品の性能を載せる前）。保存・同期するのはこちら。 */
    public CarSpec baseSpec() {
        return baseSpec;
    }

    public void setBaseSpec(CarSpec spec) {
        this.baseSpec = spec;
    }

    /** 装着している部品（初期装備を足す前）。保存・通信・換装画面の「装着中」の印はこちら。 */
    public PartFitment fitment() {
        return fitment;
    }

    public void setFitment(PartFitment fitment) {
        this.fitment = fitment;
    }

    /** 停車時のシャシー基準面の地上高 [m]。スポーンさせるときにこの高さぶん持ち上げる。 */
    public double staticRideHeight() {
        return baseSpec.staticRideHeight();
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
    public CarSpec spec() {
        PartFitment fitted = effectiveFitment();
        if (effectiveBase != baseSpec || effectiveParts != fitted || effectiveGeneration != CarParts.generation()) {
            effectiveSpec = baseSpec.toBuilder().turbo(turboOf(fitted)).tire(tireOf(fitted))
                    .partAero(totalAero(fitted))
                    .build();
            effectiveBase = baseSpec;
            effectiveParts = fitted;
            effectiveGeneration = CarParts.generation();
        }
        return effectiveSpec;
    }

    /**
     * 初期装備（車種の {@code default_parts}）を足した、<b>実際に付いている状態</b>。
     * 描画・諸元・音はこちらを読む。保存・通信・換装画面の「装着中」の印は {@link #fitment()}（元の状態）。
     *
     * <p>装着状態と車種（読み直すと別のインスタンスになる）が変わったときだけ組み直す。</p>
     */
    public PartFitment effectiveFitment() {
        CarType type = CarTypes.get(carId);
        // 部品の一覧が変わると「知らない部品」も変わる（カーパックの出し入れ・ログイン直後に一覧が届いたとき）
        if (resolvedFrom != fitment || resolvedType != type || resolvedGeneration != CarParts.generation()) {
            resolvedFitment = fitment.resolve(type.defaultPartIds());
            resolvedFrom = fitment;
            resolvedType = type;
            resolvedGeneration = CarParts.generation();
        }
        return resolvedFitment;
    }

    /** ターボが付いているか。 */
    public boolean hasTurbo() {
        return spec().turbo().present();
    }

    /**
     * ブローオフバルブが付いているか（<b>音だけ</b>。ターボが無ければ鳴らない）。
     * 知らない部品（カーパックを外した）なら付いていないものとする。
     */
    public boolean hasBlowoff() {
        CarId id = effectiveFitment().getPart(PartSlot.BLOWOFF);
        return id != null && CarParts.get(id) != null && hasTurbo();
    }

    /** 装着しているターボの性能。付いていない・知らない部品なら無し。 */
    private static Turbo turboOf(PartFitment parts) {
        CarId id = parts.getPart(PartSlot.TURBO);
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.turbo() == null ? Turbo.NONE : part.turbo();
    }

    /** 履いているタイヤの性能。付いていない・知らない部品ならノーマル。 */
    private static Tire tireOf(PartFitment parts) {
        CarId id = parts.getPart(PartSlot.TIRE);
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
        CarId id = parts.getPart(slot);
        CarPart part = id == null ? null : CarParts.get(id);
        if (part == null || part.aero() == null) {
            return PartAero.NONE;
        }
        Double angle = parts.getAngle(slot);
        return part.aero().at(angle != null ? angle : part.aero().defaultAngle());
    }

    // ------------------------------------------------------------------
    // 描画向けの値（ティックの境目の姿勢・補間・タイヤの回転・同期値からの組み立て）
    // ------------------------------------------------------------------

    /**
     * 描画用の値をティックの境目で 1 つずらす。
     *
     * <p>手元で解いていてもいなくても同じように補間できるよう、<b>読み出し元の違いは
     * ここで吸収する</b>。</p>
     */
    public void updateRenderSnapshot() {
        boolean local = hasLocalState();
        prevRenderYaw = renderYaw;
        prevRenderPitch = renderPitch;
        prevRenderRoll = renderRoll;
        prevRenderSteer = renderSteer;
        System.arraycopy(renderSuspension, 0, prevRenderSuspension, 0, Wheel.COUNT);

        if (local) {
            renderYaw = host.vanillaYaw();
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
        if (CarPose.isOtherRepresentationCloser(prevRenderYaw, prevRenderPitch, prevRenderRoll,
                renderYaw, renderPitch, renderRoll)) {
            prevRenderYaw += 180.0F;
            prevRenderPitch = CarPose.otherPitch(prevRenderPitch);
            prevRenderRoll = CarPose.otherRoll(prevRenderRoll);
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
        float yaw = host.synced().yaw();
        float pitch = host.synced().pitch();
        float roll = host.synced().roll();
        float steer = host.synced().steerAngle();
        boolean changed = !renderSnapshotReady || yaw != targetYaw || pitch != targetPitch
                || roll != targetRoll || steer != targetSteer;
        for (int i = 0; i < Wheel.COUNT; i++) {
            double length = host.synced().suspension(i);
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
        if (CarPose.isOtherRepresentationCloser(renderYaw, renderPitch, renderRoll, yaw, pitch, roll)) {
            renderYaw += 180.0F;
            renderPitch = CarPose.otherPitch(renderPitch);
            renderRoll = CarPose.otherRoll(renderRoll);
        }
        float step = 1.0F / poseLerpSteps;
        renderYaw += Mth.wrapDegrees(yaw - renderYaw) * step;
        renderPitch = CarPose.wrapRadians(renderPitch + CarPose.wrapRadians(pitch - renderPitch) * step);
        renderRoll = CarPose.wrapRadians(renderRoll + CarPose.wrapRadians(roll - renderRoll) * step);
        renderSteer += (steer - renderSteer) * step;
        for (int i = 0; i < Wheel.COUNT; i++) {
            renderSuspension[i] += (targetSuspension[i] - renderSuspension[i]) * step;
        }
        poseLerpSteps--;
    }

    /**
     * タイヤの転がり角を進める。
     *
     * <p>手元で物理を解いているなら車輪の実際の角速度を使う。空転すれば路面より速く、
     * ロックすれば止まって見える。そうでなければ車速から均等に回す。</p>
     */
    public void updateWheelRoll() {
        boolean local = host.simulatedHere();
        for (Wheel wheel : Wheel.VALUES) {
            int index = wheel.ordinal();
            // 手元で解いていないときは同期された角速度を使う。
            // 車速から作ると空転もロックも見えなくなる
            double angularVelocity = local
                    ? state.wheelAngularVelocity[index]
                    : host.synced().wheelSpeed(index);
            wheelRoll[index] += (float) (angularVelocity * TICK_SECONDS);
        }
    }

    /** 描画に使う切れ角 [rad]。運転中は往復のラグを避けて手元の値を使う。 */
    public float getRenderSteerAngle() {
        return hasLocalState() ? (float) state.steerAngle : host.synced().steerAngle();
    }

    /** 描画に使う切れ角 [rad]。ティックの間を補間する。 */
    public float getRenderSteerAngle(float partialTick) {
        return Mth.lerp(partialTick, prevRenderSteer, renderSteer);
    }

    /**
     * <b>描画専用</b>の前輪の切れ角 [rad]。
     *
     * <p>大きく流すと自動カウンターがフルロックまで当たる。そこから逆へ切ると、車体に対する
     * 角度は最大切れ角の範囲内（35 度）で正しいのに、<b>進行方向に対しては垂直を超える</b>
     * （車体スリップ角 -72 度＋切れ角 +35 度 = 進行方向から 107 度）。タイヤが横を向いて
     * 見えるのはこれが理由。</p>
     *
     * <p>そこで<b>カウンターを当てている角度を基準にして、そこから {@code visualSlipLimit} までに
     * 収める</b>（{@link #counterSteerAngle}）。基準は物理の自動カウンターが当てる角度そのものなので、
     * 舵を放している間は実際の舵が基準へ戻っていくだけで、見た目は何も変わらない。カウンター中に
     * 内側へ切れば、カウンターの角度から上限ぶんだけ内側へ切れて見える。</p>
     *
     * <p>以前は車体のスリップ角を進行方向とし、180 度ごとに現れる線から寄せる先を選んでいた。
     * 物理と基準が違うので、<b>カウンターが当たっているのに逆向きやまっすぐに見え</b>、選ぶ線が
     * 切り替わる瞬間に 65 度跳んだ。</p>
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
        // 計算は core の CarPose。基準のカウンター角は手元で解いているかで組み立て方が違う
        return CarPose.visualSteerAngle(baseSpec, steer, renderGroundSpeed(), counterSteerAngle());
    }

    /** 自動カウンターが当てる角度 [rad]（{@link CarPose#counterSteerLocal}・{@link CarPose#counterSteerRemote}）。 */
    private double counterSteerAngle() {
        double forward = getRenderSpeed();
        boolean reversing = forward < 0.0 && getRenderGear() == -1;
        if (hasLocalState()) {
            return CarPose.counterSteerLocal(baseSpec, forward, state.lateralSpeed, state.yawRate, reversing);
        }
        double slip = getRenderSlipAngleDegrees() * Mth.DEG_TO_RAD;
        double yawRate = Mth.wrapDegrees(renderYaw - prevRenderYaw) * Mth.DEG_TO_RAD / TICK_SECONDS;
        return CarPose.counterSteerRemote(baseSpec, forward, slip, yawRate, reversing);
    }

    /** 描画に使う、地面に対する水平の速さ [m/s]（前後と横の合成）。 */
    private double renderGroundSpeed() {
        if (hasLocalState()) {
            return Math.hypot(state.forwardSpeed, state.lateralSpeed);
        }
        return CarPose.groundSpeedFromSlip(getRenderSpeed(), getRenderSlipAngleDegrees());
    }

    /** 描画に使う車速 [m/s]。 */
    public float getRenderSpeed() {
        return host.simulatedHere() ? (float) state.forwardSpeed : host.synced().speed();
    }

    /** 描画に使うピッチ角 [rad]。正で鼻上げ。 */
    public float getRenderPitch() {
        return hasLocalState() ? (float) state.pitch : host.synced().pitch();
    }

    /** 描画に使うピッチ角 [rad]。ティックの間を補間する。 */
    public float getRenderPitch(float partialTick) {
        return lerpRadians(partialTick, prevRenderPitch, renderPitch);
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

    /** 描画に使うロール角 [rad]。ティックの間を補間する。 */
    public float getRenderRoll(float partialTick) {
        return lerpRadians(partialTick, prevRenderRoll, renderRoll);
    }

    /** 描画に使うロール角 [rad]。正で右下がり。 */
    public float getRenderRoll() {
        return hasLocalState() ? (float) state.roll : host.synced().roll();
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

    /** 描画に使うサスペンション長 [m]。ティックの間を補間する。 */
    public double getRenderSuspensionLength(Wheel wheel, float partialTick) {
        int index = wheel.ordinal();
        return Mth.lerp(partialTick, prevRenderSuspension[index], renderSuspension[index]);
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
                : host.synced().suspension(wheel.ordinal());
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
        double rolling = host.synced().speed();
        double reference = Math.max(Math.abs(rolling), CarPhysics.SLIP_REFERENCE_SPEED);
        double surfaceSpeed = host.synced().wheelSpeed(wheel.ordinal()) * baseSpec.wheelRadius();
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
                : host.synced().friction(wheel.ordinal());
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
                : host.synced().wheelSpeed(index);
        double longitudinal = angularVelocity * baseSpec.wheelRadius() - forward;
        double angle = Math.min(SLIDE_MAX_ANGLE, Math.abs(getRenderSlipAngleDegrees()));
        double lateral = Math.abs(forward) * Math.tan(angle * Mth.DEG_TO_RAD);
        return Math.hypot(longitudinal, lateral);
    }

    /** 音に使うアクセルの踏み込み量 0..1。 */
    public double getRenderThrottle() {
        return hasLocalState() ? state.throttle : host.synced().throttle();
    }

    /** 表示に使う接地荷重 [N]。手元で解いていないときは停車時の値。 */
    public double getRenderWheelLoad(Wheel wheel) {
        return hasLocalState() ? state.wheelLoad[wheel.ordinal()] : baseSpec.staticWheelLoad(wheel);
    }

    /** 表示に使う横加速度 [m/s^2]。 */
    public double getRenderLateralAcceleration() {
        return hasLocalState() ? state.lateralAcceleration : 0.0;
    }

    /** 表示に使うエンジン回転数 [rpm]。 */
    public double getRenderRpm() {
        return hasLocalState() ? state.engineRpm : host.synced().rpm();
    }

    /** トラクションコントロールが介入しているか。アクセルを絞っているときと、空転輪にブレーキを掛けているとき。 */
    public boolean isTractionControlActive() {
        if (!hasLocalState()) {
            return false;
        }
        if (state.tractionControlThrottle < 0.99) {
            return true;
        }
        for (double brake : state.tractionBrake) {
            if (brake > 1.0) {
                return true;
            }
        }
        return false;
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
        return hasLocalState() ? state.gear : host.synced().gear();
    }

    /** 表示に使う車体スリップ角（ドリフト角） [度]。正なら右へ滑っている。 */
    public double getRenderSlipAngleDegrees() {
        return hasLocalState() ? state.slipAngle() * Mth.RAD_TO_DEG : host.synced().slipAngle();
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
        int packed = host.synced().surface();
        return ((packed >> (wheel.ordinal() * SURFACE_BITS)) & AIRBORNE_BIT) != 0;
    }

    /** ワールド座標での速度の X 成分 [m/s]。手元で解いていない車は同期された値から組み立てる。 */
    public double worldVelocityX() {
        if (hasLocalState()) {
            return state.velocityX();
        }
        return remoteVelocity()[0];
    }

    /** ワールド座標での速度の Z 成分 [m/s]。 */
    public double worldVelocityZ() {
        if (hasLocalState()) {
            return state.velocityZ();
        }
        return remoteVelocity()[1];
    }

    /** 同期された値から組み立てたワールド座標の速度 {x, z}（{@link CarPose#remoteVelocity}）。 */
    private double[] remoteVelocity() {
        return CarPose.remoteVelocity(host.synced().yaw(), host.synced().speed(), host.synced().slipAngle());
    }

    /** 同期されたヨー角 [rad]。 */
    public double remoteYaw() {
        return host.synced().yaw() * Mth.DEG_TO_RAD;
    }

    /** ブースト 0..1。手元で解いていないときは同期された値。 */
    public double getRenderBoost() {
        return hasLocalState() ? state.boost : host.synced().boost();
    }

    // ------------------------------------------------------------------
    // 灯火・運転入力・ゴースト・放置の寿命
    // ------------------------------------------------------------------

    /** ヘッドライトの切替。運転クライアントから呼ぶ。 */
    public void setHeadlights(boolean on) {
        headlightsOn = on;
    }

    /** 運転クライアント（または無人時のサーバー）が配る灯火の状態。 */
    public byte driverLights() {
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
        return (host.synced().lights() & LIGHT_HEAD) != 0;
    }

    /** 描画に使う灯火の状態。手元で解いていないときは同期された値。 */
    public byte getRenderLights() {
        return hasLocalState() ? driverLights() : host.synced().lights();
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
     * サイドブレーキを引いているか。メーターの灯に使う。
     *
     * <p>操作そのものなので<b>運転している本人にしか分からない</b>（同期していない）。
     * メーターは自分の車にしか出さないので、それで足りる。</p>
     */
    public boolean isHandbrakeOn() {
        return hasLocalState() && driverInput.handbrake();
    }

    public boolean isGhost() {
        return ghost;
    }

    /** ヘッドライトを点けているか（運転者が入れた状態。保存もする）。 */
    public boolean headlightsOn() {
        return headlightsOn;
    }

    /** 運転クライアントから差し込まれた入力。 */
    public CarInput driverInput() {
        return driverInput;
    }

    /** ゴーストにする（戻せない）。重力を切るのは版の {@code CarEntity#becomeGhost}。 */
    public void markGhost() {
        ghost = true;
    }

    /**
     * 放置された車を消すか（サーバー側で毎ティック呼ぶ）。
     *
     * <p>マルチプレイでは乗り捨てが溜まっていく一方で、無人の車もサーバーが毎ティック
     * 物理を解いている。設定した時間だけ誰も乗らなければ消す（0 で無効）。消すのは版。</p>
     *
     * @param lifetimeSeconds 設定の寿命 [秒]。0 以下なら消さない
     * @param occupied        誰かが乗っているか
     * @return 消すなら true
     */
    public boolean tickAbandoned(int lifetimeSeconds, boolean occupied) {
        if (lifetimeSeconds <= 0 || occupied) {
            abandonedTicks = 0;
            return false;
        }
        return ++abandonedTicks > lifetimeSeconds * 20;
    }

    // ------------------------------------------------------------------
    // 物理を回す（運転クライアント、または無人時のサーバーだけが呼ぶ）
    // ------------------------------------------------------------------

    /** 各輪へ渡した地面の少し前までの記録（F3+B で描く）。 */
    public GroundTrace groundTrace() {
        return ground.trace();
    }

    /**
     * 運転権を得た（あるいはサーバーが無人の車を引き継いだ）ときに、
     * 現在のエンティティの状態から物理の状態を作り直す。
     */
    public void takeOverPhysics() {
        // 向きは描いているのと同じ値から取る。クライアントの getYRot() はバニラの位置同期
        // （1.4 度刻み・3 ティックかけて寄せる）を補間した途中の値で、ピッチ・ロール（同期値）とも
        // 揃っていないので、乗った瞬間に車が水平に回った。サーバーの getYRot() は運転者の移動
        // パケットそのもので、最初のティックはまだ同期値が無いので getYRot() のまま
        float yaw = host.clientSide() ? host.synced().yaw() : host.vanillaYaw();
        host.setVanillaYaw(yaw);
        state.yaw = yaw * Mth.DEG_TO_RAD;
        state.forwardSpeed = host.synced().speed();
        state.lateralSpeed = 0.0;
        state.yawRate = 0.0;
        state.height = host.posY();
        state.verticalSpeed = 0.0;
        state.pitch = host.synced().pitch();
        state.roll = host.synced().roll();
        state.pitchRate = 0.0;
        state.rollRate = 0.0;
        // 車輪の回転を車速に合わせておく。0 のままだと乗った瞬間に
        // 「全輪ロック」と判定されて急制動がかかる
        double angularVelocity = state.forwardSpeed / baseSpec.wheelRadius();
        Arrays.fill(state.wheelAngularVelocity, angularVelocity);
        Arrays.fill(state.wheelSlipRatio, 0.0);
        // 段差の立ち上がり制限は前ティックの値を見るので、引き継いだ時点では捨てる
        ground.forgetHeights();
    }

    /**
     * 物理を 1 ティック解いて実際に動かす。運転クライアント、または無人時のサーバーでのみ呼ぶ。
     * 無人の車をサーバーが動かしたときに結果を同期項目へ写すのは、呼んだ版の側。
     */
    public void physicsTick(CarInput input) {
        updateWetness();

        // 車体（CarBody）とブロックの当たりは物理の 1 ステップごとに解き、位置もここで積む。
        // 車体は硬い箱で、ブロックと交わらない。当たった点に力積を掛けるので、当たったところを
        // 支点に車が回る（腹が乗れば亀、転がれば屋根の角が当たる）
        CarSpec carSpec = spec();
        double[] span = tireSpan();
        CarBody body = CarBody.of(carSpec, bodyShape()).tireSpan(span[0], span[1]).virtualWalls(ClientConfig.virtualWalls);
        double x = host.posX();
        double z = host.posZ();
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
            ground.update(state, baseSpec, spec(), wetness, x, z, dt);
            // 変速の要求は押した瞬間の 1 回なので、最初の刻みにだけ渡す
            CarPhysics.step(carSpec, state, ground.contact(), dt, i == 0 ? input : input.withoutShift());
            CarBody.Result hit = body.solve(carSpec, state, ground.contact(), x, z, blockField, dt);
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

        host.setVanillaYaw((float) (state.yaw * Mth.RAD_TO_DEG));
        host.wrapVanillaRotation();

        // 車同士の当たりは、車同士用の箱（CarHull#body）で解く。相手が車なら運動量のやりとりもそこで
        CarHost.Move move = host.moveWithCars(x - host.posX(), state.height - host.posY(), z - host.posZ());
        host.setCollisions(wall || move.horizontal(), floor);
        // 衝撃は相手へ近づいていた速さ（当たっていなければ 0）
        impact = Math.max(impact, move.closing());
        host.reportImpact(impact);
        float[][] carSparks = move.sparks();
        if (carSparks != null) {
            // 車同士でぶつかったティックは、そちらを優先する（T ボーンなど、いちばん派手に散る場面）
            host.reportScrape(carSparks[0], carSparks[1], carSparks[2], carSparks[3][0]);
        } else if (scrapeOffset != null
                && (scrapeSpeed >= SPARK_MIN_SPEED || terrainImpact >= IMPACT_MIN_SPEED)) {
            host.reportScrape(scrapeOffset, scrapeNormal, scrapeVelocity,
                    terrainImpact >= IMPACT_MIN_SPEED ? (float) terrainImpact : 0.0F);
        }
        state.height = host.posY();
        unbury(body);
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
     * <p>埋まったかどうかと持ち上げる量は {@link GroundSensor#buriedLift}。傾きはそのまま
     * （起こすのは {@code CarPhysics} の起き上がり補助）。落ちる速さは捨てる。</p>
     */
    private void unbury(CarBody body) {
        double x = host.posX();
        double y = host.posY();
        double z = host.posZ();
        double lift = ground.buriedLift(state, body.corners(), CarBody.axes(state.yaw, state.pitch, state.roll), x, y, z);
        if (lift <= 0.0) {
            return;
        }
        host.placeAt(x, y + lift, z);
        state.height = host.posY();
        state.verticalSpeed = Math.max(0.0, state.verticalSpeed);
        // 地面の高さの記憶は埋まっていたときのもの。上がる速さの制限が古い値から効かないよう捨てる
        ground.forgetHeights();
    }

    /**
     * 車体の大きさを決める諸元。<b>車種の元の諸元</b>（調整画面の値を含まない）。
     *
     * <p>調整画面でタイヤ半径・ホイールベース・トレッドを動かしても、見た目の車体は伸び縮み
     * しない（タイヤだけが動く）。当たり判定の大きさもそれに合わせて変えない。</p>
     */
    public CarSpec bodyShape() {
        return CarTypes.get(carId).spec();
    }

    /**
     * 車体の周りのブロックの当たり判定を {@link #blockField} に集める。1 ティックで動ける範囲
     * （速さ × 1 ティック + 1m）まで広げて取る。
     */
    private void fillBlockField(CarBody body, double x, double z) {
        blockField.clear();
        double[] b = body.bounds(x, state.height, z, CarBody.axes(state.yaw, state.pitch, state.roll));
        double reach = Math.hypot(state.velocityX(), state.velocityZ()) * TICK_SECONDS
                + Math.abs(state.verticalSpeed) * TICK_SECONDS + 1.0;
        // Minecraft の AABB と同じく、角を小さい側・大きい側へ並べ直してから広げる
        double[] area = {
                Math.min(b[0], b[3]) - reach, Math.min(b[1], b[4]) - reach, Math.min(b[2], b[5]) - reach,
                Math.max(b[0], b[3]) + reach, Math.max(b[1], b[4]) + reach, Math.max(b[2], b[5]) + reach};
        host.collectBlocks(blockField, area, x, z);
    }

    /**
     * 路面がどれだけ濡れているかを進める。
     *
     * <p>雨かどうかは版が {@code Level#isRainingAt}（その地点に実際に雨が当たっているか）で答える。
     * 空が見えるかとバイオームの降水種別まで見るので、<b>トンネルや橋の下は自動で乾いたまま</b>になり、
     * 寒冷地では雨ではなく雪が降る。</p>
     *
     * <p><b>切り替えは瞬時にしない。</b>トンネルの出入口でグリップが 1 ティックで 0.7 倍に
     * 飛ぶと、旋回中なら横 G が跳ねる。濡れるのは速く、乾くのは遅い時定数で追従させる。</p>
     */
    private void updateWetness() {
        boolean raining = host.rainingHere();
        double seconds = raining ? WET_SECONDS : DRY_SECONDS;
        double rate = TICK_SECONDS / seconds;
        double target = raining ? 1.0 : 0.0;
        wetness += (target - wetness) * Math.min(1.0, rate);
    }

    /** 送信用に各輪のサスペンション長を float でまとめる。 */
    public float[] suspensionSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.suspensionLength[i];
        }
        return values;
    }

    /** 送信用に各輪の摩擦の仕事率を float でまとめる。 */
    public float[] frictionSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.wheelFrictionPower[i];
        }
        return values;
    }

    /** 送信用に各輪の回転角速度を float でまとめる。 */
    public float[] wheelSpeedSnapshot() {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) state.wheelAngularVelocity[i];
        }
        return values;
    }

    /** 4 輪の路面を 1 つの整数へ詰める。{@code CarStatePacket} は 2 バイトで運ぶ。 */
    public int packSurfaces() {
        int packed = 0;
        for (int i = 0; i < ground.surfaces().length; i++) {
            int bits = ground.surfaces()[i].ordinal();
            if (state.wheelLoad[i] <= 0.0) {
                bits |= AIRBORNE_BIT;
            }
            packed |= bits << (i * SURFACE_BITS);
        }
        return packed;
    }

    /** 4 輪とも同じ路面を詰めた値。 */
    public static int packSurfaces(RoadSurface surface) {
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
            return ground.surfaces()[wheel.ordinal()];
        }
        int packed = host.synced().surface();
        return RoadSurface.byIndex((packed >> (wheel.ordinal() * SURFACE_BITS)) & SURFACE_MASK);
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

    /**
     * タイヤの当たりを見る点のワールド座標（{@link CarBody#tireRims}）。描画用。
     * サスペンションの伸びは手元で物理を解いている車しか持っていないので、それ以外は null。
     */
    @Nullable
    public double[][][] tireRims() {
        if (!host.simulatedHere()) {
            return null;
        }
        double[] span = tireSpan();
        return CarBody.tireRims(spec(), state, host.posX(), host.posZ(), span[0], span[1]);
    }

    /**
     * 階段状の壁をならした線（{@link CarBody#staircaseLines}）。F3+B 用。
     * 描くのは車から {@link #STAIRCASE_LINE_RANGE} 以内の線だけ（近づいた壁だけが出る）。
     * それを持っているのは手元で物理を解いている車だけなので、それ以外は null。
     */
    @Nullable
    public List<double[]> staircaseLines() {
        if (!host.simulatedHere() || !ClientConfig.virtualWalls) {
            return null;
        }
        // 見えない壁は車の周りの壁から先へたどって作るので、描くのは車の近くの線だけ
        double carX = host.posX();
        double carZ = host.posZ();
        List<double[]> near = new ArrayList<>();
        for (double[] line : CarBody.staircaseLines(blockField, ground.contact(), carX, carZ)) {
            double dx = line[2] - line[0];
            double dz = line[3] - line[1];
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0e-9 ? 0.0
                    : Mth.clamp(((carX - line[0]) * dx + (carZ - line[1]) * dz) / lengthSq, 0.0, 1.0);
            double ox = line[0] + dx * t - carX;
            double oz = line[1] + dz * t - carZ;
            if (ox * ox + oz * oz <= STAIRCASE_LINE_RANGE * STAIRCASE_LINE_RANGE) {
                near.add(line);
            }
        }
        return near;
    }

    /** 描画側が、いま描いたタイヤの幅を知らせる。 */
    public void setVisualTireSpan(double inner, double outer) {
        visualTireSpan = new double[]{inner, outer};
    }

    private double[] tireSpan() {
        return visualTireSpan != null ? visualTireSpan : CarBody.defaultTireSpan(spec());
    }

    /**
     * 接地を探すタイヤの内側と外側の端。幅の端そのものだと、隣のブロックの面にちょうど
     * 触れているだけで乗り上げるので、少し内へ寄せる。
     */
    public double[] tireGroundEdges() {
        double[] span = tireSpan();
        double inset = Math.min(TIRE_EDGE_INSET, (span[1] - span[0]) / 2.0);
        return new double[]{span[0] + inset, span[1] - inset};
    }

    /** 車体（{@link CarBody}）の角のワールド座標。描画用。順番は {@link CarBody#corners()} と同じ。 */
    public double[][] bodyCorners() {
        boolean local = host.simulatedHere();
        double yaw = local ? state.yaw : remoteYaw();
        double pitch = local ? state.pitch : host.synced().pitch();
        double roll = local ? state.roll : host.synced().roll();
        double[][] ax = CarBody.axes(yaw, pitch, roll);
        double[][] corners = CarBody.of(spec(), bodyShape()).corners();
        double px = host.posX();
        double py = host.posY();
        double pz = host.posZ();
        double[][] out = new double[corners.length][];
        for (int i = 0; i < corners.length; i++) {
            double[] c = corners[i];
            out[i] = new double[]{
                    px + ax[0][0] * c[0] + ax[1][0] * c[1] + ax[2][0] * c[2],
                    py + ax[0][1] * c[0] + ax[1][1] * c[1] + ax[2][1] * c[2],
                    pz + ax[0][2] * c[0] + ax[1][2] * c[1] + ax[2][2] * c[2]};
        }
        return out;
    }
}
