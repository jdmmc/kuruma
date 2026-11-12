package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.jdmmc.kurumamod.client.hud.CarGaugeHud;
import com.jdmmc.kurumamod.client.hud.CarHelp;
import com.jdmmc.kurumamod.client.hud.CarHud;

/**
 * 運転中の HUD（core の {@link CarGaugeHud}・{@link CarHelp}・{@link CarHud}）を Forge のイベントで描く。
 * 1.21.1・26.3 では {@code ClientHooks#renderGuiPost} が同じことをする。
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class McHud {

    /** アクションバーを持ち上げるために行列を積んだか。Post で戻す。 */
    private static boolean overlayMessageLifted;

    private McHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        McUiGraphics graphics = new McUiGraphics(event.getGuiGraphics());
        CarGaugeHud.onRenderGui(graphics);
        CarHelp.onRenderGui(graphics);
        CarHud.onRenderGui(graphics);
    }

    /**
     * アクションバーを持ち上げる（{@link CarGaugeHud#overlayMessageY}）。
     *
     * <p>1.20.1 のアクションバーは Forge の {@code ForgeGui#renderRecordOverlay} が描き、高さは
     * 画面下端から {@code max(max(leftHeight, rightHeight) + 9, 68)}（バイトコードで確認）。
     * その前後で行列を積んで上へずらす。<b>いちばん後の優先度で受ける</b>——ほかの MOD が Pre を
     * 取り消すと Post が飛ばないので、取り消されないと分かってから積む。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.RECORD_OVERLAY.type()
                || !(Minecraft.getInstance().gui instanceof ForgeGui gui)) {
            return;
        }
        float y = event.getWindow().getGuiScaledHeight()
                - Math.max(Math.max(gui.leftHeight, gui.rightHeight) + 9, 68);
        float lift = y - CarGaugeHud.overlayMessageY(y);
        if (lift <= 0.0F) {
            return;
        }
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0F, -lift, 0.0F);
        overlayMessageLifted = true;
    }

    @SubscribeEvent
    public static void onRenderOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.RECORD_OVERLAY.type() || !overlayMessageLifted) {
            return;
        }
        overlayMessageLifted = false;
        event.getGuiGraphics().pose().popPose();
    }
}
