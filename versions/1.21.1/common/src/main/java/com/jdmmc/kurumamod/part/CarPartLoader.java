package com.jdmmc.kurumamod.part;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.jdmmc.kurumamod.McIds;
import com.jdmmc.kurumamod.car.CarId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * データパックから部品を読む。<b>サーバー側だけで動く。</b>
 *
 * <p>読むのは {@code data/<ns>/car_parts/<path>.json}。ファイルの位置がそのまま
 * {@code <ns>:<path>} という部品の id になり、見た目の定義
 * （{@code assets/<ns>/car_parts/<path>.json}）も同じ id から引かれる。</p>
 *
 * <pre>
 * {
 *   "slot": "wheel",
 *   "order": 10,
 *   "cars": ["mypack:kei", "mypack:kei_van"]
 * }
 * </pre>
 *
 * <p><b>{@code cars} は省略できる。</b>書かなければどの車種にも付く。書けば、その車種の
 * 換装画面にだけ並び、サーバーも他の車種には履かせない。車種は存在を確かめない
 * （車種の読み込みと順序が決まっていないうえ、別のカーパックの車種を書いてもよいため）。</p>
 *
 * <p><b>{@code slot} は必須。</b>どこに付くか分からない部品は換装画面に並べようがないので、
 * 書かれていなければその部品だけ捨てる。将来この MOD が知らない場所（エアロなど）を
 * 書いたカーパックも同じ扱いで、<b>その部品だけ</b>落ちて他は動く。</p>
 *
 * <p>1 つ壊れていても他は使えるべきなので、{@code CarTypeLoader} と同じく
 * <b>失敗したファイルだけを捨てて続ける</b>。</p>
 */
public final class CarPartLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    /** {@code data/<ns>/car_parts/} 以下を読む。 */
    public static final String DIRECTORY = CarPartJson.DIRECTORY;

    public CarPartLoader() {
        super(GSON, DIRECTORY);
    }

    /** 中身の読み方は core の {@link CarPartJson}。ここは Minecraft の id を core の id に直して渡すだけ。 */
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> found, ResourceManager resources,
                         ProfilerFiller profiler) {
        Map<CarId, JsonElement> byId = new LinkedHashMap<>();
        found.forEach((id, json) -> byId.put(McIds.of(id), json));
        CarPartJson.apply(byId);
    }
}
