package com.jdmmc.kurumamod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 設定クラスのサンプル。必須ではないが、設定を整理しておくために用意しておくとよい。
// Forge の設定 API の使い方を示すためのもの。
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.comment("共通セットアップ時に土ブロックをログ出力するかどうか").define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER.comment("マジックナンバー").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER.comment("マジックナンバーを表示する際の前置きメッセージ").define("magicNumberIntroduction", "マジックナンバーは... ");

    // アイテムの ResourceLocation として扱う文字列のリスト
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER.comment("共通セットアップ時にログ出力するアイテムのリスト").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // 放置された車を消すまでの時間 [秒]。0 で無効
    private static final ForgeConfigSpec.IntValue ABANDONED_CAR_LIFETIME = BUILDER
            .comment("誰も乗っていない車を消すまでの時間 [秒]。0 で消さない")
            .defineInRange("abandonedCarLifetimeSeconds", 600, 0, 86400);

    // 車同士が当たるかどうか
    private static final ForgeConfigSpec.BooleanValue CAR_COLLISION = BUILDER
            .comment("車同士が当たるかどうか。false ですり抜ける",
                    "ゲーム中に /kuruma-admin collide でも切り替えられる（こちらへ書き戻される）")
            .define("carCollision", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    /** 放置された車を消すまでの時間 [秒]。0 で消さない。 */
    public static int abandonedCarLifetimeSeconds;

    /**
     * 車同士が当たるかどうか（サーバーの設定）。
     *
     * <p><b>挙動を決めるときはこれではなく {@link CarRules#carCollision()} を読むこと。</b>
     * この COMMON 設定はクライアントにも同名のファイルがあり、マルチプレイでは
     * 手元の値が接続先と食い違う。効いている値はサーバーが配る。</p>
     */
    public static boolean carCollision;

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    /**
     * 車同士の当たりを切り替えてファイルへ書き戻す。
     *
     * <p><b>書き換えた側が static フィールドも一緒に書くこと。</b>{@code SPEC.save()} で
     * ファイルを書いても、それを拾って {@link ModConfigEvent} を起こすのは <b>Forge の
     * ファイル監視スレッド</b>なので、OS の通知が届くまで遅れるうえ届かないこともある。
     * 当てにすると、切り替えたのに再起動まで反映されない（以前の設定画面で実際に起きた）。</p>
     */
    public static void setCarCollision(boolean value) {
        CAR_COLLISION.set(value);
        SPEC.save();
        carCollision = value;
        CarRules.setCarCollision(value);
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof final String itemName && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(itemName));
    }

    /**
     * 値を読み直す。
     *
     * <p><b>自分の spec のイベントだけを拾うこと。</b>{@link ModConfigEvent} は CLIENT の
     * 読み込みでも飛んでくるので、素通しにすると<b>まだ読まれていない COMMON の値を
     * 取りにいって「Cannot get config value before config is loaded」で落ちる</b>
     * （{@link ClientConfig} を足したときに実際に出た）。</p>
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.COMMON) {
            return;
        }
        abandonedCarLifetimeSeconds = ABANDONED_CAR_LIFETIME.get();
        carCollision = CAR_COLLISION.get();

        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // 文字列のリストをアイテムの Set へ変換する
        items = ITEM_STRINGS.get().stream().map(itemName -> ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemName))).collect(Collectors.toSet());
    }
}
