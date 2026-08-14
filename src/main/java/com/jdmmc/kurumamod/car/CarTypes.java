package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * いま読み込まれている車種の一覧。<b>サーバーとクライアントの両方が同じものを持つ。</b>
 *
 * <ul>
 *   <li>サーバー … {@link CarTypeLoader} がデータパックから読んで入れる</li>
 *   <li>クライアント … サーバーから {@code CarTypesPacket} で配られたものを入れる</li>
 * </ul>
 *
 * <p><b>クライアントが自分でデータパックを読んではいけない。</b>シングルプレイでは同じ
 * プロセスなので読めてしまうが、マルチプレイでは車種を決めるのはサーバーであって、
 * 手元に置いたカーパックではない。配られたものだけを見る、で揃える。</p>
 *
 * <p>レジストリ（{@code DeferredRegister}）にしていないのは、<b>データパックが読まれる
 * 時点でレジストリは既に凍結されている</b>ため。車種ごとにアイテムやエンティティを
 * 登録することはできないので、車種は「エンティティが持つ id」として扱う。</p>
 */
public final class CarTypes {

    /**
     * MOD 同梱の車。<b>データパックが 1 つも無くても必ず存在する。</b>
     *
     * <p>id を {@code kurumamod:car} にしてあるのは、見た目の定義が
     * {@code assets/kurumamod/vehicles/car.json} で、アイテムもエンティティも
     * {@code kurumamod:car} だから。名前を揃えておくと特例が要らない。</p>
     */
    public static final ResourceLocation DEFAULT_ID = new ResourceLocation(Kurumamod.MODID, "car");

    /** 既定の車種。読み込みに失敗したときと、知らない id を引いたときに返る。 */
    public static final CarType FALLBACK = new CarType(DEFAULT_ID, CarSpec.DEFAULT, 0);

    private static Map<ResourceLocation, CarType> types = Map.of(DEFAULT_ID, FALLBACK);
    private static List<CarType> sorted = List.of(FALLBACK);

    private CarTypes() {
    }

    /**
     * 一覧を入れ替える。データパックの読み込みと、サーバーからの受信で呼ぶ。
     *
     * <p><b>{@link #DEFAULT_ID} は必ず残す。</b>データパックが上書きしていれば
     * そちらを使うが、消してしまうと同梱のアイテムが指す先が無くなる。</p>
     */
    public static void replaceAll(Collection<CarType> loaded) {
        Map<ResourceLocation, CarType> next = new LinkedHashMap<>();
        next.put(DEFAULT_ID, FALLBACK);
        for (CarType type : loaded) {
            next.put(type.id(), type);
        }
        List<CarType> list = new ArrayList<>(next.values());
        list.sort(Comparator.comparingInt(CarType::order).thenComparing(type -> type.id().toString()));

        types = Map.copyOf(next);
        sorted = List.copyOf(list);
    }

    /**
     * その id の車種。<b>知らない id なら既定の車を返す。</b>
     *
     * <p>カーパックを外したセーブデータを開いても、車が消えたり落ちたりせず
     * 既定の車として走れる方がよいため。</p>
     */
    public static CarType get(ResourceLocation id) {
        CarType type = types.get(id);
        return type != null ? type : FALLBACK;
    }

    /** その id の車種を知っているか。知らない車に乗っていることを画面に出したいときに使う。 */
    public static boolean has(ResourceLocation id) {
        return types.containsKey(id);
    }

    /** 並び順に整列した一覧。クリエイティブタブと車種の選択に使う。 */
    public static List<CarType> all() {
        return sorted;
    }
}
