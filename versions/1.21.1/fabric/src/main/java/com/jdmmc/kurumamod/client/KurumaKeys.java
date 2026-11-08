package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * この MOD のキー割り当て（Fabric 版）。<b>NeoForge 版（versions/1.21.1/neoforge/）にも同じ名前・同じ欄のこのクラスがある。</b>
 * 共有コードは各欄の {@code consumeClick()} と {@code getTranslatedKeyMessage()} だけを使う。
 *
 * <p><b>Ctrl の組み合わせ（Ctrl+L・Ctrl+G・Ctrl+H）は {@link Ctrl}。</b>NeoForge は修飾キー付きの割り当て
 * （{@code KeyModifier.CONTROL}）を持っているが、素の Minecraft と Fabric API には無い。素の Minecraft では
 * 1 つのキーに 1 つの割り当てしか届かないので、同じキーの割り当てを Ctrl の有無で振り分けるのは mixin
 * （{@code fabric.mixin.KeyMappingMixin}）がやる。Ctrl を押していれば {@link Ctrl} だけ、押していなければ
 * それ以外だけが反応する（NeoForge と同じ）。</p>
 */
public final class KurumaKeys {

    private static final String CATEGORY = "key.categories.kurumamod";

    public static final KeyMapping OPEN_TUNING = plain("key.kurumamod.tuning", GLFW.GLFW_KEY_G);
    public static final KeyMapping OPEN_MENU = plain("key.kurumamod.menu", GLFW.GLFW_KEY_H);
    public static final KeyMapping TOGGLE_CAMERA = plain("key.kurumamod.camera", GLFW.GLFW_KEY_V);
    public static final KeyMapping SHIFT_UP = plain("key.kurumamod.shift_up", GLFW.GLFW_KEY_R);
    public static final KeyMapping SHIFT_DOWN = plain("key.kurumamod.shift_down", GLFW.GLFW_KEY_F);
    public static final KeyMapping CAMERA_CLOSER = plain("key.kurumamod.camera_closer", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping CAMERA_FARTHER = plain("key.kurumamod.camera_farther", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping TOGGLE_HEADLIGHTS = plain("key.kurumamod.headlights", GLFW.GLFW_KEY_L);
    public static final KeyMapping OPEN_HEADLIGHT_SETTINGS = new Ctrl("key.kurumamod.headlight_settings", GLFW.GLFW_KEY_L);
    public static final KeyMapping OPEN_SEAT_DEBUG = new Ctrl("key.kurumamod.seat_debug", GLFW.GLFW_KEY_G);
    public static final KeyMapping OPEN_SOUND_DEBUG = new Ctrl("key.kurumamod.sound_debug", GLFW.GLFW_KEY_H);
    public static final KeyMapping OPEN_CONTROLLER = plain("key.kurumamod.controller", GLFW.GLFW_KEY_J);
    /** レース（参加・主催の入口）。NeoForge 版と同じ。 */
    public static final KeyMapping OPEN_RACE = plain("key.kurumamod.race", GLFW.GLFW_KEY_K);

    private KurumaKeys() {
    }

    /** クライアントで 1 回だけ呼ぶ（{@code onInitializeClient}）。並びは NeoForge 版と同じ。 */
    public static void register() {
        KeyBindingHelper.registerKeyBinding(OPEN_TUNING);
        KeyBindingHelper.registerKeyBinding(OPEN_MENU);
        KeyBindingHelper.registerKeyBinding(TOGGLE_CAMERA);
        KeyBindingHelper.registerKeyBinding(SHIFT_UP);
        KeyBindingHelper.registerKeyBinding(SHIFT_DOWN);
        KeyBindingHelper.registerKeyBinding(TOGGLE_HEADLIGHTS);
        KeyBindingHelper.registerKeyBinding(OPEN_HEADLIGHT_SETTINGS);
        KeyBindingHelper.registerKeyBinding(OPEN_SEAT_DEBUG);
        KeyBindingHelper.registerKeyBinding(CAMERA_CLOSER);
        KeyBindingHelper.registerKeyBinding(CAMERA_FARTHER);
        KeyBindingHelper.registerKeyBinding(OPEN_CONTROLLER);
        KeyBindingHelper.registerKeyBinding(OPEN_SOUND_DEBUG);
        KeyBindingHelper.registerKeyBinding(OPEN_RACE);
    }

    private static KeyMapping plain(String name, int key) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    /** Ctrl を押しているときだけ反応する割り当て。表示は「Ctrl + G」。 */
    public static final class Ctrl extends KeyMapping {

        Ctrl(String name, int key) {
            super(name, InputConstants.Type.KEYSYM, key, CATEGORY);
        }

        /** いま Ctrl が押されているか（左右どちらでも。macOS の Command も含む。バニラの判定と同じ）。 */
        public static boolean held() {
            return Screen.hasControlDown();
        }

        /** NeoForge と同じ「CTRL + G」（macOS では「CMD + G」）。 */
        @Override
        public Component getTranslatedKeyMessage() {
            return Component.translatable(Minecraft.ON_OSX ? "key.kurumamod.modifier.control.mac" : "key.kurumamod.modifier.control",
                    super.getTranslatedKeyMessage());
        }

        /** 同じキーでも、Ctrl の有無が違う割り当てとは重なっていない（操作設定の画面で赤くしない）。 */
        @Override
        public boolean same(KeyMapping other) {
            return other instanceof Ctrl && super.same(other);
        }
    }
}
