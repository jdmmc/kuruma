package com.jdmmc.kurumamod.platform;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.jdmmc.kurumamod.race.RaceColor;
import com.jdmmc.kurumamod.race.RaceText;

/** core の {@link RaceText} を {@link Component} に直す。 */
public final class RaceTexts {

    private RaceTexts() {
    }

    public static Component of(RaceText text) {
        if (text.kind() == RaceText.Kind.NATIVE) {
            Component component = (Component) text.nativeText();
            if (text.color() == null && text.siblings().isEmpty()) {
                return component;
            }
            MutableComponent copy = component.copy();
            for (RaceText sibling : text.siblings()) {
                copy.append(of(sibling));
            }
            return text.color() == null ? copy : copy.withStyle(color(text.color()));
        }
        MutableComponent component = text.kind() == RaceText.Kind.LITERAL
                ? Component.literal(text.text())
                : Component.translatable(text.text(), args(text.args()));
        for (RaceText sibling : text.siblings()) {
            component.append(of(sibling));
        }
        return text.color() == null ? component : component.withStyle(color(text.color()));
    }

    /** 引数の中の {@link RaceText} も直す（{@link RaceText#args()} は写しなので、書き換えてよい）。 */
    private static Object[] args(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof RaceText inner) {
                args[i] = of(inner);
            }
        }
        return args;
    }

    private static ChatFormatting color(RaceColor color) {
        return ChatFormatting.valueOf(color.name());
    }
}
