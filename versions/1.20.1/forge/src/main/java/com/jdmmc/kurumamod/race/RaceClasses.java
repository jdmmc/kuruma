package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * クラス（名前を付けて保存したレギュレーション）の入口。中身は core の {@link RaceClassBook}。
 *
 * <p>ここは「どのサーバーのファイルか」を決めるだけ（ワールドのフォルダの {@code kurumamod/race_classes.json}）。
 * シングルプレイでワールドを替えたら読み直す。</p>
 */
public final class RaceClasses {

    public static final int MAX_NAME = RaceClassBook.MAX_NAME;
    public static final int MAX_CLASSES = RaceClassBook.MAX_CLASSES;

    private static MinecraftServer loadedFor;
    private static RaceClassBook book;

    private RaceClasses() {
    }

    /** そのサーバーのクラスの帳面。 */
    public static RaceClassBook book(MinecraftServer server) {
        if (loadedFor != server || book == null) {
            loadedFor = server;
            book = new RaceClassBook(server.getWorldPath(LevelResource.ROOT)
                    .resolve("kurumamod").resolve("race_classes.json"));
        }
        return book;
    }

    /** 保存してあるクラス（名前の付けた順）。記録の件数は全ワールドぶんを足す。 */
    public static List<RaceLobbyPacket.RaceClass> list(MinecraftServer server) {
        List<RaceLobbyPacket.RaceClass> out = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : book(server).all().entrySet()) {
            int records = 0;
            for (ServerLevel level : server.getAllLevels()) {
                records += RaceData.get(level).countClassBests(entry.getKey());
            }
            out.add(new RaceLobbyPacket.RaceClass(entry.getKey(), entry.getValue(), records));
        }
        return out;
    }

    public static List<String> names(MinecraftServer server) {
        return book(server).names();
    }

    public static Regulation regulation(MinecraftServer server, String name) {
        return book(server).regulation(name);
    }

    public static boolean put(MinecraftServer server, String name, double[] regulation) {
        return book(server).put(name, regulation);
    }

    public static boolean move(MinecraftServer server, String name, int direction) {
        return book(server).move(name, direction);
    }

    public static boolean remove(MinecraftServer server, String name) {
        return book(server).remove(name);
    }

    /** サーバーが止まったら捨てる（次に入ったワールドで読み直す）。 */
    public static void clear() {
        loadedFor = null;
        book = null;
    }
}
