package com.jdmmc.kurumamod.part;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.plate.PlateRules;
import com.jdmmc.kurumamod.tuning.Regulation;
import org.jetbrains.annotations.Nullable;

/**
 * 運転者から届いた換装の要求を、サーバーで受ける決まり。
 *
 * <p>版の {@code CarPacketHandlers} は、車を探して運転者か確かめ、ここへ渡し、弾かれたら知らせるだけ。
 * 何を付けてよいかの判断はここにだけ置く。</p>
 */
public final class PartRequests {

    /** 要求を当てる車。版の {@code CarEntity} が実装する（名前は {@code CarEntity} のメソッドのまま）。 */
    public interface Car {
        CarId getCarId();

        CarSpec getSpec();

        /** 装着状態（初期装備を埋める前の、プレイヤーが選んだもの）。 */
        PartFitment getFitment();

        /** 初期装備で埋めた、実際に付いている装着状態。 */
        PartFitment getEffectiveFitment();

        /** 装着状態を替える（版はそのまま周囲のクライアントへ配る）。 */
        void setFitment(PartFitment parts);
    }

    /**
     * レギュレーションの外になるので付けなかった。
     *
     * @param partId 付けようとした部品。初期装備の無い場所を「なし」にしようとしたときは null
     */
    public record Blocked(@Nullable CarId partId) {
    }

    private PartRequests() {
    }

    /**
     * 部品・調整値の付け替えの要求を当てる。
     *
     * @param regulation 運転者がいま守るべきレギュレーション（エントリー中のレース・宣言したクラス）。無ければ null
     * @return レギュレーションで弾いたとき {@link Blocked}（車は元のまま）。付けた・黙って捨てたときは null
     */
    @Nullable
    public static Blocked apply(Car car, PartSlot slot, PartFitment.Setting wanted, @Nullable Regulation regulation) {
        CarType type = CarTypes.get(car.getCarId());
        CarId partId = wanted.partId();
        // 「なし」は初期装備のある場所でだけ意味を持つ。外せない初期装備は外させない
        if (PartFitment.isNone(partId)) {
            var defaults = type.defaultPart(slot);
            if (defaults == null) {
                wanted = new PartFitment.Setting(null, wanted.camber(), wanted.offset(),
                        wanted.width(), wanted.angle(), wanted.tone());
                partId = null;
            } else if (!defaults.removable()) {
                return null;
            }
        }
        // いま履いている部品のまま角度だけ変える要求は、車種の制限で弾かない。
        // カーパックの更新で付かなくなった部品を履いたままの車でも、調整はできるべきなので
        if (partId != null && !PartFitment.isNone(partId) && !partId.equals(car.getFitment().getPart(slot))) {
            CarPart part = CarParts.get(partId);
            // 知らない部品、場所の合わない部品、その車種に付かない部品は履かせない
            if (part == null || part.slot() != slot || !part.fits(car.getCarId())) {
                return null;
            }
        }
        // 角度は弾かずに押し込む（範囲は PartFitment.readSetting が既に掛けている）。
        // 部品と違って「無い値」というものが無いので、弾くのではなく丸めるのが正しい

        PartFitment before = car.getFitment();
        CarSpec specBefore = car.getSpec();
        car.setFitment(before.with(slot, wanted));
        // レース中は決まりの外へ出る部品を履かせない（ターボ禁止・グリップの上限など）。
        // 部品の効き方は車へ付けてみないと分からないので、付けてから測って戻す
        if (regulation != null && !regulation.allowsChange(specBefore, car.getSpec(), type.spec())) {
            car.setFitment(before);
            return new Blocked(partId);
        }
        return null;
    }

    /** 付けているプレートの欄の決まり。付けていない・知らない部品なら決まり無し（最低限の検査だけ）。 */
    public static PlateRules plateRules(Car car) {
        CarId id = car.getEffectiveFitment().getPart(PartSlot.PLATE);
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.plate() == null ? PlateRules.NONE : part.plate();
    }
}
