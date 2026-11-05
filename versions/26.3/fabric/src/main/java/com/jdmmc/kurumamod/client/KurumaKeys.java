package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.jdmmc.kurumamod.Kurumamod;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * この MOD のキー割り当て（Fabric 版）。<b>NeoForge 版（versions/26.3/neoforge/・forge/）にも同じ名前・同じ欄のこのクラスがある。</b>
 * 共有コードは各欄の {@code consumeClick()} と {@code getTranslatedKeyMessage()} だけを使う。
 *
 * <p><b>Ctrl の組み合わせ（Ctrl+L・Ctrl+G・Ctrl+H）は {@link Ctrl}。</b>NeoForge は修飾キー付きの割り当て
 * （{@code KeyModifier.CONTROL}）を持っているが、素の Minecraft と Fabric API には無い。素の Minecraft では
 * 1 つのキーに 1 つの割り当てしか届かないので、同じキーの割り当てを Ctrl の有無で振り分けるのは mixin
 * （{@code fabric.mixin.KeyMappingMixin}）がやる。Ctrl を押していれば {@link Ctrl} だけ、押していなければ
 * それ以外だけが反応する（NeoForge と同じ）。</p>
 */
public final class KurumaKeys {

    /**
     * 操作設定の画面での見出し。26.3 では見出しも登録するもの（{@code KeyMapping.Category}）になった。
     * 表示名は lang の {@code key.category.kurumamod.main}。3 つのローダーで同じ ID にしてある。
     */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(Kurumamod.MODID, "main"));

    public static final KeyMapping OPEN_TUNING = plain("key.kurumamod.tuning", InputConstants.KEY_G);
    public static final KeyMapping OPEN_MENU = plain("key.kurumamod.menu", InputConstants.KEY_H);
    public static final KeyMapping TOGGLE_CAMERA = plain("key.kurumamod.camera", InputConstants.KEY_V);
    public static final KeyMapping SHIFT_UP = plain("key.kurumamod.shift_up", InputConstants.KEY_R);
    public static final KeyMapping SHIFT_DOWN = plain("key.kurumamod.shift_down", InputConstants.KEY_F);
    public static final KeyMapping CAMERA_CLOSER = plain("key.kurumamod.camera_closer", InputConstants.KEY_PAGEUP);
    public static final KeyMapping CAMERA_FARTHER = plain("key.kurumamod.camera_farther", InputConstants.KEY_PAGEDOWN);
    public static final KeyMapping TOGGLE_HEADLIGHTS = plain("key.kurumamod.headlights", InputConstants.KEY_L);
    public static final KeyMapping OPEN_HEADLIGHT_SETTINGS = new Ctrl("key.kurumamod.headlight_settings", InputConstants.KEY_L);
    public static final KeyMapping OPEN_SEAT_DEBUG = new Ctrl("key.kurumamod.seat_debug", InputConstants.KEY_G);
    public static final KeyMapping OPEN_SOUND_DEBUG = new Ctrl("key.kurumamod.sound_debug", InputConstants.KEY_H);
    public static final KeyMapping OPEN_CONTROLLER = plain("key.kurumamod.controller", InputConstants.KEY_J);

    private KurumaKeys() {
    }

    /** クライアントで 1 回だけ呼ぶ（{@code onInitializeClient}）。並びは NeoForge 版と同じ。 */
    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN_TUNING);
        KeyMappingHelper.registerKeyMapping(OPEN_MENU);
        KeyMappingHelper.registerKeyMapping(TOGGLE_CAMERA);
        KeyMappingHelper.registerKeyMapping(SHIFT_UP);
        KeyMappingHelper.registerKeyMapping(SHIFT_DOWN);
        KeyMappingHelper.registerKeyMapping(TOGGLE_HEADLIGHTS);
        KeyMappingHelper.registerKeyMapping(OPEN_HEADLIGHT_SETTINGS);
        KeyMappingHelper.registerKeyMapping(OPEN_SEAT_DEBUG);
        KeyMappingHelper.registerKeyMapping(CAMERA_CLOSER);
        KeyMappingHelper.registerKeyMapping(CAMERA_FARTHER);
        KeyMappingHelper.registerKeyMapping(OPEN_CONTROLLER);
        KeyMappingHelper.registerKeyMapping(OPEN_SOUND_DEBUG);
    }

    private static KeyMapping plain(String name, int key) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, key, CATEGORY);
    }

    /** Ctrl を押しているときだけ反応する割り当て。表示は「Ctrl + G」。 */
    public static final class Ctrl extends KeyMapping {

        Ctrl(String name, int key) {
            super(name, InputConstants.Type.KEYBOARD, key, CATEGORY);
        }

        /** いま Ctrl が押されているか（左右どちらでも。macOS の Command も含む。バニラの判定と同じ）。 */
        public static boolean held() {
            return Minecraft.getInstance().hasControlDown();
        }

        /** NeoForge と同じ「CTRL + G」（macOS では「CMD + G」）。 */
        @Override
        public Component getTranslatedKeyMessage() {
            return Component.translatable(Util.getPlatform() == Util.OS.OSX ? "key.kurumamod.modifier.control.mac" : "key.kurumamod.modifier.control",
                    super.getTranslatedKeyMessage());
        }

        /** 同じキーでも、Ctrl の有無が違う割り当てとは重なっていない（操作設定の画面で赤くしない）。 */
        @Override
        public boolean same(KeyMapping other) {
            return other instanceof Ctrl && super.same(other);
        }
    }
}
