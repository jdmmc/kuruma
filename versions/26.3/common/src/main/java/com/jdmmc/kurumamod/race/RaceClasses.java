package com.jdmmc.kurumamod.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
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
                JsonArray array = entry.getValue().getAsJsonArray();
                double[] values = new double[array.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = array.get(i).getAsDouble();
                }
                out.put(entry.getKey(), values);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("クラスを読めませんでした: {}", path, e);
        }
        return out;
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
        } catch (IOException e) {
            LOGGER.warn("クラスを保存できませんでした: {}", path, e);
        }
    }

    /** 保存してあるクラス（名前の付けた順）。 */
    public static List<RaceLobbyPacket.RaceClass> list(MinecraftServer server) {
        List<RaceLobbyPacket.RaceClass> out = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : classes(server).entrySet()) {
            out.add(new RaceLobbyPacket.RaceClass(entry.getKey(), entry.getValue().clone()));
        }
        return out;
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
        map.put(trimmed, regulation.clone());
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
