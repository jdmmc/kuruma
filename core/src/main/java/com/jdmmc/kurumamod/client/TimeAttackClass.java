package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.Slant;
import com.jdmmc.kurumamod.client.ui.UiButton;
import com.jdmmc.kurumamod.client.ui.UiEditBox;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.client.ui.UiKey;
import com.jdmmc.kurumamod.client.ui.UiPressable;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.client.ui.UiTooltip;
import com.jdmmc.kurumamod.race.RaceCar;
import com.jdmmc.kurumamod.race.RaceEngine;
import com.jdmmc.kurumamod.race.RaceText;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.tuning.Regulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * タイムアタックで宣言しているクラス。<b>クライアントがサーバー（ワールド）ごとに覚えておく</b>
 * （{@code config/kurumamod-timeattack.json}）。
 *
 * <p>デルタとベストはサーバーが出すので、宣言はサーバーへも伝える（{@link RaceActionPacket.Action#DECLARE_CLASS}）。
 * サーバーは落ちた人のぶんを捨てるので、入るたびに送り直す。</p>
 *
 * <p>サーバーの返事（{@link RaceLobbyPacket#declaredClass}）が正。クラスが消されたら空で返ってくるので、
 * こちらも忘れる。ただし<b>送った直後の様子はまだ古い</b>（サーバーが受け取る前に配ったもの）ので、
 * 送った名前が返ってくるまで（長くて {@link #CONFIRM_TICKS}）は合わせにいかない。</p>
 */
public final class TimeAttackClass {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TimeAttackClass.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 送ってから返事を待つ長さ [ティック]。 */
    private static final int CONFIRM_TICKS = 60;

    private static Map<String, String> remembered;
    /** 入ってから最初の様子を受け取ったか（受け取ったら送り直す）。 */
    private static boolean synced;
    private static String awaiting;
    private static int awaitTicks;
    /** サーバーが認めている宣言（空なら宣言していない）。 */
    private static String declared = "";

    private TimeAttackClass() {
    }

    private static Path file() {
        return RaceClient.get().configDir().resolve("kurumamod-timeattack.json");
    }

    private static Map<String, String> remembered() {
        if (remembered == null) {
            remembered = new HashMap<>();
            Path path = file();
            if (Files.exists(path)) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                        remembered.put(entry.getKey(), entry.getValue().getAsString());
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("タイムアタックのクラスの宣言を読めませんでした: {}", path, e);
                }
            }
        }
        return remembered;
    }

    private static void remember(String name) {
        Map<String, String> map = remembered();
        String key = RaceClient.get().worldKey();
        if (name.isEmpty() ? map.remove(key) == null : name.equals(map.put(key, name))) {
            return;
        }
        JsonObject root = new JsonObject();
        map.forEach(root::addProperty);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("タイムアタックのクラスの宣言を保存できませんでした: {}", file(), e);
        }
    }

    /** 宣言しているクラスの名前。宣言していなければ空。 */
    public static String declared() {
        return awaiting != null ? awaiting : declared;
    }

    /** 宣言しているクラスの決まり。宣言していない（クラスが見つからない）なら null。 */
    public static Regulation regulation() {
        String name = declared();
        if (name.isEmpty()) {
            return null;
        }
        for (RaceLobbyPacket.RaceClass raceClass : RaceLobby.classes()) {
            if (raceClass.name().equals(name)) {
                return Regulation.fromArray(raceClass.regulation());
            }
        }
        return null;
    }

    /** このクラスで走る（空ならやめる）。 */
    public static void declare(String name) {
        String trimmed = name == null ? "" : name.trim();
        remember(trimmed);
        send(trimmed);
    }

    private static void send(String name) {
        awaiting = name;
        awaitTicks = CONFIRM_TICKS;
        RaceClient.get().sendToServer(new RaceActionPacket(RaceActionPacket.Action.DECLARE_CLASS, name, 0,
                new double[0]));
    }

    /** レースの様子を受け取った（{@link RaceLobby#accept}）。 */
    static void accept(RaceLobbyPacket packet) {
        String fromServer = packet.declaredClass();
        if (!synced) {
            synced = true;
            declared = fromServer;
            String want = remembered().getOrDefault(RaceClient.get().worldKey(), "");
            if (!want.isEmpty() && !want.equals(fromServer)) {
                send(want);
            }
            return;
        }
        if (awaiting != null) {
            // 送ったクラスがもうサーバーに無いなら、待っても返ってこない（サーバーは空で返す）
            boolean exists = awaiting.isEmpty() || RaceLobby.classes().stream()
                    .anyMatch(raceClass -> raceClass.name().equals(awaiting));
            if (!awaiting.equals(fromServer) && awaitTicks > 0 && exists) {
                return;
            }
            boolean refused = !awaiting.equals(fromServer);
            awaiting = null;
            if (refused) {
                // 送った宣言をサーバーが受けなかった（ログアウト中にクラスが消された、など）。<b>覚えた宣言も消す</b>——
                // 下の「変わったら覚える」は declared がもう同じ値だと通らず、入るたびに送り直していた
                declared = fromServer;
                remember(fromServer);
                return;
            }
        }
        if (!fromServer.equals(declared)) {
            declared = fromServer;
            // サーバーで消された（クラスの削除）。こちらも忘れる
            remember(fromServer);
        }
    }

    public static void onClientTick() {
        if (awaitTicks > 0) {
            awaitTicks--;
        }
    }

    static void reset() {
        synced = false;
        awaiting = null;
        awaitTicks = 0;
        declared = "";
    }
}
