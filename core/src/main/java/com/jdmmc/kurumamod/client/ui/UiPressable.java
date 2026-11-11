package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * 自分で描く押せる部品（タブ・入れ切りの四角・札・一覧の行）。バニラの {@code AbstractButton} の代わり。
 *
 * <p>版の画面はクリックとキー（Enter・Space）で {@link #onPress} を呼び、描くときに {@link #renderWidget} を呼ぶ。
 * 押したときのクリックの音は版の側が鳴らす（バニラの部品と同じ）。</p>
 */
public abstract class UiPressable extends UiWidget {

    protected UiPressable(int x, int y, int width, int height, RaceText message) {
        super(x, y, width, height, message);
    }

    /** 押された（Enter・Space も）。何もしない部品（位置つきの {@link #onClick} だけを使う）もある。 */
    public void onPress() {
    }

    /** 押された（左クリック。位置つき）。既定は {@link #onPress}。 */
    public void onClick(double mouseX, double mouseY) {
        onPress();
    }

    /** ボタンを離した。 */
    public void onRelease(double mouseX, double mouseY) {
    }

    /** 押したまま動かした。 */
    public void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
    }

    public abstract void renderWidget(UiGraphics graphics, int mouseX, int mouseY, float partialTick);
}
