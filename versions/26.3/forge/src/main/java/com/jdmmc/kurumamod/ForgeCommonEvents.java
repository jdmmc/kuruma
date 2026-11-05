package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.car.CarTypeLoader;
import com.jdmmc.kurumamod.hook.CommonHooks;
import com.jdmmc.kurumamod.part.CarPartLoader;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Forge のサーバー側のイベントを受けて、共有側（{@link CommonHooks}）へ渡すだけのつなぎ。
 * NeoForge 版の同じ役目は {@code NeoForgeCommonEvents} と {@code Kurumamod} のハンドラ、Fabric 版は {@code Kurumamod#onInitialize}。
 */
final class ForgeCommonEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ForgeCommonEvents() {
    }

    static void register() {
        ServerStartingEvent.BUS.addListener(event -> {
            LOGGER.info("サーバー起動処理を実行します");
            CommonHooks.serverStarting();
        });
        ServerStoppedEvent.BUS.addListener(event -> CommonHooks.serverStopped());
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> CommonHooks.serverTickPost(event.server()));
        RegisterCommandsEvent.BUS.addListener(event -> CommonHooks.registerCommands(event.getDispatcher()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> CommonHooks.playerLoggedIn(event.getEntity()));
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> CommonHooks.playerLoggedOut(event.getEntity()));
        PlayerEvent.PlayerChangedDimensionEvent.BUS.addListener(event ->
                CommonHooks.playerChangedDimension(event.getEntity(), event.getFrom()));

        // データパックから車種と部品を読む。素性は data/<ns>/cars・car_parts/*.json
        AddReloadListenerEvent.BUS.addListener(event -> {
            event.addListener(new CarTypeLoader());
            event.addListener(new CarPartLoader());
        });
        // ログイン時は本人へ、/reload 時は全員へ（NeoForge の OnDatapackSyncEvent と同じ場面）
        OnDatapackSyncEvent.BUS.addListener(event -> event.getPlayers().forEach(CommonHooks::syncDataPack));
    }
}
