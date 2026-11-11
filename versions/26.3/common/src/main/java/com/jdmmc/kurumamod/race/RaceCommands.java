package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;


/**
 * コースを作るコマンドの木（Brigadier）。<b>中身は core の {@link CourseAdmin}</b>（線の引き方・断り方・記録の片付け）。
 *
 * <p>ここは引数を取り出し、打った人と場所を {@link CourseAdmin.Source}・{@link CourseAdmin.Stand} に包んで渡すだけ。</p>
 */
public final class RaceCommands {

    /**
     * コース名の候補。
     *
     * <p>{@code StringArgumentType.word()} は候補を持たないので、明示的に付けないと
     * タブ補完が効かない。</p>
     */
    private static final SuggestionProvider<CommandSourceStack> COURSES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    RaceManager.admin().courseNames(McRaceServer.level(ctx.getSource().getLevel())), builder);

    private RaceCommands() {
    }

    /** 打ちかけの 2 点はサーバーと一緒に捨てる（{@link CourseAdmin#onServerStopped}）。 */
    public static void onServerStopped() {
        RaceManager.admin().onServerStopped();
    }

    /** コマンドの登録。NeoForge は {@code RegisterCommandsEvent}、Fabric は {@code CommandRegistrationCallback} から。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /kuruma-admin はコースを作る側だけ（race stop は 2026-11-10 に消した。主催者がいないレースは、OP と残った参加者がレースの画面の「終える」で畳む）。記録は K の画面で見る。
        // /kuruma（best・timeattack reset）は 2026-11-10 に消した（降りるか K の「計測をやめる」で止まる）
        // レースの参加・主催は K の画面だけ（コマンドは無い）。
        // 根を分けておくと、権限のない人の補完候補にコース作成の枝が出てこない
        dispatcher.register(Commands.literal("kuruma-admin")
                .then(Commands.literal("point")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(RaceCommands::markPoint))
                .then(Commands.literal("course")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        // 引き直しにも使うので、既存の名前も候補に出す
                                        .suggests(COURSES)
                                        .executes(RaceCommands::createCourse)))
                        .then(Commands.literal("checkpoint")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(RaceCommands::addCheckpoint)))
                        .then(Commands.literal("direction")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(RaceCommands::setDirection)))
                        .then(Commands.literal("laps")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .then(Commands.argument("laps", IntegerArgumentType.integer(1, 99))
                                                .executes(RaceCommands::setLaps))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(RaceCommands::removeCourse)))
                        .then(Commands.literal("list").executes(RaceCommands::listCourses))));
    }

    // ------------------------------------------------------------------
    // core へ渡す
    // ------------------------------------------------------------------

    private static CourseAdmin.Source source(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        return new CourseAdmin.Source() {
            @Override
            public RaceLevel level() {
                return McRaceServer.level(source.getLevel());
            }

            @Override
            public RaceServer server() {
                return McRaceServer.of(source.getServer());
            }

            @Override
            public void success(RaceText message, boolean broadcast) {
                source.sendSuccess(() -> RaceTexts.of(message), broadcast);
            }

            @Override
            public void failure(RaceText message) {
                source.sendFailure(RaceTexts.of(message));
            }
        };
    }

    /** 打った人の立ち位置。プレイヤーでなければ（コンソール）コマンドの失敗にする。 */
    private static CourseAdmin.Stand stand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return new CourseAdmin.Stand(player.getUUID(), player.getX(), player.getY(), player.getZ(), player.getYRot(),
                player.getDirection().getName());
    }

    /** 線を引くコマンドは、打った人のいる次元のコースを触る（コマンドの場所ではなく）。 */
    private static CourseAdmin.Source playerSource(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CourseAdmin.Source base = source(ctx);
        ServerLevel level = (ServerLevel) player.level();
        return new CourseAdmin.Source() {
            @Override
            public RaceLevel level() {
                return McRaceServer.level(level);
            }

            @Override
            public RaceServer server() {
                return base.server();
            }

            @Override
            public void success(RaceText message, boolean broadcast) {
                base.success(message, broadcast);
            }

            @Override
            public void failure(RaceText message) {
                base.failure(message);
            }
        };
    }

    private static int markPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return RaceManager.admin().markPoint(source(ctx), stand(ctx));
    }

    private static int createCourse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return RaceManager.admin().createCourse(playerSource(ctx), stand(ctx), StringArgumentType.getString(ctx, "name"));
    }

    private static int addCheckpoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return RaceManager.admin().addCheckpoint(playerSource(ctx), stand(ctx), StringArgumentType.getString(ctx, "name"));
    }

    private static int setDirection(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return RaceManager.admin().setDirection(playerSource(ctx), stand(ctx), StringArgumentType.getString(ctx, "name"));
    }

    private static int setLaps(CommandContext<CommandSourceStack> ctx) {
        return RaceManager.admin().setLaps(source(ctx), StringArgumentType.getString(ctx, "name"),
                IntegerArgumentType.getInteger(ctx, "laps"));
    }

    private static int removeCourse(CommandContext<CommandSourceStack> ctx) {
        return RaceManager.admin().removeCourse(source(ctx), StringArgumentType.getString(ctx, "name"));
    }

    private static int listCourses(CommandContext<CommandSourceStack> ctx) {
        return RaceManager.admin().listCourses(source(ctx));
    }
}
