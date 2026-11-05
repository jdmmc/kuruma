package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.hook.CommonHooks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge のサーバー側のイベントを受けて、共有側（{@link CommonHooks}）へ渡すだけのつなぎ。
 * Fabric 版の同じ役目は fabric-1.21.1/ の {@code KurumamodFabric}。
 */
@EventBusSubscriber(modid = Kurumamod.MODID)
public final class NeoForgeCommonEvents {

    private NeoForgeCommonEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommonHooks.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CommonHooks.serverTickPost(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CommonHooks.serverStopped();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        CommonHooks.playerLoggedIn(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CommonHooks.playerLoggedOut(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        CommonHooks.playerChangedDimension(event.getEntity(), event.getFrom());
    }
}
