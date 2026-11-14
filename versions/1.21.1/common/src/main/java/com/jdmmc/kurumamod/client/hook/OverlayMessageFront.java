package com.jdmmc.kurumamod.client.hook;

import com.jdmmc.kurumamod.mixin.GuiAccessor;
import com.jdmmc.kurumamod.platform.McUiScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * アクションバーを、この MOD の HUD（メーター・操作ヘルプ・レースの HUD）より<b>いつも手前に</b>描く。
 *
 * <p>素の順番ではアクションバーの層のあとに HUD 全体の後（{@code RenderGuiEvent.Post}）が来るので、メーターや
 * ヘルプの下に隠れていた。バニラの層では描かせず（{@code OverlayMessageMixin} が取り消す）、HUD を描き終えた
 * 最後にここで描く。</p>
 */
public final class OverlayMessageFront {

    /** いまここから描いているか。バニラの層からの呼び出しは取り消す。 */
    private static boolean drawing;

    private OverlayMessageFront() {
    }

    public static boolean drawing() {
        return drawing;
    }

    /** HUD をすべて描いたあとに呼ぶ。この MOD の画面を開いているなら、画面の上で描くので（{@link #drawOverScreen}）ここでは描かない。 */
    public static void draw(GuiRender event) {
        if (Minecraft.getInstance().screen instanceof McUiScreen) {
            return;
        }
        drawNow(event.getGuiGraphics(), event.getPartialTick());
    }

    /**
     * この MOD の画面（プリセット・G・換装など）の上で描く。HUD のアクションバーは画面の下に隠れ、プリセットが
     * 読まれなかった・部品を付けなかった、の知らせが読めなかった（2026-11-14）。{@link McUiScreen} が最後に呼ぶ。
     */
    public static void drawOverScreen(GuiGraphics graphics) {
        drawNow(graphics, Minecraft.getInstance().getTimer());
    }

    private static void drawNow(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        // 先に描いたものと同じ深さだと、まとめて描くときの順番で負けることがある。手前へずらしてから描く
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        drawing = true;
        try {
            ((GuiAccessor) mc.gui).kurumamod$renderOverlayMessage(graphics, delta);
        } finally {
            drawing = false;
            graphics.pose().popPose();
        }
    }
}
