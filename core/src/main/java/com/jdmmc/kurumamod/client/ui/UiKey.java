package com.jdmmc.kurumamod.client.ui;

/**
 * 押されたキー。版の画面が、版ごとに違うキーの番号（1.21.1 までは GLFW、26.3 は SDL）を見分けてから渡す。
 *
 * @param escape   Esc
 * @param enter    Enter（テンキーの Enter も）
 * @param openRace レースの画面を開くキー（既定は K。割り当てを変えていればそのキー）
 * @param driving  運転のキー（画面を開いたまま走れるよう、画面では使わない）
 */
public record UiKey(int keyCode, int scanCode, int modifiers,
                    boolean escape, boolean enter, boolean openRace, boolean driving) {
}
