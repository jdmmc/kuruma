package com.jdmmc.kurumamod.car;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import com.jdmmc.kurumamod.tuning.TunableParameter;
import com.jdmmc.kurumamod.tuning.Tunables;
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
import java.util.TreeMap;

/**
 * データパックから車種の諸元を読む。<b>サーバー側だけで動く。</b>
 *
 * <p>読むのは {@code data/<ns>/cars/<path>.json}。ファイルの位置がそのまま
 * {@code <ns>:<path>} という車種の id になる。</p>
 *
 * <h2>中身はセッティングのプリセットと同じ形</h2>
 *
 * <p>{@code 項目名 → 内部単位の値}。{@code CarPresets} や
 * {@code config/kurumamod-setups.json} とまったく同じなので、<b>ゲーム内で作り込んで
 * 書き出したものをそのまま持ってこられる</b>。手で数字を書くより確実で、
 * カーパックを作る人に「まず乗って詰める」という正しい順序を強制できる。</p>
 *
 * <pre>
 * {
 *   "order": 10,
 *   "values": {
 *     "wheel_base": 2.4,
 *     "mass": 950.0
 *   }
 * }
 * </pre>
 *
 * <p><b>書かれていない項目は既定値になる</b>（{@code CarSpecCodec#fromMap}）。全部書く必要はなく、
 * 既定の車から変えたいところだけ書けばよい。項目が増えても古いカーパックは読める。</p>
 *
 * <p>1 つ壊れていても他の車種は使えるべきなので、<b>失敗したファイルだけを捨てて続ける</b>。</p>
 *
 * <h2>範囲を外れた値は範囲の端へ丸める</h2>
 *
 * <p>範囲は調整画面のスライダーと同じ（{@link TunableParameter#clampInternal}）。上に外れたら上限、
 * 下に外れたら下限にして、ログに残す。<b>手で書かれる値なので桁違いが混ざる</b>——角度を度で
 * 書くと 32 ラジアンのような値がそのまま通り、スライダーでは作れない車ができていた。
 * 丸めておけば、カーパックの車に乗って調整画面を開いたときにスライダーが端に張り付いたまま
 * 動かない、ということも起きない。数として読めない値（NaN・無限大）は既定値のままにする。</p>
 */
public final class CarTypeLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** {@code data/<ns>/cars/} 以下を読む。 */
    public static final String DIRECTORY = "cars";

    public CarTypeLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> found, ResourceManager resources,
                         ProfilerFiller profiler) {
        List<CarType> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : found.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                loaded.add(parse(id, GsonHelper.convertToJsonObject(entry.getValue(), "car")));
            } catch (RuntimeException e) {
                LOGGER.error("車種の読み込みに失敗しました（この車種だけ捨てます）: {}", id, e);
            }
        }
        CarTypes.replaceAll(loaded);
        LOGGER.info("車種を {} 件読み込みました: {}", CarTypes.all().size(),
                CarTypes.all().stream().map(type -> type.id().toString()).toList());
    }

    /**
     * {@code values} が無ければ根をそのまま値の並びとして読む（プリセットと同じ扱い）。
     *
     * <p><b>{@code _} で始まるキーは読み飛ばす。</b>JSON にコメントは書けないが、
     * カーパックの諸元は<b>手で書かれるもの</b>なので「なぜこの値なのか」を残せる道が要る。
     * 用意しておかないと {@code "_comment": "..."} を書いた人のファイルが
     * 「数値として読めない」で丸ごと捨てられる。</p>
     */
    private static CarType parse(ResourceLocation id, JsonObject root) {
        int order = root.has("order") ? root.get("order").getAsInt() : CarType.DEFAULT_ORDER;
        JsonObject values = root.has("values") ? root.getAsJsonObject("values") : root;

        Map<String, Double> parsed = new TreeMap<>();
        for (String key : values.keySet()) {
            if (key.startsWith("_")) {
                continue;
            }
            // order と default_parts を values と同じ階層に書ける形にしてあるので、値として拾わないよう避ける
            if (values == root && (key.equals("order") || key.equals("default_parts"))) {
                continue;
            }
            Double value = inRange(id, key, values.get(key).getAsDouble());
            if (value != null) {
                parsed.put(key, value);
            }
        }
        CarSpec spec = CarSpecCodec.fromMap(parsed);
        return new CarType(id, spec, order, parseDefaultParts(id, root));
    }

    /**
     * 初期装備。場所 → 部品 id。外せないものは {@code {"part": ..., "removable": false}} で書く。
     *
     * <pre>
     * "default_parts": {
     *   "rear_wing": "mypack:s15_spoiler",
     *   "front_bumper": { "part": "mypack:s15_bumper", "removable": false }
     * }
     * </pre>
     *
     * <p>部品があるかはここでは見ない（部品の読み込みとどちらが先か決まっていない）。
     * 知らない部品なら、その場所は何も付いていないのと同じに描かれる。</p>
     */
    private static java.util.Map<com.jdmmc.kurumamod.part.PartSlot, CarType.DefaultPart> parseDefaultParts(
            ResourceLocation id, JsonObject root) {
        java.util.Map<com.jdmmc.kurumamod.part.PartSlot, CarType.DefaultPart> result =
                new java.util.EnumMap<>(com.jdmmc.kurumamod.part.PartSlot.class);
        if (!root.has("default_parts") || !root.get("default_parts").isJsonObject()) {
            return result;
        }
        JsonObject json = root.getAsJsonObject("default_parts");
        for (String key : json.keySet()) {
            var slot = com.jdmmc.kurumamod.part.PartSlot.byName(key);
            if (slot == null) {
                LOGGER.warn("default_parts に知らない場所があります（無視します）: {} の \"{}\"", id, key);
                continue;
            }
            var element = json.get(key);
            String part;
            boolean removable = true;
            if (element.isJsonPrimitive()) {
                part = element.getAsString();
            } else if (element.isJsonObject() && element.getAsJsonObject().has("part")) {
                part = element.getAsJsonObject().get("part").getAsString();
                removable = GsonHelper.getAsBoolean(element.getAsJsonObject(), "removable", true);
            } else {
                LOGGER.warn("default_parts.{} は部品の id か {{\"part\": ..., \"removable\": ...}} で書いてください（無視します）: {}",
                        key, id);
                continue;
            }
            ResourceLocation partId = ResourceLocation.tryParse(part);
            if (partId == null) {
                LOGGER.warn("default_parts.{} の部品の id を読めません（無視します）: {} の \"{}\"", key, id, part);
                continue;
            }
            result.put(slot, new CarType.DefaultPart(partId, removable));
        }
        return result;
    }

    /**
     * 範囲の端へ丸める。<b>知らない名前はそのまま返す</b>（{@code torque_falloff} のような
     * 昔の項目は {@code Tunables#migrateLegacy} が読むので、ここで捨ててはいけない）。
     *
     * @return 丸めた値。数として読めなければ null（＝書かなかったのと同じ）
     */
    private static Double inRange(ResourceLocation id, String key, double value) {
        if (!Double.isFinite(value)) {
            LOGGER.warn("車種 {} の {} は数として読めないので既定値を使います: {}", id, key, value);
            return null;
        }
        TunableParameter parameter = Tunables.byName(key);
        if (parameter == null) {
            return value;
        }
        double clamped = parameter.clampInternal(value);
        if (clamped != value) {
            LOGGER.warn("車種 {} の {} = {} は範囲（{} 〜 {}）の外なので {} に丸めました",
                    id, key, value, parameter.minInternal(), parameter.maxInternal(), clamped);
        }
        return clamped;
    }
}
