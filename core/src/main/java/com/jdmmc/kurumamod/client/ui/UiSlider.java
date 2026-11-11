package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * つまみを動かすスライダー。バニラの {@code AbstractSliderButton} の代わり（名前と作りも同じ）。
 *
 * <p>{@link #value} は 0〜1。動かされると版の画面が {@link #moved} を呼び、{@link #applyValue}・{@link #updateMessage}
 * が呼ばれる。core の側から {@link #value} を書き換えたら、表示を変えるために自分で {@link #updateMessage} を呼ぶこと
 * （バニラと同じ）。</p>
 */
public abstract class UiSlider extends UiWidget {

    protected double value;

    protected UiSlider(int x, int y, int width, int height, RaceText message, double value) {
        super(x, y, width, height, message);
        this.value = value;
    }

    public double value() {
        return value;
    }

    /** 版の画面が、つまみを動かしたときに呼ぶ。 */
    public void moved(double newValue) {
        double clamped = Math.max(0.0, Math.min(1.0, newValue));
        if (clamped != value) {
            value = clamped;
            applyValue();
        }
        updateMessage();
    }

    /** つまみを離した。 */
    public void onRelease(double mouseX, double mouseY) {
    }

    protected abstract void updateMessage();

    protected abstract void applyValue();
}
