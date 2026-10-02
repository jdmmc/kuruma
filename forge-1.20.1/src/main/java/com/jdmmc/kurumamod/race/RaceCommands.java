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

    /** 受付中のレースのコース名だけを候補にする。 */
    private static final SuggestionProvider<CommandSourceStack> OPEN_RACE = (ctx, builder) -> {
        String open = RaceManager.openCourse(ctx.getSource().getLevel());
        return open == null
                ? builder.buildFuture()
                : SharedSuggestionProvider.suggest(java.util.List.of(open), builder);
    };

    /** 自分が記録を持っているコースだけを候補にする。 */
    private static final SuggestionProvider<CommandSourceStack> MY_RECORDS = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(
                RaceData.get((ServerLevel) player.level()).recordedCourses(player.getUUID()), builder);
    };

    /** ランキングに出す人数。 */
    private static final int LEADERBOARD_SIZE = 10;

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
        // コースを作る側は /kuruma-admin。走る側（race / best）は誰でも撃つので /kuruma に残す。
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

        dispatcher.register(Commands.literal("kuruma")
                .then(Commands.literal("best")
                        .executes(RaceCommands::showBests)
                        // 誰でも見られる。速い人のタイムが見えないと目標が立たない
                        .then(Commands.literal("top")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(RaceCommands::showLeaderboard)))
                        .then(Commands.literal("clear")
                                .then(Commands.literal("all").executes(RaceCommands::clearAllBests))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(MY_RECORDS)
                                        .executes(RaceCommands::clearBest)))
                        .then(Commands.literal("clearall")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(RaceCommands::clearCourseBests))))
                .then(Commands.literal("timeattack")
                        .then(Commands.literal("reset").executes(RaceCommands::resetTimeAttack)))
                .then(Commands.literal("race")
                        // 参加まわりは誰でも撃てる。開催の操作だけ OP
                        .then(Commands.literal("join")
                                .executes(ctx -> joinRace(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(OPEN_RACE)
                                        .executes(ctx -> joinRace(ctx,
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("leave").executes(RaceCommands::leaveRace))
                        .then(Commands.literal("entrants").executes(RaceCommands::listEntrants))
                        .then(Commands.literal("open")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(COURSES)
                                        .executes(ctx -> openRace(ctx, -1))
                                        .then(Commands.argument("laps", IntegerArgumentType.integer(1, 99))
                                                .executes(ctx -> openRace(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "laps"))))))
                        .then(Commands.literal("start").executes(RaceCommands::startRace))
                        .then(Commands.literal("stop").executes(RaceCommands::stopRace))));
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

    // ------------------------------------------------------------------
    // ベストタイム
    // ------------------------------------------------------------------

    private static int showBests(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RaceData data = RaceData.get((ServerLevel) player.level());
        var names = data.recordedCourses(player.getUUID());
        if (names.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.no_bests"), false);
            return 0;
        }
        for (String name : names) {
            long millis = data.bestLap(player.getUUID(), name);
            // 理論ベスト（区間ベストの合計）は、まだ縮められる余地がどれだけあるかを示す。
            // ベストラップと同じ値なら、全区間を同じ周に出しきったということ
            long ideal = data.theoreticalBest(player.getUUID(), name);
            ctx.getSource().sendSuccess(() -> ideal > 0L && ideal < millis
                    ? Component.translatable("race.kurumamod.best_entry_ideal",
                            name, RaceManager.format(millis), RaceManager.format(ideal))
                    : Component.translatable("race.kurumamod.best_entry",
                            name, RaceManager.format(millis)), false);
        }
        return names.size();
    }

    /**
     * そのコースの速い順。
     *
     * <p>オフラインの人も出す。名前は記録した時点のもので、記録が古くて名前を持っていない
     * ぶんだけプロフィールキャッシュへ聞きにいく。</p>
     */
    private static int showLeaderboard(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        java.util.List<RaceData.Entry> top = data.leaderboard(name, LEADERBOARD_SIZE);
        if (top.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_best", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.leaderboard_header", name).withStyle(ChatFormatting.GOLD), false);
        long fastest = top.get(0).millis();
        for (int i = 0; i < top.size(); i++) {
            RaceData.Entry entry = top.get(i);
            int place = i + 1;
            String shown = displayName(ctx, entry);
            String time = RaceManager.format(entry.millis());
            // 先頭にはタイムだけ、以降は先頭との差も添える。何秒届いていないかが一目で分かる
            ctx.getSource().sendSuccess(() -> place == 1
                    ? Component.translatable("race.kurumamod.leaderboard_entry", place, shown, time)
                    : Component.translatable("race.kurumamod.leaderboard_entry_gap", place, shown, time,
                            RaceManager.format(entry.millis() - fastest)), false);
        }
        return top.size();
    }

    /** 記録に名前が入っていなければ（内訳を持つ前の古い記録）プロフィールキャッシュで引く。 */
    private static String displayName(CommandContext<CommandSourceStack> ctx, RaceData.Entry entry) {
        if (!entry.name().isEmpty()) {
            return entry.name();
        }
        return ctx.getSource().getServer().getProfileCache() == null ? entry.player().toString()
                : ctx.getSource().getServer().getProfileCache().get(entry.player())
                        .map(com.mojang.authlib.GameProfile::getName)
                        .orElse(entry.player().toString());
    }

    private static int clearBest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get((ServerLevel) player.level());
        if (!data.clearBest(player.getUUID(), name)) {
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_best", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.best_cleared", name), false);
        return 1;
    }

    private static int clearAllBests(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RaceData data = RaceData.get((ServerLevel) player.level());
        int removed = data.clearBests(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.bests_cleared", removed), false);
        return removed;
    }

    /** そのコースの全員のベストを消す。コースを引き直したときに使う。 */
    private static int clearCourseBests(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        int removed = data.clearCourseBests(name);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.course_bests_cleared", name, removed), true);
        return removed;
    }

    // ------------------------------------------------------------------
    // タイムアタック
    // ------------------------------------------------------------------

    /**
     * 計測中のタイムアタックを取り消す。
     *
     * <p>ミスした周を捨てて入り直すための口。今までは<b>車から降りるしか手が無かった</b>。</p>
     */
    private static int resetTimeAttack(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        switch (RaceManager.resetTimeAttack(player)) {
            case NOT_TIMING -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.not_timing"));
                return 0;
            }
            case RACING -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.reset_while_racing"));
                return 0;
            }
            case RESET -> {
                // 本人だけに出す。他人のタイムアタックのやり直しは誰も見たくない
                ctx.getSource().sendSuccess(() -> Component.translatable(
                        "race.kurumamod.timeattack_reset").withStyle(ChatFormatting.GRAY), false);
                return 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // レース
    // ------------------------------------------------------------------

    /** エントリーの受付を始める。まだ走らない。 */
    private static int openRace(CommandContext<CommandSourceStack> ctx, int laps)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        RaceData data = RaceData.get(ctx.getSource().getLevel());
        Course course = data.course(name).orElse(null);
        if (course == null || !course.isReady()) {
            return failNoCourse(ctx, name);
        }
        if (!RaceManager.openRace(ctx.getSource().getLevel(), course,
                laps > 0 ? laps : course.laps(), player)) {
            ServerLevel level = ctx.getSource().getLevel();
            String open = RaceManager.openCourse(level);
            // 「開催中です」だけだと、待てば始まるのか終わるのを待つのかが分からない
            ctx.getSource().sendFailure(open != null
                    ? Component.translatable("race.kurumamod.already_open_entry", open)
                    : Component.translatable("race.kurumamod.already_open_running",
                            RaceManager.racingCourse(level)));
            return 0;
        }
        return 1;
    }

    private static int joinRace(CommandContext<CommandSourceStack> ctx, String course)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        switch (RaceManager.join(player, course)) {
            case NO_RACE -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_entry"));
                return 0;
            }
            case ALREADY_STARTED -> {
                // 「受付中のレースがありません」で片付けると、レース自体が無いと読める
                ctx.getSource().sendFailure(Component.translatable(
                        "race.kurumamod.already_started", RaceManager.racingCourse(level)));
                return 0;
            }
            case WRONG_COURSE -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.wrong_course",
                        RaceManager.openCourse(level)));
                return 0;
            }
            case ALREADY_JOINED -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.already_joined"));
                return 0;
            }
            case JOINED -> {
                ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.joined")
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
        }
        return 0;
    }

    private static int leaveRace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        switch (RaceManager.leave(player)) {
            case NOT_ENTERED -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.not_entered"));
                return 0;
            }
            case RETIRED -> {
                // 走行中に抜けた。車から降りただけでは抜けないので、ここは明示的な意思表示
                ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.retired_self")
                        .withStyle(ChatFormatting.GRAY), false);
                return 1;
            }
            case LEFT -> {
                ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.left"), true);
                return 1;
            }
        }
        return 0;
    }

    private static int listEntrants(CommandContext<CommandSourceStack> ctx) {
        var names = RaceManager.entrants(ctx.getSource().getLevel());
        if (names.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.no_entrants"), false);
            return 0;
        }
        String open = RaceManager.racingCourse(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "race.kurumamod.entrants", open == null ? "-" : open,
                names.size(), String.join(", ", names)), false);
        return names.size();
    }

    /** カウントダウンを始める。開催した本人か OP だけ。 */
    private static int startRace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        if (!RaceManager.hasSession(level)) {
            // 権限より先に「そもそも無い」を言う。主催者でないと断られても次の手が分からない
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_race_to_start"));
            return 0;
        }
        if (!RaceManager.canManage(ctx.getSource().getPlayerOrException())) {
            return failNotHost(ctx);
        }
        switch (RaceManager.beginCountdown(level)) {
            case NO_RACE -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_race_to_start"));
                return 0;
            }
            case ALREADY_STARTED -> {
                ctx.getSource().sendFailure(Component.translatable(
                        "race.kurumamod.already_started", RaceManager.racingCourse(level)));
                return 0;
            }
            case NO_ENTRANTS -> {
                ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_entrants_to_start"));
                return 0;
            }
            case STARTED -> {
                return 1;
            }
        }
        return 0;
    }

    /** 誰なら操作できるのかを名指しする。「本人だけ」では誰なのか分からない。 */
    private static int failNotHost(CommandContext<CommandSourceStack> ctx) {
        String host = RaceManager.hostName(ctx.getSource().getLevel());
        ctx.getSource().sendFailure(host.isEmpty()
                ? Component.translatable("race.kurumamod.not_host")
                : Component.translatable("race.kurumamod.not_host_named", host));
        return 0;
    }

    private static int stopRace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!RaceManager.hasSession(ctx.getSource().getLevel())) {
            ctx.getSource().sendFailure(Component.translatable("race.kurumamod.no_race_to_start"));
            return 0;
        }
        if (!RaceManager.canManage(ctx.getSource().getPlayerOrException())) {
            return failNotHost(ctx);
        }
        RaceManager.stopRace(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.translatable("race.kurumamod.race_stopped"), true);
        return 1;
    }
}
