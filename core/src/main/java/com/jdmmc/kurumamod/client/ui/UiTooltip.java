package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/** カーソルを乗せたときの説明。バニラの {@code Tooltip} の代わり。 */
public record UiTooltip(RaceText text) {

    public static UiTooltip create(RaceText text) {
        return new UiTooltip(text);
    }
}
