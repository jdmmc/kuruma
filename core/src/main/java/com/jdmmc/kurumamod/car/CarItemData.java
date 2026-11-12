package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.race.RaceTag;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import org.jetbrains.annotations.Nullable;

/**
 * 車を出すアイテムが持つ中身（どの車種か・設定ごと回収したときの諸元と部品）の書き方と読み方。
 * Minecraft に依存しない。アイテムの NBT を開くのと、アイテムを作るのは版の {@code CarSpawnItem}。
 *
 * <p><b>アイテムは 1 種類のまま、どの車を出すかは NBT に持つ。</b>1.21 からはアイテムの NBT が
 * {@code minecraft:custom_data} になったが、<b>中のキーは 1.20.1 と同じ</b>なので、
 * 1.20.1 のワールドを変換しても設定を持ったまま回収した車が読める。</p>
 */
public final class CarItemData {

    /** NBT のキー。車種の id を文字列で入れる。 */
    public static final String CAR_ID = "CarId";
    /**
     * 諸元と装着した部品。<b>キーは {@code CarEntity} の NBT と同じ名前にしてある。</b>
     *
     * <p>同じものを指すキーが 2 つあると、片方を直したときにもう片方が取り残される。</p>
     */
    public static final String SPEC = "Spec";
    public static final String PARTS = "Parts";

    private CarItemData() {
    }

    /**
     * その車種をアイテムに書くか。<b>既定の車には書かない</b>
     * （NBT の付いていないアイテムと同じ物として重なる）。
     */
    public static boolean storesCarId(CarId carId) {
        return !carId.equals(CarTypes.DEFAULT_ID);
    }

    /** 車種を書く。{@link #storesCarId} が true のときだけ呼ぶ。 */
    public static void writeCarId(RaceTag tag, CarId carId) {
        tag.putString(CAR_ID, carId.toString());
    }

    /**
     * 設定ごと回収したときの諸元と部品を書く。部品が何も付いていなければ部品は書かない。
     *
     * <p><b>NBT が違うので他のアイテムと重ならない</b>——セッティングの違う車が
     * 1 個のスタックに混ざらないという意味で、これは正しい振る舞い。</p>
     */
    public static void writeSettings(RaceTag tag, CarSpec spec, PartFitment parts) {
        RaceTag specTag = tag.child();
        CarSpecCodec.save(specTag, spec);
        tag.put(SPEC, specTag);
        if (!parts.isEmpty()) {
            RaceTag partsTag = tag.child();
            parts.save(partsTag);
            tag.put(PARTS, partsTag);
        }
    }

    /** そのアイテムが出す車種。NBT が無い・壊れている・知らない id なら既定の車。 */
    public static CarType carTypeOf(@Nullable RaceTag tag) {
        if (tag == null || !tag.contains(CAR_ID)) {
            return CarTypes.get(CarTypes.DEFAULT_ID);
        }
        CarId id = CarId.parse(tag.getString(CAR_ID));
        return id != null ? CarTypes.get(id) : CarTypes.get(CarTypes.DEFAULT_ID);
    }

    /** そのアイテムが設定（諸元か部品）を持っているか。持ち物の画面で見分けるために使う。 */
    public static boolean hasSettings(@Nullable RaceTag tag) {
        return tag != null && (tag.contains(SPEC) || tag.contains(PARTS));
    }

    /** 保存された諸元。無ければ null。 */
    @Nullable
    public static CarSpec savedSpec(RaceTag tag) {
        return tag.contains(SPEC) ? CarSpecCodec.load(tag.getCompound(SPEC)) : null;
    }

    /** 保存された部品。無ければ null。 */
    @Nullable
    public static PartFitment savedParts(RaceTag tag) {
        return tag.contains(PARTS) ? PartFitment.load(tag.getCompound(PARTS)) : null;
    }
}
