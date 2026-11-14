package com.jdmmc.kurumamod.platform;

import com.jdmmc.kurumamod.Kurumamod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
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

    private McHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        McUiGraphics graphics = new McUiGraphics(event.getGuiGraphics());
        CarGaugeHud.onRenderGui(graphics);
        CarHelp.onRenderGui(graphics);
        CarHud.onRenderGui(graphics);
        // アクションバーはいちばん最後（この MOD の HUD より手前）
        drawOverlayMessage(event);
    }

    /**
     * アクションバーを、この MOD の HUD（メーター・操作ヘルプ・レースの HUD）より<b>いつも手前に</b>描く。
     *
     * <p>素の順番では HUD 全体の後（{@code RenderGuiEvent.Post}）がアクションバーより後に来るので、メーターや
     * ヘルプの下に隠れていた。Forge の層（{@code RECORD_OVERLAY}）では描かせず（{@link #onRenderOverlayPre}）、
     * ここで同じ層の中身を呼ぶ。</p>
     *
     * <p>上へ持ち上げる（{@link CarGaugeHud#overlayMessageY}）。1.20.1 のアクションバーは Forge の
     * {@code ForgeGui#renderRecordOverlay} が描き、高さは画面下端から
     * {@code max(max(leftHeight, rightHeight) + 9, 68)}（バイトコードで確認）。</p>
     */
    private static void drawOverlayMessage(RenderGuiEvent.Post event) {
        // この MOD の画面を開いているなら、画面の上で描く（drawOverScreen）
        if (Minecraft.getInstance().screen instanceof McUiScreen) {
            return;
        }
        drawOverlayMessage(event.getGuiGraphics(), event.getPartialTick());
    }

    /**
     * この MOD の画面（プリセット・G・換装など）の上で描く。HUD のアクションバーは画面の下に隠れ、プリセットが
     * 読まれなかった・部品を付けなかった、の知らせが読めなかった（2026-11-14）。{@link McUiScreen} が最後に呼ぶ。
     */
    public static void drawOverScreen(net.minecraft.client.gui.GuiGraphics graphics, float partialTick) {
        drawOverlayMessage(graphics, partialTick);
    }

    private static void drawOverlayMessage(net.minecraft.client.gui.GuiGraphics graphics, float partialTick) {
        if (!(Minecraft.getInstance().gui instanceof ForgeGui gui) || gui.getMinecraft().options.hideGui) {
            return;
        }
        int width = gui.getMinecraft().getWindow().getGuiScaledWidth();
        int height = gui.getMinecraft().getWindow().getGuiScaledHeight();
        float y = height - Math.max(Math.max(gui.leftHeight, gui.rightHeight) + 9, 68);
        float lift = Math.max(0.0F, y - CarGaugeHud.overlayMessageY(y));
        // 先に描いたものと同じ深さだと、まとめて描くときの順番で負けることがある。手前へずらしてから描く
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, -lift, 400.0F);
        try {
            VanillaGuiOverlay.RECORD_OVERLAY.type().overlay()
                    .render(gui, graphics, partialTick, width, height);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Forge の層ではアクションバーを描かせない（{@link #drawOverlayMessage} が最後に描く）。 */
    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() == VanillaGuiOverlay.RECORD_OVERLAY.type()) {
            event.setCanceled(true);
        }
    }
}
