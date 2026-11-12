package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.CarSpec;
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
}
