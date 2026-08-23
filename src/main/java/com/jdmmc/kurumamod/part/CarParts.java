package com.jdmmc.kurumamod.part;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * いま読み込まれている部品の一覧。<b>サーバーとクライアントの両方が同じものを持つ。</b>
 *
 * <ul>
 *   <li>サーバー … {@link CarPartLoader} がデータパックから読んで入れる</li>
 *   <li>クライアント … サーバーから {@code CarPartTypesPacket} で配られたものを入れる</li>
 * </ul>
 *
 * <p><b>クライアントが自分でデータパックを読んではいけない。</b>{@code CarTypes} と同じ話で、
 * マルチプレイで何を履けるか決めるのはサーバーであって手元のカーパックではない。
 * 配られたものだけを見る、で揃えておけば、サーバーが知らない部品が換装画面に並ぶことがない。</p>
 *
 * <p><b>一覧に無い id を「装着していない」ことにはしない。</b>装着状態
 * （{@link PartFitment}）は id をそのまま覚えるので、カーパックを外しても
 * 見た目が既定へ落ちるだけで、入れ直せば元に戻る。</p>
 */
public final class CarParts {

    private static Map<ResourceLocation, CarPart> parts = Map.of();
    private static List<CarPart> sorted = List.of();

    private CarParts() {
    }

    /** 一覧を入れ替える。データパックの読み込みと、サーバーからの受信で呼ぶ。 */
    public static void replaceAll(Collection<CarPart> loaded) {
        Map<ResourceLocation, CarPart> next = new LinkedHashMap<>();
        for (CarPart part : loaded) {
            next.put(part.id(), part);
        }
        List<CarPart> list = new ArrayList<>(next.values());
        list.sort(Comparator.comparingInt(CarPart::order).thenComparing(part -> part.id().toString()));

        parts = Map.copyOf(next);
        sorted = List.copyOf(list);
    }

    /** その id の部品。<b>知らない id なら null。</b> */
    @Nullable
    public static CarPart get(ResourceLocation id) {
        return parts.get(id);
    }

    /** 並び順に整列した一覧。 */
    public static List<CarPart> all() {
        return sorted;
    }

    /** その場所に付けられる部品。換装画面が並べる一覧。 */
    public static List<CarPart> forSlot(PartSlot slot) {
        List<CarPart> list = new ArrayList<>();
        for (CarPart part : sorted) {
            if (part.slot() == slot) {
                list.add(part);
            }
        }
        return list;
    }
}
