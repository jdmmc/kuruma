package com.jdmmc.kurumamod.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * クラス（名前を付けて保存したレギュレーション）。<b>サーバーに保存する</b>（ワールドのフォルダの
 * {@code kurumamod/race_classes.json}）。
 *
 * <p>主催者画面から保存・読み込み・削除する。中身は {@code Regulation#toArray} の並びをそのまま
 * 名前ごとに持つだけ。人が手で書き換えることもあるので JSON にしてある。</p>
 *
 * <p>サーバー（ワールド）ごとに持つ。シングルプレイでワールドを替えたら読み直す。</p>
 */
public final class RaceClasses {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 名前の長さの上限。 */
    public static final int MAX_NAME = 32;
    /** 持てる数の上限。パケットが際限なく大きくならないように。 */
    public static final int MAX_CLASSES = 64;

    private static MinecraftServer loadedFor;
    private static Map<String, double[]> classes = new LinkedHashMap<>();

    private RaceClasses() {
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("kurumamod").resolve("race_classes.json");
    }

    private static Map<String, double[]> classes(MinecraftServer server) {
        if (loadedFor != server) {
            loadedFor = server;
            classes = load(file(server));
        }
        return classes;
    }

    private static Map<String, double[]> load(Path path) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                // <b>手で書き換えたファイルにも、画面から保存するときと同じ上限を掛ける。</b>長すぎる名前や
                // 多すぎるクラスをそのまま配ると、パケットが書けずに全員の接続が切れた
                String name = clip(entry.getKey().trim());
                if (name.isEmpty() || out.containsKey(name)) {
                    LOGGER.warn("クラスの名前が空か、切り詰めたら重なったので読み飛ばします: {}", entry.getKey());
                    continue;
                }
                if (out.size() >= MAX_CLASSES) {
                    LOGGER.warn("クラスが {} を超えているので、残りは読み飛ばします: {}", MAX_CLASSES, path);
                    break;
                }
                if (!name.equals(entry.getKey())) {
                    LOGGER.warn("クラスの名前が {} 文字を超えているので切り詰めます: {} → {}", MAX_NAME, entry.getKey(), name);
                }
                JsonArray array = entry.getValue().getAsJsonArray();
                double[] values = new double[array.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = array.get(i).getAsDouble();
                }
                out.put(name, values);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("クラスを読めませんでした: {}", path, e);
        }
        return out;
    }

    /** {@link #MAX_NAME} 文字に切り詰める。サロゲートペア（絵文字など）の途中では切らない。 */
    private static String clip(String name) {
        if (name.length() <= MAX_NAME) {
            return name;
        }
        int end = Character.isHighSurrogate(name.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
        return name.substring(0, end).trim();
    }

    private static void save(MinecraftServer server) {
        Path path = file(server);
        JsonObject root = new JsonObject();
        for (Map.Entry<String, double[]> entry : classes.entrySet()) {
            JsonArray array = new JsonArray();
            for (double value : entry.getValue()) {
                array.add(value);
            }
            root.add(entry.getKey(), array);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("クラスを保存できませんでした: {}", path, e);
        }
    }

    /** 保存してあるクラス（名前の付けた順）。記録の件数は全ワールドぶんを足す。 */
    public static List<RaceLobbyPacket.RaceClass> list(MinecraftServer server) {
        List<RaceLobbyPacket.RaceClass> out = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : classes(server).entrySet()) {
            int records = 0;
            for (ServerLevel level : server.getAllLevels()) {
                records += RaceData.get(level).countClassBests(entry.getKey());
            }
            out.add(new RaceLobbyPacket.RaceClass(entry.getKey(), entry.getValue().clone(), records));
        }
        return out;
    }

    /** 保存してあるクラスの名前（並び順）。記録の件数は数えない。 */
    public static List<String> names(MinecraftServer server) {
        return new ArrayList<>(classes(server).keySet());
    }

    /** その名前のクラスの決まり。無ければ null。 */
    public static Regulation regulation(MinecraftServer server, String name) {
        double[] values = name == null ? null : classes(server).get(name);
        return values == null ? null : Regulation.fromArray(values);
    }

    /**
     * 保存する。同じ名前があれば上書きする。
     *
     * @return 保存できたか（名前が空・多すぎるときは保存しない）
     */
    public static boolean put(MinecraftServer server, String name, double[] regulation) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            return false;
        }
        Map<String, double[]> map = classes(server);
        if (!map.containsKey(trimmed) && map.size() >= MAX_CLASSES) {
            return false;
        }
        // 決まりとして読み直した値で持つ（範囲の外・数でない値は丸める）。届いた値をそのまま書くと、
        // NaN が入っていたら JSON に書けずに保存が止まった
        map.put(trimmed, Regulation.fromArray(regulation).toArray());
        save(server);
        return true;
    }

    /**
     * 並びの中で 1 つ動かす（{@code direction} が負なら前へ、正なら後ろへ）。並び順は一覧・ランキングの順になる。
     *
     * @return 動かしたか（端で動かせない・名前が無いときは false）
     */
    public static boolean move(MinecraftServer server, String name, int direction) {
        Map<String, double[]> map = classes(server);
        List<String> names = new ArrayList<>(map.keySet());
        int from = names.indexOf(name);
        int to = from + Integer.signum(direction);
        if (from < 0 || direction == 0 || to < 0 || to >= names.size()) {
            return false;
        }
        names.remove(from);
        names.add(to, name);
        Map<String, double[]> reordered = new LinkedHashMap<>();
        for (String key : names) {
            reordered.put(key, map.get(key));
        }
        classes = reordered;
        save(server);
        return true;
    }

    /** 消す。 */
    public static boolean remove(MinecraftServer server, String name) {
        if (classes(server).remove(name) == null) {
            return false;
        }
        save(server);
        return true;
    }

    /** サーバーが止まったら捨てる（次に入ったワールドで読み直す）。 */
    public static void clear() {
        loadedFor = null;
        classes = new LinkedHashMap<>();
    }
}
