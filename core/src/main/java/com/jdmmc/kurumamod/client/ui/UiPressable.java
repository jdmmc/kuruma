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

    public abstract void onPress();

    public abstract void renderWidget(UiGraphics graphics, int mouseX, int mouseY, float partialTick);
}
