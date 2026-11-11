package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * コースの線をクライアントへ配る。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる。ログイン時、次元を移ったとき、
 * コースをいじったとき。<b>全員に同じものを送る</b>（どれを描くかはクライアントが決める。{@code CourseLinesPacket}）。
 * レースの様子も一緒に送るので、レースの開始・終了時にも呼んでいる。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID)
public final class RaceSync {

    private RaceSync() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // クラスの一覧はレースの様子より先に（宣言したクラスがまだあるかを、届いた一覧で確かめるため）
            RaceManager.sendClasses(player);
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
        sendLines(player);
        // レースの様子も一緒に。入ったワールドで受付中なら、誘いと参加の口がすぐ出る
        RaceManager.sendLobby(player);
    }

    private static void sendLines(ServerPlayer player) {
        KurumaNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                build((ServerLevel) player.level()));
    }

    public static void sendToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    private static CourseLinesPacket build(ServerLevel level) {
        RaceData data = RaceData.get(level);
        List<CourseLinesPacket.Gate> gates = new ArrayList<>();
        for (Course course : data.courses()) {
            CourseLine start = course.start();
            if (start != null) {
                gates.add(toGate(course.name(), start, true));
            }
            for (int i = 0; i < course.checkpointCount(); i++) {
                gates.add(toGate(course.name(), course.checkpoint(i), false));
            }
        }
        return new CourseLinesPacket(gates);
    }

    private static CourseLinesPacket.Gate toGate(String course, CourseLine line, boolean start) {
        // 判定は足元の 2 ブロック下から始まるが、ゲートは足元から上へ描く
        return new CourseLinesPacket.Gate(course, start, line.ax(), line.az(), line.bx(), line.bz(),
                line.yMin() + 2.0, line.yMax());
    }
}
