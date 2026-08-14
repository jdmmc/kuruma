package com.jdmmc.kurumamod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraftforge.fml.loading.FMLPaths;

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
 */
public final class CarSetups {

    private static final String FILE = "kurumamod-setups.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 名前 → 項目名 → 内部単位の値。名前順に並べておく。 */
    private static Map<String, Map<String, Double>> setups;

    private CarSetups() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static Map<String, Map<String, Double>> all() {
        if (setups == null) {
            setups = read();
        }
        return setups;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Double>> read() {
        Path path = file();
        Map<String, Map<String, Double>> loaded = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return loaded;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return loaded;
            }
            for (String name : root.keySet()) {
                Map<String, Double> values = new TreeMap<>();
                JsonObject entry = root.getAsJsonObject(name);
                for (String key : entry.keySet()) {
                    values.put(key, entry.get(key).getAsDouble());
                }
                loaded.put(name, values);
            }
        } catch (IOException | RuntimeException e) {
            // 壊れていても運転は続けられるべきなので、握りつぶして空にする
            return new TreeMap<>();
        }
        return loaded;
    }

    private static void write() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<String, Double>> setup : all().entrySet()) {
            JsonObject entry = new JsonObject();
            for (Map.Entry<String, Double> value : setup.getValue().entrySet()) {
                entry.addProperty(value.getKey(), value.getValue());
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

    /** 今の設定を名前を付けて保存する。同じ名前があれば上書き。 */
    public static void save(String name, CarSpec spec) {
        all().put(name, CarSpecCodec.toMap(spec));
        write();
    }

    /**
     * 保存した設定を組み立てる。
     *
     * <p>載っていない項目は既定値のまま。項目を増やした後で古いファイルを読んでも
     * {@link CarSpecCodec#fromMap} が吸収する。</p>
     *
     * @return 無ければ null
     */
    public static CarSpec load(String name) {
        Map<String, Double> values = all().get(name);
        return values == null ? null : CarSpecCodec.fromMap(values);
    }

    public static boolean delete(String name) {
        if (all().remove(name) == null) {
            return false;
        }
        write();
        return true;
    }
}
