package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作ヘルプ。<b>はじめて乗る人が、何ができるのかを画面から知れるようにするためのもの。</b>
 *
 * <p>置き場所は右下、{@link CarHud} の速度計の上。運転中いちばん目が行く場所の近くに置くが、
 * 速度計より手前には出さない。</p>
 *
 * <p><b>キーは {@link KeyMapping} から引く。</b>文字で「G」と書いてしまうと、キー割り当てを
 * 変えた人に嘘を表示することになる。</p>
 *
 * <p><b>運転しているときにしか出さない。</b>降りている人に「アクセル」を出しても仕方がなく、
 * 歩いている間ずっと出ていると視界の邪魔にしかならない（以前は設定で切り替えられたが、
 * 切る以外の使い道が無かったので消した）。</p>
 *
 * <p>出すかどうかは H キーのメニュー（{@code KurumaMenuScreen}）で切り替える。
 * <b>テレメトリの表示とは独立。</b>数字を消したい人と、操作を覚えて一覧を消したい人は別だから。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarHelp {

    private static final int MARGIN = 6;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    /** キーの列と説明の列の間隔。 */
    private static final int COLUMN_GAP = 6;

    private static final int COLOR_BACKGROUND = 0x90000000;
    private static final int COLOR_KEY = 0xFFFFD26A;
    private static final int COLOR_TEXT = 0xFFC8C8C8;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    /** 注記。操作の一覧そのものより目立たせない */
    private static final int COLOR_HINT = 0xFF8A8A8A;

    /**
     * 一覧の下に出す注記。<b>この一覧自体を消せることを、この一覧の中で知らせる。</b>
     *
     * <p>設定画面を開いてはじめて「消せる」と分かるのでは順序が逆で、
     * 邪魔だと思った人は設定を探す前に諦めてしまう。</p>
     */
    private static final String HIDE_HINT = "help.kurumamod.hide_hint";

    private CarHelp() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientConfig.showHelp) {
            return;
        }
        // 換装・セッティング画面が開いている間は引っ込める（メーターと同じ理由）
        if (ScreenStyle.isCarScreen()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        // 運転しているときだけ。降りている人に「アクセル」を出しても仕方がなく、
        // 歩いている間ずっと出ていると視界の邪魔にしかならない
        if (!(minecraft.player.getVehicle() instanceof CarEntity car)
                || car.getControllingPassenger() != minecraft.player) {
            return;
        }
        render(event.getGuiGraphics(), minecraft);
    }

    /** 中身。運転しているときにしか出さないので、運転の操作だけを並べる。 */
    private static List<Line> lines(Minecraft minecraft) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(key(minecraft.options.keyUp) + "/" + key(minecraft.options.keyDown),
                "help.kurumamod.pedals"));
        lines.add(new Line(key(minecraft.options.keyLeft) + "/" + key(minecraft.options.keyRight),
                "help.kurumamod.steer"));
        lines.add(new Line(key(minecraft.options.keyJump), "help.kurumamod.handbrake"));
        lines.add(new Line(key(KurumaKeys.SHIFT_UP) + "/" + key(KurumaKeys.SHIFT_DOWN),
                "help.kurumamod.shift"));
        lines.add(new Line(key(minecraft.options.keyShift), "help.kurumamod.dismount"));
        lines.add(new Line(key(KurumaKeys.OPEN_TUNING), "help.kurumamod.tuning"));
        lines.add(new Line(key(KurumaKeys.OPEN_MENU), "help.kurumamod.menu"));
        lines.add(new Line(key(KurumaKeys.TOGGLE_HEADLIGHTS), "help.kurumamod.headlights"));
        lines.add(new Line(key(KurumaKeys.TOGGLE_CAMERA), "help.kurumamod.camera"));
        lines.add(new Line(key(KurumaKeys.CAMERA_CLOSER) + "/" + key(KurumaKeys.CAMERA_FARTHER),
                "help.kurumamod.camera_distance"));
        lines.add(new Line(key(KurumaKeys.OPEN_CONTROLLER), "help.kurumamod.controller"));
        return lines;
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft) {
        List<Line> lines = lines(minecraft);
        Component title = Component.translatable("help.kurumamod.title");
        Component hint = Component.translatable(HIDE_HINT);

        int keyWidth = 0;
        int textWidth = 0;
        for (Line line : lines) {
            keyWidth = Math.max(keyWidth, minecraft.font.width(line.key));
            textWidth = Math.max(textWidth, minecraft.font.width(Component.translatable(line.descriptionKey)));
        }
        int contentWidth = Math.max(keyWidth + COLUMN_GAP + textWidth,
                Math.max(minecraft.font.width(title), minecraft.font.width(hint)));
        int panelWidth = contentWidth + PADDING * 2;
        // 見出しと注記のぶんで 2 行ぶん増える
        int panelHeight = (lines.size() + 2) * LINE_HEIGHT + PADDING * 2;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        // 速度計の上へ。テレメトリを消しているときは速度計も出ないので、下まで下ろす
        int left = screenWidth - MARGIN - panelWidth;
        // メーターに重なるときだけ上へ逃がす。画面が広くて横に並べられるなら下端のままでよい
        int bottom = screenHeight - MARGIN
                - (left < CarGaugeHud.gaugeRightEdge() ? CarGaugeHud.reservedBottomHeight() : 0);
        int top = bottom - panelHeight;

        graphics.fill(left, top, left + panelWidth, bottom, COLOR_BACKGROUND);

        int textLeft = left + PADDING;
        int y = top + PADDING;
        graphics.drawString(minecraft.font, title, textLeft, y, COLOR_TITLE, false);
        y += LINE_HEIGHT;

        for (Line line : lines) {
            graphics.drawString(minecraft.font, line.key, textLeft, y, COLOR_KEY, false);
            graphics.drawString(minecraft.font, Component.translatable(line.descriptionKey),
                    textLeft + keyWidth + COLUMN_GAP, y, COLOR_TEXT, false);
            y += LINE_HEIGHT;
        }

        graphics.drawString(minecraft.font, hint, textLeft, y, COLOR_HINT, false);
    }

    /** キーの表示名。割り当てを変えたらそのまま追従する。 */
    private static String key(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    /** 一覧の 1 行。 */
    private record Line(String key, String descriptionKey) {
    }
}
