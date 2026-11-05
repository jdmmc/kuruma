package com.jdmmc.kurumamod.hook;

import com.jdmmc.kurumamod.CarRuleCommands;
import com.jdmmc.kurumamod.CarRules;
import com.jdmmc.kurumamod.Config;
import com.jdmmc.kurumamod.network.CarPartTypesPacket;
import com.jdmmc.kurumamod.network.CarRulesPacket;
import com.jdmmc.kurumamod.network.CarTypesPacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.race.RaceCommands;
import com.jdmmc.kurumamod.race.RaceManager;
import com.jdmmc.kurumamod.race.RaceSync;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * サーバー側（統合サーバーを含む）の割り込み先の一覧。<b>どの場面で、どのクラスを、どの順に呼ぶかはここにだけ書く。</b>
 *
 * <p>各ローダーのつなぎ（NeoForge 版は {@code NeoForgeCommonEvents}、Fabric 版は {@code KurumamodFabric}）は、
 * 場面が来たらここを呼ぶだけ。クライアント側は {@code client.hook.ClientHooks}。</p>
 */
public final class CommonHooks {

    private CommonHooks() {
    }

    /** サーバーが起動する。 */
    public static void serverStarting() {
        // 走行のルールはサーバーの設定が正。ここで一度移しておき、以降は
        // ログイン時とコマンドで配る（クライアントは自分の toml を見ない）
        CarRules.setCarCollision(Config.carCollision);
    }

    /**
     * 読み込んだ車種・部品・走行のルールをその人へ配る。ログイン時は本人、{@code /reload} 時は全員に対して呼ぶ。
     *
     * <p><b>クライアントに自分でデータパックを読ませない。</b>シングルプレイでは読めて
     * しまうが、マルチプレイで車種を決めるのはサーバーであって手元のカーパックではない。
     * 配られたものだけを見る、で揃えておけば、サーバーに無い車がクリエイティブタブに
     * 並ぶということが起きない。</p>
     */
    public static void syncDataPack(ServerPlayer player) {
        KurumaNetwork.sendToPlayer(player, CarTypesPacket.current());
        KurumaNetwork.sendToPlayer(player, CarPartTypesPacket.current());
        KurumaNetwork.sendToPlayer(player, CarRulesPacket.current());
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        CarRuleCommands.register(dispatcher);
        RaceCommands.register(dispatcher);
    }

    /** サーバーのティックの終わり。 */
    public static void serverTickPost(MinecraftServer server) {
        RaceManager.onServerTick(server);
    }

    public static void serverStopped() {
        RaceCommands.onServerStopped();
        RaceManager.onServerStopped();
    }

    public static void playerLoggedIn(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            RaceSync.sendTo(serverPlayer);
        }
    }

    public static void playerLoggedOut(Player player) {
        RaceManager.onLogout(player);
    }

    /** 次元を移った。{@code from} は移る前の次元。 */
    public static void playerChangedDimension(Player player, ResourceKey<Level> from) {
        RaceManager.onChangedDimension(player, from);
        if (player instanceof ServerPlayer serverPlayer) {
            RaceSync.sendTo(serverPlayer);
        }
    }
}
