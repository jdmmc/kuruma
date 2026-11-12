package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.ScreenStyle;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.ClientConfig;

import java.util.List;

/**
 * 操作ヘルプ。<b>はじめて乗る人が、何ができるのかを画面から知れるようにするためのもの。</b>
 *
 * <p>置き場所は右下、{@link CarHud} の速度計の上。運転中いちばん目が行く場所の近くに置くが、
 * 速度計より手前には出さない。</p>
 *
 * <p><b>キーは版が {@code KeyMapping} から引く（{@link CarClient#helpLines}）。</b>文字で「G」と書いてしまうと、キー割り当てを
 * 変えた人に嘘を表示することになる。</p>
 *
 * <p><b>運転しているときにしか出さない。</b>降りている人に「アクセル」を出しても仕方がなく、
 * 歩いている間ずっと出ていると視界の邪魔にしかならない（以前は設定で切り替えられたが、
 * 切る以外の使い道が無かったので消した）。</p>
 *
 * <p>出すかどうかは H キーのメニュー（{@code KurumaMenuScreen}）で切り替える。
 * <b>テレメトリの表示とは独立。</b>数字を消したい人と、操作を覚えて一覧を消したい人は別だから。</p>
 */
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

    /** HUD をすべて描き終えた後に呼ぶ（版の {@code ClientHooks#renderGuiPost}）。 */
    public static void onRenderGui(UiGraphics graphics) {
        if (!ClientConfig.showHelp || RaceHud.isShowingResults()) {
            return;
        }
        // 換装・セッティング画面が開いている間は引っ込める（メーターと同じ理由）
        if (ScreenStyle.isCarScreen()) {
            return;
        }
        RaceClient client = RaceClient.get();
        if (client.hideGui()) {
            return;
        }

        // 運転しているときだけ。降りている人に「アクセル」を出しても仕方がなく、
        // 歩いている間ずっと出ていると視界の邪魔にしかならない
        if (CarClient.get().ridingHud() == null || !CarClient.get().isDriver()) {
            return;
        }
        render(graphics, client.font(), client.guiWidth(), client.guiHeight());
    }

    private static void render(UiGraphics graphics, UiFont font, int screenWidth, int screenHeight) {
        List<CarClient.HelpLine> lines = CarClient.get().helpLines();
        RaceText title = RaceText.translatable("help.kurumamod.title");
        RaceText hint = RaceText.translatable(HIDE_HINT);

        int keyWidth = 0;
        int textWidth = 0;
        for (CarClient.HelpLine line : lines) {
            keyWidth = Math.max(keyWidth, font.width(line.key()));
            textWidth = Math.max(textWidth, font.width(RaceText.translatable(line.descriptionKey())));
        }
        int contentWidth = Math.max(keyWidth + COLUMN_GAP + textWidth,
                Math.max(font.width(title), font.width(hint)));
        int panelWidth = contentWidth + PADDING * 2;
        // 見出しと注記のぶんで 2 行ぶん増える
        int panelHeight = (lines.size() + 2) * LINE_HEIGHT + PADDING * 2;


        // 画面の右下の角に固定する。メーターと重なってもずらさない（2026-11-10、ユーザーの指示）。
        // ホットバーとだけは重ねない（横に重なるときは、ホットバーの上まで持ち上げる）
        int left = screenWidth - MARGIN - panelWidth;
        int bottom = screenHeight - MARGIN - HotbarClearance.lift(left, left + panelWidth, screenWidth);
        int top = bottom - panelHeight;

        graphics.fill(left, top, left + panelWidth, bottom, COLOR_BACKGROUND);

        int textLeft = left + PADDING;
        int y = top + PADDING;
        graphics.drawString(font, title, textLeft, y, COLOR_TITLE, false);
        y += LINE_HEIGHT;

        for (CarClient.HelpLine line : lines) {
            graphics.drawString(font, line.key(), textLeft, y, COLOR_KEY, false);
            graphics.drawString(font, RaceText.translatable(line.descriptionKey()),
                    textLeft + keyWidth + COLUMN_GAP, y, COLOR_TEXT, false);
            y += LINE_HEIGHT;
        }

        graphics.drawString(font, hint, textLeft, y, COLOR_HINT, false);
    }
}
