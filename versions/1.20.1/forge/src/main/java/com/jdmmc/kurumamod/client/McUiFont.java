package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** core の {@link UiFont} を Minecraft の {@link Font} で測る。 */
final class McUiFont extends UiFont {

    static final McUiFont INSTANCE = new McUiFont();

    private McUiFont() {
        super(9);
    }

    static Font font() {
        return Minecraft.getInstance().font;
    }

    @Override
    public int width(String text) {
        return font().width(text);
    }

    @Override
    public int width(RaceText text) {
        return font().width(RaceTexts.of(text));
    }
}
