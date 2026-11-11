package com.jdmmc.kurumamod.race;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * コースとレースのコマンド。
 *
 * <p>線は<b>端 2 点</b>で決める。WorldEdit のように、先に 2 点を打ってから
 * 「それがスタートラインだ」「チェックポイントだ」と宣言する形。</p>
 *
 * <p><b>2 点だけでは通過方向が決まらない</b>（線はどちらからでも跨げる）ので、
 * 宣言した瞬間の視線を「走る向き」として取る。あとから
 * {@code /kuruma-admin course direction} で引き直せる。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID)
public final class RaceCommands {

    /** 線の判定に使う高さの幅 [ブロック]。打った点の高さから上へこれだけ。 */
    private static final double HEIGHT = 6.0;

    /**
     * コース名の候補。
     *
     * <p>{@code StringArgumentType.word()} は候補を持たないので、明示的に付けないと
     * タブ補完が効かない。</p>
     */
    private static final SuggestionProvider<CommandSourceStack> COURSES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    RaceData.get(ctx.getSource().getLevel()).courses().stream().map(Course::name),
                    builder);

    /**
     * 「そのコースが無い」と「そもそもコースが 1 つも無い」を区別して返す。
     *
     * <p>名前を打ち間違えたのか、まだ何も作っていないのかで<b>次にやることが違う</b>。
     * 前者は補完を使えばよく、後者はコースの作り方を知る必要がある。</p>
     */
    private static int failNoCourse(CommandContext<CommandSourceStack> ctx, String name) {
        ctx.getSource().sendFailure(RaceData.get(ctx.getSource().getLevel()).courses().isEmpty()
                ? Component.translatable("race.kurumamod.no_courses_hint")
                : Component.translatable("race.kurumamod.no_course", name));
        return 0;
    }

    /** プレイヤーごとに打ちかけの 2 点を覚えておく。サーバーを立て直せば消える。 */
    private static final Map<UUID, double[]> PENDING = new HashMap<>();

    private RaceCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * 打ちかけの 2 点はサーバーと一緒に捨てる。
     *
     * <p>{@code static} なので、シングルプレイでワールドを移っても残ってしまう。
     * 座標は前のワールドのものなので、そのまま線にすると<b>まったく違う場所に引かれる</b>。</p>
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /kuruma-admin はコースを作る側だけ（race stop は 2026-11-10 に消した。主催者がいないレースは、OP と残った参加者がレースの画面の「終える」で畳む）。記録は K の画面で見る。
        // /kuruma（best・timeattack reset）は 2026-11-10 に消した（降りるか K の「計測をやめる」で止まる）
        // レースの参加・主催は K の画面だけ（コマンドは無い）。
        // 根を分けておくと、権限のない人の補完候補にコース作成の枝が出てこない
        dispatcher.register(Commands.literal("kuruma-admin")
                .then(Commands.literal("point")
                        .requires(source -> source.hasPermission(2))
                        .executes(RaceCommands::markPoint))
                .then(Commands.literal("course")
                        .requires(source -> source.hasPermission(2))
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
    // 2 点を打つ
    // ------------------------------------------------------------------

    private static int markPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double[] points = PENDING.get(player.getUUID());
        if (points == null || points.length == 6) {
            // 1 点目。前の組は捨てる
            PENDING.put(player.getUUID(), new double[]{player.getX(), player.getZ(), player.getY()});
            ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.point_a",
                    String.format("%.1f", player.getX()), String.format("%.1f", player.getZ())), false);
            return 1;
        }
        // 2 点目
        double[] pair = {points[0], points[1], points[2], player.getX(), player.getZ(), player.getY()};
        PENDING.put(player.getUUID(), pair);
        double length = Math.hypot(pair[3] - pair[0], pair[4] - pair[1]);
        ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.point_b",
                String.format("%.1f", player.getX()), String.format("%.1f", player.getZ()),
                String.format("%.1f", length)), false);
        return 1;
    }

    /** 打ちかけの 2 点から線を作る。揃っていなければ null。 */
    private static CourseLine takeLine(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        double[] pair = PENDING.get(player.getUUID());
        if (pair == null) {
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.need_points"));
            return null;
        }
        if (pair.length < 6) {
            // 1 点目だけ打ってある。「2 点打て」と言われても、もう 1 点でよいと分からない
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.need_second_point",
                    String.format("%.1f", pair[0]), String.format("%.1f", pair[1])));
            return null;
        }
        // 高さは 2 点の低い方を基準にする
        double y = Math.min(pair[2], pair[5]);
        return CourseLine.between(pair[0], pair[1], pair[3], pair[4], y, HEIGHT, player.getYRot());
    }

    // ------------------------------------------------------------------
    // コース
    // ------------------------------------------------------------------

    private static int createCourse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CourseLine line = takeLine(ctx, player);
        if (line == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        // 名前は画面のあちこちとパケットに載る。長すぎる名前は作らせない
        if (name.length() > Course.MAX_NAME_LENGTH) {
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.course_name_too_long",
                    Course.MAX_NAME_LENGTH));
            return 0;
        }
        RaceData data = RaceData.get((ServerLevel) player.level());
        Course course = data.course(name).orElseGet(() -> data.createCourse(name));
        course.setStart(line);
        course.clearCheckpoints();
        data.setDirty();
        RaceSync.sendToAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.course_created",
                        name, String.format("%.1f", line.length()), facing(player))
                .withStyle(ChatFormatting.GREEN), true);
        // 線が動けば過去のタイムは比較できない。消すかどうかは本人に決めてもらう
        if (data.clearCourseBests(name) > 0) {
            notifyCourseBestsCleared(ctx.getSource().getServer(), name);
            // クラスの記録も消えたので、一覧の記録の件数を配り直す
            RaceManager.classesToAll(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "race.kurumamod.bests_invalidated", name).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int addCheckpoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get((ServerLevel) player.level());
        Course course = data.course(name).orElse(null);
        if (course == null) {
            return failNoCourse(ctx, name);
        }
        CourseLine line = takeLine(ctx, player);
        if (line == null) {
            return 0;
        }
        course.addCheckpoint(line);
        data.setDirty();
        RaceSync.sendToAll(ctx.getSource().getServer());
        int index = course.checkpointCount();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.checkpoint_added", name, index).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** スタートラインの通過方向を、今向いている方向へ引き直す。 */
    private static int setDirection(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get((ServerLevel) player.level());
        Course course = data.course(name).orElse(null);
        if (course == null || course.start() == null) {
            return failNoCourse(ctx, name);
        }
        course.setStart(course.start().withDirection(player.getYRot()));
        data.setDirty();
        RaceSync.sendToAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.direction_set", name, facing(player)), true);
        return 1;
    }

    /** 視線を東西南北で表す。設定した向きが合っているか目で確かめられるように。 */
    private static String facing(ServerPlayer player) {
        return player.getDirection().getName();
    }

    private static int setLaps(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int laps = IntegerArgumentType.getInteger(ctx, "laps");
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        Course course = data.course(name).orElse(null);
        if (course == null) {
            return failNoCourse(ctx, name);
        }
        course.setLaps(laps);
        data.setDirty();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.laps_set", name, laps), true);
        return 1;
    }

    private static int removeCourse(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        if (!data.removeCourse(name)) {
            return failNoCourse(ctx, name);
        }
        RaceSync.sendToAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.course_removed", name), true);
        return 1;
    }

    private static int listCourses(CommandContext<CommandSourceStack> ctx) {
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        if (data.courses().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.no_courses"), false);
            return 0;
        }
        for (Course course : data.courses()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.course_entry",
                    course.name(), course.laps(), course.checkpointCount()), false);
        }
        return data.courses().size();
    }

    /**
     * そのコースの全員のベストを消したことを、いま入っている全員に知らせる。
     * 落ちている人のゴーストは残るが、次にベストを出した周で上書きされる。
     */
    private static void notifyCourseBestsCleared(net.minecraft.server.MinecraftServer server, String name) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            RaceManager.notifyBestCleared(online, name);
        }
    }

    // ------------------------------------------------------------------
    // タイムアタック
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // レース
    // ------------------------------------------------------------------

}
