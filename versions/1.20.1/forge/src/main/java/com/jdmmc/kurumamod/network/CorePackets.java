package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.client.CarClientPackets;
import com.jdmmc.kurumamod.race.RaceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * core に置いたパケット（{@link RacePayload}）が届いたときの処理（Forge 1.20.1）。
 *
 * <p>SimpleChannel はどんなクラスでも運べるので、包みは要らない。{@link KurumaNetwork} が core のレコードを
 * そのまま登録し、読み書きは {@link FriendlyRaceBuf} を通す。</p>
 */
public final class CorePackets {

    private CorePackets() {
    }

    public static void handleRaceEvent(RaceEventPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyRaceEvent(packet));
    }

    public static void handleCourseLines(CourseLinesPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyCourseLines(packet));
    }

    public static void handleStandings(RaceStandingsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyStandings(packet));
    }

    public static void handleRaceLobby(RaceLobbyPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyRaceLobby(packet));
    }

    public static void handleRaceClasses(RaceClassesPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyRaceClasses(packet));
    }

    public static void handleTimeAttackRecords(TimeAttackRecordsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toClient(ctx, () -> CarClientPackets.applyTimeAttackRecords(packet));
    }

    public static void handleRaceAction(RaceActionPacket packet, Supplier<NetworkEvent.Context> ctx) {
        toServer(ctx, player -> RaceManager.handleAction(player, packet));
    }

    /** クライアント専用のクラスを専用サーバーで読み込ませないため、Dist で包む。 */
    private static void toClient(Supplier<NetworkEvent.Context> ctx, Runnable task) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> task));
        context.setPacketHandled(true);
    }

    private static void toServer(Supplier<NetworkEvent.Context> ctx, Consumer<ServerPlayer> task) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                task.accept(player);
            }
        });
        context.setPacketHandled(true);
    }
}
