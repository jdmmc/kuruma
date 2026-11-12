package com.jdmmc.kurumamod.platform;

import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.platform.RaceTexts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

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

    @Override
    public String plainSubstrByWidth(String text, int maxWidth) {
        return font().plainSubstrByWidth(text, maxWidth);
    }

    @Override
    public List<RaceText> split(RaceText text, int maxWidth) {
        List<RaceText> lines = new ArrayList<>();
        for (FormattedText line : font().getSplitter().splitLines(RaceTexts.of(text), maxWidth, Style.EMPTY)) {
            lines.add(RaceText.literal(line.getString()));
        }
        return lines;
    }
}
