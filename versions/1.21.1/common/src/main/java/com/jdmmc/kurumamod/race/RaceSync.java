package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.network.KurumaNetwork;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * コースの線をクライアントへ配る。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる。ログイン時、次元を移ったとき、
 * コースをいじったとき、レースの開始・終了時。</p>
 */
public final class RaceSync {

    private RaceSync() {
    }

    /** ログインしたとき・次元を移ったときにも呼ぶ（{@code CommonHooks}）。 */
    public static void sendTo(ServerPlayer player) {
        KurumaNetwork.sendToPlayer(player, build((ServerLevel) player.level()));
        // レースの様子も一緒に。入ったワールドで受付中なら、誘いと参加の口がすぐ出る
        RaceManager.sendLobby(player);
    }

    public static void sendToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    private static CourseLinesPacket build(ServerLevel level) {
        RaceData data = RaceData.get(level);
        List<CourseLinesPacket.Gate> gates = new ArrayList<>();
        // レース中はそのコースのゲートだけ。1 ワールドに複数コースがあるとき、
        // 関係ない線が視界に入ると走りにくい
        String only = RaceManager.racingCourse(level);
        for (Course course : data.courses()) {
            if (only != null && !only.equals(course.name())) {
                continue;
            }
            CourseLine start = course.start();
            if (start != null) {
                gates.add(toGate(start, true));
            }
            for (int i = 0; i < course.checkpointCount(); i++) {
                gates.add(toGate(course.checkpoint(i), false));
            }
        }
        return new CourseLinesPacket(RaceManager.isRacing(level), gates);
    }

    private static CourseLinesPacket.Gate toGate(CourseLine line, boolean start) {
        // 判定は足元の 2 ブロック下から始まるが、ゲートは足元から上へ描く
        return new CourseLinesPacket.Gate(start, line.ax(), line.az(), line.bx(), line.bz(),
                line.yMin() + 2.0, line.yMax());
    }
}
