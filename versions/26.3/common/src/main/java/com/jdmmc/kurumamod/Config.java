package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.config.ConfigSpec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 設定クラスのサンプル。必須ではないが、設定を整理しておくために用意しておくとよい。
// Forge の設定 API の使い方を示すためのもの。
public class Config {
    private static final ConfigSpec.Builder BUILDER = new ConfigSpec.Builder();

    private static final ConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.comment("共通セットアップ時に土ブロックをログ出力するかどうか").define("logDirtBlock", true);

    private static final ConfigSpec.IntValue MAGIC_NUMBER = BUILDER.comment("マジックナンバー").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER.comment("マジックナンバーを表示する際の前置きメッセージ").define("magicNumberIntroduction", "マジックナンバーは... ");

    // アイテムの Identifier として扱う文字列のリスト
    private static final ConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER.comment("共通セットアップ時にログ出力するアイテムのリスト").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // 放置された車を消すまでの時間 [秒]。0 で無効
    private static final ConfigSpec.IntValue ABANDONED_CAR_LIFETIME = BUILDER
            .comment("誰も乗っていない車を消すまでの時間 [秒]。0 で消さない")
            .defineInRange("abandonedCarLifetimeSeconds", 600, 0, 86400);

    // 車同士が当たるかどうか
    private static final ConfigSpec.BooleanValue CAR_COLLISION = BUILDER
            .comment("車同士が当たるかどうか。false ですり抜ける",
                    "ゲーム中に /kuruma-admin collide でも切り替えられる（こちらへ書き戻される）")
            .define("carCollision", true);

    static final ConfigSpec SPEC = BUILDER.build();

    /** {@code config/} の中のファイル名。NeoForge の設定の仕組みを使っていた頃と同じ名前（それまでの値がそのまま読める）。 */
    private static final String FILE = "kurumamod-common.toml";

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
     * ファイルを書いても、それを拾って {@code ModConfigEvent} を起こすのは <b>Forge の
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
        return obj instanceof final String itemName && BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemName));
    }

    /**
     * 値を読み直す。
     *
     * <p>起動時に 1 回、両側で呼ぶ（NeoForge 版は {@code Kurumamod} のコンストラクタ、Fabric 版は
     * {@code onInitialize}）。ゲーム中にファイルを書き換えても読み直さない。</p>
     */
    public static void load() {
        SPEC.load(Platform.configDir().resolve(FILE));
        abandonedCarLifetimeSeconds = ABANDONED_CAR_LIFETIME.get();
        carCollision = CAR_COLLISION.get();

        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // 文字列のリストをアイテムの Set へ変換する
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.getValue(Identifier.parse(itemName))).collect(Collectors.toSet());
    }
}
