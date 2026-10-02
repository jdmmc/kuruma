package com.jdmmc.kurumamod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * セッティングのプリセット。<b>クライアント側に保存する。</b>
 *
 * <p>調整した値は「車種の諸元」ではなく「いま自分が使っている設定」なので、ワールドや
 * サーバーをまたいで持ち回れる方が都合がよい。置き場所は {@code config/kurumamod-setups.json}。</p>
 *
 * <p><b>キーは項目名。</b>並び順で保存すると、項目を増やしたときに過去のファイルが
 * 別の値として読まれてしまう。無い項目は既定値のままにするので、古いファイルも読める。</p>
 *
 * <p>人が読める JSON にしてあるので、書き換えたり誰かに渡したりできる。</p>
 *
 * <p><b>パーツは {@code "parts"} の下に入る</b>（{@link PartFitment#toJson()}）。値と同じ階層に
 * 置いてあるので、ファイルから 1 件コピーして {@code presets/} へ置けばパーツごと組み込みに
 * なる。{@code "parts"} の無いもの（パーツを入れる前に保存したもの・車に乗らずに保存したもの）は、
 * 読み込んでもパーツに触らない。</p>
 */
public final class CarSetups {

    private static final String FILE = "kurumamod-setups.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 値と同じ階層に置く、パーツのキー。 */
    static final String PARTS_KEY = "parts";

    /**
     * 保存した 1 件。
     *
     * @param values 項目名 → 内部単位の値
     * @param parts  装着状態。null なら「パーツは含まない」（読み込んでも触らない）
     */
    public record Setup(Map<String, Double> values, @Nullable PartFitment parts) {

        /**
         * 諸元を組み立てる。載っていない項目は既定値のまま。項目を増やした後で古いファイルを
         * 読んでも {@link CarSpecCodec#fromMap} が吸収する。
         */
        public CarSpec toSpec() {
            return CarSpecCodec.fromMap(values);
        }
    }

    /** 名前 → 1 件。名前順に並べておく。 */
    private static Map<String, Setup> setups;

    private CarSetups() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static Map<String, Setup> all() {
        if (setups == null) {
            setups = read();
        }
        return setups;
    }

    private static Map<String, Setup> read() {
        Path path = file();
        Map<String, Setup> loaded = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return loaded;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return loaded;
            }
            for (String name : root.keySet()) {
                loaded.put(name, parse(root.getAsJsonObject(name)));
            }
        } catch (IOException | RuntimeException e) {
            // 壊れていても運転は続けられるべきなので、握りつぶして空にする
            return new TreeMap<>();
        }
        return loaded;
    }

    /**
     * 1 件を読む。組み込みプリセット（{@link CarPresets}）も同じ形なのでここを通す。
     *
     * <p><b>数でないキーは読み飛ばす。</b>{@code getAsDouble} に任せると、オブジェクトの
     * {@code "parts"} や {@code "_comment"} が 1 つ混ざっただけで例外になり、ファイルごと捨てられる。</p>
     */
    static Setup parse(JsonObject entry) {
        Map<String, Double> values = new TreeMap<>();
        for (String key : entry.keySet()) {
            JsonElement element = entry.get(key);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                values.put(key, element.getAsDouble());
            }
        }
        PartFitment parts = entry.has(PARTS_KEY) && entry.get(PARTS_KEY).isJsonObject()
                ? PartFitment.fromJson(entry.getAsJsonObject(PARTS_KEY))
                : null;
        return new Setup(Map.copyOf(values), parts);
    }

    private static void write() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Setup> setup : all().entrySet()) {
            JsonObject entry = new JsonObject();
            for (Map.Entry<String, Double> value : new TreeMap<>(setup.getValue().values()).entrySet()) {
                entry.addProperty(value.getKey(), value.getValue());
            }
            if (setup.getValue().parts() != null) {
                entry.add(PARTS_KEY, setup.getValue().parts().toJson());
            }
            root.add(setup.getKey(), entry);
        }
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            // 保存できなくても走れる方を優先する
        }
    }

    /** 名前の一覧。 */
    public static List<String> names() {
        return new ArrayList<>(all().keySet());
    }

    public static boolean exists(String name) {
        return all().containsKey(name);
    }

    /**
     * 今の設定を名前を付けて保存する。同じ名前があれば上書き。
     *
     * @param parts 装着状態。null なら含めない（車に乗っていないときなど）
     */
    public static void save(String name, CarSpec spec, @Nullable PartFitment parts) {
        all().put(name, new Setup(CarSpecCodec.toMap(spec), parts));
        write();
    }

    /**
     * 保存した 1 件。諸元は {@link Setup#toSpec()} で組み立てる。
     *
     * @return 無ければ null
     */
    @Nullable
    public static Setup get(String name) {
        return all().get(name);
    }

    public static boolean delete(String name) {
        if (all().remove(name) == null) {
            return false;
        }
        write();
        return true;
    }
}
