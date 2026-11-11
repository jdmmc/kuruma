package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.util.GsonHelper;
import com.jdmmc.kurumamod.car.CarId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.plate.PlateLayout;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ナンバーの部品の見た目（{@code assets/<ns>/car_parts/<id>.json} の {@code "plate"}）。<b>クライアントだけが読む。</b>
 *
 * <pre>
 * "plate": {
 *   "model": "kurumamod:models/plate/jp.obj",
 *   "texture": "kurumamod:textures/plate/jp_private.png",
 *   "texture_rear": null,
 *   "scale": 1.15,
 *   "fields": {
 *     "region": { "font": "kurumamod:plate_fonts/kurumaplate_jp.ttf", "color": "#1E5631", "fit": "justify" },
 *     "number": { "color": "#1E5631", "format": "jp_serial", "mono": true, "emboss": 0.02 }
 *   }
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code model} … プレートのメッシュ。表は前（-Z）、原点は取り付け面の中心、実寸。字の枠は {@code text_<欄>} の板
 *       （{@code tools/blender_plate_gauge_*.py}）</li>
 *   <li>{@code texture} … 地の絵。<b>表の全体に UV を 0〜1 で張る</b>（字はこの上に MOD が焼き込む）。
 *       {@code texture_rear} を書けば後ろだけそれを使う（UK の前は白地・後ろは黄地）</li>
 *   <li>{@code scale} … 描く大きさの倍率（既定 {@link #DEFAULT_SCALE}）。メッシュは実寸だが、車はどれも実車より大きく
 *       作ってあるので、実寸のままでは車体に対して小さく見える。車の側の {@code plates} の {@code scale} はこれに掛かる</li>
 *   <li>{@code fields} … 欄ごとの字の見た目。欄の決まり（字の数・使える字・既定の文字）は {@code data/} 側</li>
 * </ul>
 *
 * <p>欄の見た目の項目: {@code font}（TTF のパス。書かなければ {@link #DEFAULT_FONT}）、{@code color}（{@code #RRGGBB}）、
 * {@code fit}（{@code justify} / {@code condense} / {@code center}）、{@code format}（{@code jp_serial} / {@code upper}）、
 * {@code mono}（等幅）、{@code emboss}（型押しの深さ。枠の高さに対する比）。</p>
 */
public record PlateModel(CarId model, CarId texture, @Nullable CarId textureRear,
                         float scale, Map<String, FieldLook> fields) {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlateModel.class);

    /**
     * 書いていないときの大きさ。車はどれも実車より 1.1〜1.45 倍大きく作ってあり、実寸のプレートは車体に対して
     * 小さく見える（S15 の幅の比で実車の 7 割）。車体の拡大の目安に揃える。日本のプレートは 1.15 を書いてある
     */
    public static final float DEFAULT_SCALE = 1.2F;

    /** 欄の見た目を書かなかったときのフォント（英数字向け）。日本語の字はシステムのフォントで代わりに描く。 */
    public static final CarId DEFAULT_FONT =
            CarId.of("kurumamod", "plate_fonts/barlowcondensed-semibold.ttf");

    /** 欄 1 つの字の見た目。 */
    public record FieldLook(CarId font, int argb, PlateLayout.Fit fit, @Nullable String format,
                            boolean mono, float emboss) {

        public static final FieldLook DEFAULT =
                new FieldLook(DEFAULT_FONT, 0xFF141414, PlateLayout.Fit.CONDENSE, null, false, 0.0F);
    }

    /** 見た目の定義が無い・読めない部品。メッシュが空なので何も描かれない。 */
    private static final PlateModel EMPTY = new PlateModel(
            CarId.of("kurumamod", "models/plate/missing.obj"),
            CarId.of("kurumamod", "textures/plate/missing.png"), null, DEFAULT_SCALE, Map.of());

    private static final Map<CarId, PlateModel> CACHE = new HashMap<>();

    /** その欄の字の見た目。書かれていなければ既定。 */
    public FieldLook field(String id) {
        return fields.getOrDefault(id, FieldLook.DEFAULT);
    }

    /** 前後どちらの地の絵を使うか。 */
    public CarId texture(boolean front) {
        return front || textureRear == null ? texture : textureRear;
    }

    public static PlateModel get(CarId partId) {
        return CACHE.computeIfAbsent(partId, PlateModel::load);
    }

    /** リソースの再読み込みで呼ぶ。 */
    public static void clearCache() {
        CACHE.clear();
    }

    private static PlateModel load(CarId partId) {
        CarId location = CarPartModel.locationOf(partId);
        Optional<Assets.Asset> resource = Assets.get().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("ナンバーの見た目の定義が見つかりません（プレートを描きません）: {}", location);
            return EMPTY;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            JsonObject root = GsonHelper.parse(reader);
            JsonObject block = root.has("plate") ? GsonHelper.getAsJsonObject(root, "plate") : root;
            return parse(block, location);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("ナンバーの見た目の定義の読み込みに失敗しました（プレートを描きません）: {}", location, e);
            return EMPTY;
        }
    }

    private static PlateModel parse(JsonObject block, CarId location) {
        if (!block.has("model") || !block.has("texture")) {
            LOGGER.error("ナンバーの見た目の定義に model と texture が要ります（プレートを描きません）: {}", location);
            return EMPTY;
        }
        CarId model = CarId.parseStrict(GsonHelper.getAsString(block, "model"));
        CarId texture = CarId.parseStrict(GsonHelper.getAsString(block, "texture"));
        CarId rear = block.has("texture_rear") && !block.get("texture_rear").isJsonNull()
                ? CarId.parseStrict(GsonHelper.getAsString(block, "texture_rear")) : null;
        Map<String, FieldLook> fields = new LinkedHashMap<>();
        if (block.has("fields")) {
            for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(block, "fields").entrySet()) {
                if (entry.getKey().startsWith("_") || !entry.getValue().isJsonObject()) {
                    continue;
                }
                fields.put(entry.getKey(), parseField(entry.getValue().getAsJsonObject(), location, entry.getKey()));
            }
        }
        float scale = Math.max(PlateMounts.MIN_SCALE,
                Math.min(PlateMounts.MAX_SCALE, GsonHelper.getAsFloat(block, "scale", DEFAULT_SCALE)));
        return new PlateModel(model, texture, rear, scale, Map.copyOf(fields));
    }

    private static FieldLook parseField(JsonObject json, CarId location, String name) {
        CarId font = json.has("font")
                ? CarId.parseStrict(GsonHelper.getAsString(json, "font")) : DEFAULT_FONT;
        int argb = FieldLook.DEFAULT.argb();
        if (json.has("color")) {
            String raw = GsonHelper.getAsString(json, "color");
            try {
                argb = 0xFF000000 | Integer.parseInt(raw.startsWith("#") ? raw.substring(1) : raw, 16);
            } catch (NumberFormatException e) {
                LOGGER.warn("ナンバーの欄 {} の color を読めません（黒にします）: {} の \"{}\"", name, location, raw);
            }
        }
        float emboss = Math.max(0.0F, Math.min(0.1F, GsonHelper.getAsFloat(json, "emboss", 0.0F)));
        return new FieldLook(font, argb, PlateLayout.Fit.byName(GsonHelper.getAsString(json, "fit", null)),
                GsonHelper.getAsString(json, "format", null), GsonHelper.getAsBoolean(json, "mono", false), emboss);
    }
}
