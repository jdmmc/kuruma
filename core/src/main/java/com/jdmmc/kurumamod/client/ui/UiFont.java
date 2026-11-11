package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * 文字の幅を測る道具。バニラの {@code Font} の代わり（版ごとの {@code McUiFont} が中身を持つ）。
 *
 * <p>名前はバニラに揃えてある（{@link #width}・{@link #lineHeight}）。</p>
 */
public abstract class UiFont {

    /** 1 行の高さ [GUI の座標]。 */
    public final int lineHeight;

    protected UiFont(int lineHeight) {
        this.lineHeight = lineHeight;
    }

    public abstract int width(String text);

    public abstract int width(RaceText text);
}
