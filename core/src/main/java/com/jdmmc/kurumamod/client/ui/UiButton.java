package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * バニラの見た目のボタン。バニラの {@code Button} の代わり（作り方も {@link #builder} で同じ）。
 *
 * <p>地は版の画面がバニラのまま描く。文字を自分で描きたいとき（ドロップダウンの「▼」）は
 * {@link #customLabel} で true を返し、{@link #renderLabel} を上書きする。</p>
 */
public class UiButton extends UiWidget {

    public interface OnPress {
        void onPress(UiButton button);
    }

    private final OnPress onPress;

    public UiButton(int x, int y, int width, int height, RaceText message, OnPress onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    public void onPress() {
        onPress.onPress(this);
    }

    /** 文字を自分で描くか（false ならバニラのまま文字を描く）。 */
    public boolean customLabel() {
        return false;
    }

    /**
     * 地の上の文字を描く（{@link #customLabel} が true のときだけ呼ばれる）。
     *
     * @param color 文字の色（押せないときは灰色）。版が渡す値
     */
    public void renderLabel(UiGraphics graphics, UiFont font, int color) {
    }

    public static Builder builder(RaceText message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {

        private final RaceText message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private UiTooltip tooltip;

        private Builder(RaceText message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder tooltip(UiTooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public UiButton build() {
            UiButton button = new UiButton(x, y, width, height, message, onPress);
            button.setTooltip(tooltip);
            return button;
        }
    }
}
