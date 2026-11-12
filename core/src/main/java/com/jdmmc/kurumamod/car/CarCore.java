package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
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
}
