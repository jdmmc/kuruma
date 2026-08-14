package com.jdmmc.kurumamod.client;

import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MOD に同梱するセッティングのプリセット。<b>読むだけで、書き換えられない。</b>
 *
 * <p>置き場所は {@code assets/kurumamod/presets/*.json}。ファイル名がそのまま id になり、
 * 表示名は翻訳キー {@code preset.kurumamod.<id>} から引く。リソースとして読むので
 * <b>F3+T で読み直せる</b>し、リソースパックで足すこともできる。</p>
 *
 * <h2>中身は項目名 → 内部単位の値</h2>
 *
 * <p>{@link CarSetups} が {@code config/kurumamod-setups.json} へ書くのと<b>同じ形</b>に
 * してある。ゲーム内で作り込んだセッティングを保存し、そのファイルから 1 つコピーして
 * ここへ置けばプリセットになる、という作り方ができる。</p>
 *
 * <p>単位が内部単位（角度はラジアン）なのはそのため。手で書くより、<b>ゲーム内で作って
 * 書き出したものを持ってくる</b>のが正しい使い方。</p>
 *
 * <h2>これは物理をリソースで決めているのか</h2>
 *
 * <p>いいえ。プリセットは<b>プレイヤーが選ぶ出発点</b>で、選んだ結果はスライダーを手で
 * 動かしたときとまったく同じ経路（{@link CarTuning} → {@code CarSpecPacket}）でサーバーへ
 * 届く。車の諸元がリソースパック次第になるわけではない。</p>
 */
public final class CarPresets {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DIRECTORY = "presets";
    private static final String SUFFIX = ".json";

    /** 並び順を書かなかったプリセットの位置。数字の小さいものが先。 */
    private static final int DEFAULT_ORDER = 100;

    private static List<Preset> cached;

    private CarPresets() {
    }

    /**
     * プリセット 1 つ。
     *
     * @param id     ファイル名から取った識別子。翻訳キーに使う
     * @param values 項目名 → 内部単位の値。載っていない項目は既定値になる
     * @param order  表示順。同じなら id 順
     */
    public record Preset(String id, Map<String, Double> values, int order) {

        public String translationKey() {
            return "preset.kurumamod." + id;
        }

        /** 画面に出す名前。翻訳が無ければ id をそのまま出す。 */
        public Component displayName() {
            String key = translationKey();
            Component translated = Component.translatable(key);
            return translated.getString().equals(key) ? Component.literal(id) : translated;
        }

        public CarSpec toSpec() {
            return CarSpecCodec.fromMap(values);
        }
    }

    /** 一覧。まだ読んでいなければ読む。 */
    public static List<Preset> all() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** リソースの再読み込みで呼ぶ。 */
    public static void clearCache() {
        cached = null;
    }

    private static List<Preset> load() {
        Map<ResourceLocation, Resource> found = Minecraft.getInstance().getResourceManager()
                .listResources(DIRECTORY, location -> location.getPath().endsWith(SUFFIX));

        List<Preset> presets = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            ResourceLocation location = entry.getKey();
            if (!location.getNamespace().equals(Kurumamod.MODID)) {
                continue;
            }
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                presets.add(parse(idOf(location), GsonHelper.parse(reader)));
            } catch (IOException | RuntimeException e) {
                // 1 つ壊れていても他のプリセットは使えるべきなので、その 1 つだけ捨てる
                LOGGER.error("プリセットの読み込みに失敗しました: {}", location, e);
            }
        }
        presets.sort(Comparator.comparingInt(Preset::order).thenComparing(Preset::id));
        LOGGER.info("組み込みプリセットを {} 件読み込みました", presets.size());
        return List.copyOf(presets);
    }

    /** {@code presets/drift.json} → {@code drift}。 */
    private static String idOf(ResourceLocation location) {
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1, path.length() - SUFFIX.length());
    }

    private static Preset parse(String id, JsonObject root) {
        int order = root.has("order") ? root.get("order").getAsInt() : DEFAULT_ORDER;
        JsonObject values = root.has("values") ? root.getAsJsonObject("values") : root;

        Map<String, Double> parsed = new TreeMap<>();
        for (String key : values.keySet()) {
            // order を values と同じ階層に書ける形にしてあるので、拾わないよう避ける
            if (values == root && key.equals("order")) {
                continue;
            }
            parsed.put(key, values.get(key).getAsDouble());
        }
        return new Preset(id, Map.copyOf(parsed), order);
    }
}
