package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * セッティングのプリセットを扱うコマンド。
 *
 * <p><b>クライアント側のコマンド</b>（{@link RegisterClientCommandsEvent}）なので、
 * サーバーへは一切飛ばない。設定はクライアントに保存するものなので、
 * サーバーの権限も要らないし、サーバーを移っても同じものが使える。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarSetupCommands {

    private static final SuggestionProvider<CommandSourceStack> SETUPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(CarSetups.names(), builder);

    private CarSetupCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 根は /kuruma。race/best はサーバー側の別ディスパッチャにある同名の根だが、
        // 一致しない枝は Forge が「Incorrect argument」としてサーバーへ回すので衝突しない
        // （ClientCommandHandler#runCommand が dispatcherUnknownArgument を素通しする）
        event.getDispatcher().register(Commands.literal("kuruma")
                .then(Commands.literal("preset")
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SETUPS)
                                .executes(CarSetupCommands::save)))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SETUPS)
                                .executes(CarSetupCommands::load)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SETUPS)
                                .executes(CarSetupCommands::delete)))
                .then(Commands.literal("list").executes(CarSetupCommands::list))));
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean overwrite = CarSetups.exists(name);
        CarSetups.save(name, CarTuning.spec());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                        overwrite ? "setup.kurumamod.overwritten" : "setup.kurumamod.saved", name)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int load(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        CarSpec spec = CarSetups.load(name);
        if (spec == null) {
            ctx.getSource().sendFailure(Component.translatable("setup.kurumamod.not_found", name));
            return 0;
        }
        // 運転中なら次のティックで CarClientDriver が車へ流し込み、サーバーへも送る
        CarTuning.apply(spec);
        ctx.getSource().sendSuccess(() -> Component.translatable("setup.kurumamod.loaded", name)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!CarSetups.delete(name)) {
            ctx.getSource().sendFailure(Component.translatable("setup.kurumamod.not_found", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("setup.kurumamod.deleted", name), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var names = CarSetups.names();
        if (names.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("setup.kurumamod.none"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("setup.kurumamod.list",
                names.size(), String.join(", ", names)), false);
        return names.size();
    }
}
