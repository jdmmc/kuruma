package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * コースの線（とレースの様子）をクライアントへ配る口。中身は {@link RaceEngine#syncTo}。
 *
 * <p>線は動かないので、変わったときだけ送れば足りる。ログイン時、次元を移ったとき、コースをいじったとき。</p>
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
        RaceManager.syncTo(player);
    }

    public static void sendToAll(MinecraftServer server) {
        RaceManager.syncAll(server);
    }
}
