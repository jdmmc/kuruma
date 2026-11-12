package com.jdmmc.kurumamod.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.jdmmc.kurumamod.client.hud.RaceHud;
import com.jdmmc.kurumamod.client.race.RaceLobby;

/**
 * レースのクライアント側（core の {@link RaceLobby}・{@link RaceHud}・{@link GhostEngine}）へ Forge のイベントを渡す。
 * 1.21.1 の {@code ClientHooks} の該当する行と同じ順に呼ぶ。
 */
@Mod.EventBusSubscriber(modid = com.jdmmc.kurumamod.Kurumamod.MODID, value = Dist.CLIENT)
public final class RaceClientEvents {

    static {
        McRaceClient.install();
    }

    private RaceClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 車がティックする前に流し込む。lerpTo の位置へはその車のティックで寄る
            CarGhosts.onClientTickPre();
            return;
        }
        // 車がティックし終えて、送った状態が出揃ってから取る
        CarGhosts.onClientTickPost();
        RaceHud.onClientTick();
        RaceLobby.onClientTick();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        RaceHud.onLoggingIn();
        RaceLobby.onLoggingIn();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CarGhosts.onLoggingOut();
        RaceHud.onLoggingOut();
        RaceLobby.onLoggingOut();
    }

    /** ホットバーの層を描き終えた直後（チャットなど後ろの層より下に描くため）。 */
    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
            return;
        }
        McUiGraphics graphics = new McUiGraphics(event.getGuiGraphics());
        RaceHud.onRenderOverlay(graphics, event.getPartialTick());
        RaceLobby.onRenderOverlay(graphics);
    }
}
