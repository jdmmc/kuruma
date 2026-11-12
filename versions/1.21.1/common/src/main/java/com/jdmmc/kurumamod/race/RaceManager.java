package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.jdmmc.kurumamod.platform.McRaceServer;

/**
 * ラップ計測とレースの入口。<b>中身は core の {@link RaceEngine}</b>（Minecraft に触らない）。
 *
 * <p>ここは Minecraft のものを {@link McRaceServer} で包んで渡すだけ。呼ぶ側（{@code CommonHooks}・
 * {@code RaceCommands}・パケット）は、中身を core へ移す前と同じ名前・同じ引数で呼べる。</p>
 */
public final class RaceManager {

    /** 信号の灯の数。 */
    public static final int LIGHT_COUNT = RaceEngine.LIGHT_COUNT;

    /**
     * レースの状態。<b>プロセスが生きている限り残る</b>ので、サーバーが止まったら {@link #onServerStopped} で捨てる
     * （シングルプレイで別のワールドへ入ると、次元の名前はどちらも {@code minecraft:overworld} なので、
     * 前のワールドのレースがそのまま生き続ける）。
     */
    private static final RaceEngine ENGINE = new RaceEngine();
    /** コースを作るコマンドの中身（{@link RaceCommands} が呼ぶ）。 */
    private static final CourseAdmin ADMIN = new CourseAdmin(ENGINE);

    private RaceManager() {
    }

    /** サーバーのティックの終わり。 */
    public static void onServerTick(MinecraftServer server) {
        ENGINE.onServerTick(McRaceServer.of(server));
    }

    public static void onServerStopped() {
        ENGINE.onServerStopped();
        RaceClasses.clear();
        McRaceServer.clear();
    }

    public static void onChangedDimension(Player player, ResourceKey<Level> from) {
        if (player instanceof ServerPlayer serverPlayer) {
            ENGINE.onChangedDimension(McRaceServer.player(serverPlayer), McRaceServer.dimension(from));
        }
    }

    public static void onLogout(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ENGINE.onLogout(McRaceServer.player(serverPlayer));
        }
    }

    public static void handleAction(ServerPlayer player, RaceActionPacket packet) {
        ENGINE.handleAction(McRaceServer.player(player), packet);
    }

    /** いまレースの決まりに縛られている人の決まり。縛られていなければ null。 */
    public static Regulation restrictionFor(ServerPlayer player) {
        return ENGINE.restrictionFor(McRaceServer.player(player));
    }

    public static void sendLobby(ServerPlayer player) {
        ENGINE.sendLobby(McRaceServer.player(player));
    }

    public static void sendClasses(ServerPlayer player) {
        ENGINE.sendClasses(McRaceServer.player(player));
    }

    public static void classesToAll(MinecraftServer server) {
        ENGINE.classesToAll(McRaceServer.of(server));
    }

    public static void notifyCourseBestsCleared(ServerLevel level, String course) {
        ENGINE.notifyCourseBestsCleared(McRaceServer.level(level), course);
    }

    public static void onCourseChanged(ServerLevel level, String course) {
        ENGINE.onCourseChanged(McRaceServer.level(level), course);
    }

    public static void onCourseRemoved(ServerLevel level, String name) {
        ENGINE.onCourseRemoved(McRaceServer.level(level), name);
    }

    public static boolean raceOnCourse(ServerLevel level, String course) {
        return ENGINE.raceOnCourse(McRaceServer.level(level), course);
    }

    /** コースの線とレースの様子を配る（{@link RaceSync}）。 */
    static void syncTo(ServerPlayer player) {
        ENGINE.syncTo(McRaceServer.player(player));
    }

    static void syncAll(MinecraftServer server) {
        ENGINE.syncAll(McRaceServer.of(server));
    }

    static CourseAdmin admin() {
        return ADMIN;
    }

    public static String format(long millis) {
        return RaceEngine.format(millis);
    }
}
