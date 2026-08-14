package com.jdmmc.kurumamod;

import com.jdmmc.kurumamod.network.CarRulesPacket;
import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * 走行のルールを切り替えるコマンド。
 *
 * <p>{@code /kuruma-admin collide} で今の状態、{@code /kuruma-admin collide true|false} で
 * 切り替え。<b>コースを作る側と同じく OP 専用</b>なので、権限のない人の補完候補には出ない。</p>
 *
 * <p><b>切り替えたら設定ファイルへ書き戻し、全員へ配り直す。</b>コマンドだけで変えて
 * 配らないと、クライアントは「当たらない」と思ったまま走ってサーバーに引き戻される。
 * 書き戻さないと再起動で元へ戻る。</p>
 *
 * <p>{@code /kuruma-admin} の枝は {@link com.jdmmc.kurumamod.race.RaceCommands} でも
 * 生やしているが、<b>Brigadier は同じ名前の literal を登録すると子をまとめる</b>ので
 * 衝突しない。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID)
public final class CarRuleCommands {

    private CarRuleCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kuruma-admin")
                .then(Commands.literal("collide")
                        .requires(source -> source.hasPermission(2))
                        .executes(CarRuleCommands::show)
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> set(ctx, BoolArgumentType.getBool(ctx, "enabled"))))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        boolean on = CarRules.carCollision();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                on ? "rule.kurumamod.collide.on" : "rule.kurumamod.collide.off"), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean value) {
        if (CarRules.carCollision() == value) {
            return show(ctx);
        }
        Config.setCarCollision(value);
        // 全員へ配り直す。片側だけ変わると、当たらないと思って走った車が引き戻される
        KurumaNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), CarRulesPacket.current());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                value ? "rule.kurumamod.collide.set_on" : "rule.kurumamod.collide.set_off"), true);
        return 1;
    }
}
