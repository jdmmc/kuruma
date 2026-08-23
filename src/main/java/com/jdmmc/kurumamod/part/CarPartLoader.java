package com.jdmmc.kurumamod.part;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
 *   "order": 10
 * }
 * </pre>
 *
 * <p><b>{@code slot} は必須。</b>どこに付くか分からない部品は換装画面に並べようがないので、
 * 書かれていなければその部品だけ捨てる。将来この MOD が知らない場所（エアロなど）を
 * 書いたカーパックも同じ扱いで、<b>その部品だけ</b>落ちて他は動く。</p>
 *
 * <p>1 つ壊れていても他は使えるべきなので、{@code CarTypeLoader} と同じく
 * <b>失敗したファイルだけを捨てて続ける</b>。</p>
 */
public final class CarPartLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** {@code data/<ns>/car_parts/} 以下を読む。 */
    public static final String DIRECTORY = "car_parts";

    public CarPartLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> found, ResourceManager resources,
                         ProfilerFiller profiler) {
        List<CarPart> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : found.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                CarPart part = parse(id, GsonHelper.convertToJsonObject(entry.getValue(), "car_part"));
                if (part == null) {
                    continue;
                }
                loaded.add(part);
            } catch (RuntimeException e) {
                LOGGER.error("部品の読み込みに失敗しました（この部品だけ捨てます）: {}", id, e);
            }
        }
        CarParts.replaceAll(loaded);
        LOGGER.info("部品を {} 件読み込みました: {}", CarParts.all().size(),
                CarParts.all().stream().map(part -> part.id().toString()).toList());
    }

    /** 付ける場所が分からなければ null（この部品だけ捨てる）。 */
    private static CarPart parse(ResourceLocation id, JsonObject root) {
        if (!root.has("slot")) {
            LOGGER.error("部品に slot が書かれていません（この部品だけ捨てます）: {}", id);
            return null;
        }
        String name = root.get("slot").getAsString();
        PartSlot slot = PartSlot.byName(name);
        if (slot == null) {
            LOGGER.error("知らない slot です（この部品だけ捨てます）: {} の \"{}\"", id, name);
            return null;
        }
        int order = root.has("order") ? root.get("order").getAsInt() : CarPart.DEFAULT_ORDER;
        return new CarPart(id, slot, order);
    }
}
