package com.jdmmc.kurumamod.client.hook;

import com.jdmmc.kurumamod.mixin.HudAccessor;
import com.jdmmc.kurumamod.platform.McUiScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * アクションバーを、この MOD の HUD（メーター・操作ヘルプ・レースの HUD）より<b>いつも手前に</b>描く。
 *
 * <p>26.3 の GUI は描いた順で重なり、この MOD の HUD はアクションバーの後に描くので、メーターやヘルプの下に
 * 隠れていた。バニラの順番では描かせず（{@code OverlayMessageMixin} が取り消す）、HUD を描き終えた最後に
 * ここで描く。</p>
 */
public final class OverlayMessageFront {

    /** いまここから描いているか。バニラの順番からの呼び出しは取り消す。 */
    private static boolean drawing;

    private OverlayMessageFront() {
    }

    public static boolean drawing() {
        return drawing;
    }

    /** HUD をすべて描いたあとに呼ぶ。この MOD の画面を開いているなら、画面の上で描くので（{@link #drawOverScreen}）ここでは描かない。 */
    public static void draw(GuiRender event) {
        if (Minecraft.getInstance().gui.screen() instanceof McUiScreen) {
            return;
        }
        drawNow(event.getGuiGraphics(), event.getPartialTick());
    }

    /**
     * この MOD の画面（プリセット・G・換装など）の上で描く。HUD のアクションバーは画面の下に隠れ、プリセットが
     * 読まれなかった・部品を付けなかった、の知らせが読めなかった（2026-11-14）。{@link McUiScreen} が最後に呼ぶ。
     */
    public static void drawOverScreen(GuiGraphicsExtractor graphics) {
        drawNow(graphics, Minecraft.getInstance().getDeltaTracker());
    }

    private static void drawNow(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden()) {
            return;
        }
        drawing = true;
        try {
            ((HudAccessor) mc.gui.hud).kurumamod$extractOverlayMessage(graphics, delta);
        } finally {
            drawing = false;
        }
    }
}
