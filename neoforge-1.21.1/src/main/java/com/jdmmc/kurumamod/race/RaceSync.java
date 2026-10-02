package com.jdmmc.kurumamod.race;

import net.neoforged.neoforge.network.PacketDistributor;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;

/**
 * コースの線をクライアントへ配る。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる。ログイン時、次元を移ったとき、
 * コースをいじったとき、レースの開始・終了時。</p>
 */
@EventBusSubscriber(modid = Kurumamod.MODID)
public final class RaceSync {

    private RaceSync() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    public static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, build((ServerLevel) player.level()));
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
