package com.jdmmc.kurumamod.race;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * コースの線（とレースの様子）をクライアントへ配る口。中身は {@link RaceEngine#syncTo}。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる。ログイン時、次元を移ったとき、コースをいじったとき。</p>
 */
public final class RaceSync {

    private RaceSync() {
    }

    /** ログインしたとき・次元を移ったときにも呼ぶ（{@code CommonHooks}）。 */
    public static void sendTo(ServerPlayer player) {
        RaceManager.syncTo(player);
    }

    public static void sendToAll(MinecraftServer server) {
        RaceManager.syncAll(server);
    }
}
