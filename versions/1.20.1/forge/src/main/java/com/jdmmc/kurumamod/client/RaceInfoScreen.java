package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.network.KurumaNetwork;
import com.jdmmc.kurumamod.network.RaceActionPacket;
import com.jdmmc.kurumamod.network.RaceLobbyPacket;
import com.jdmmc.kurumamod.race.RaceManager;
import com.jdmmc.kurumamod.tuning.Regulation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 参加したレースの画面（参加中に K）。<b>そのレースの情報と参加者だけを見る。</b>
 *
 * <p>コースを選ぶ場所は持たない——1 つのワールドで開かれるレースは 1 つで、
 * 参加した人が知りたいのはそのレースのことだけだから。</p>
 *
 * <p>走りながら開く画面なので、ゲームは止めない。</p>
 */
public final class RaceInfoScreen extends Screen {

    private static final int ROW = 20;

    private Button leave;
    private Button join;
    /** 観戦に切り替える。エントリーせず、レースの HUD だけを見る。 */
    private Button spectate;
    private Button unspectate;

    public RaceInfoScreen() {
        super(Component.translatable("screen.kurumamod.race_info.title"));
    }

    @Override
    protected void init() {
        int left = (width - panelWidth()) / 2;
        int right = left + panelWidth();
        int y = height - 34;
        for (RaceTabs.Tab tab : RaceTabs.create(left, 6, RaceTabs.Page.RACE)) {
            addRenderableWidget(tab);
        }
        leave = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_info.leave"),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.LEAVE)))
                .bounds(right - 90, y, 90, 20).build());
        join = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_info.join"),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.JOIN)))
                .bounds(right - 90, y, 90, 20).build());
        spectate = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_info.spectate"),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.SPECTATE)))
                .bounds(right - 184, y, 90, 20).build());
        unspectate = addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.race_info.unspectate"),
                        button -> KurumaNetwork.CHANNEL.sendToServer(RaceActionPacket.of(RaceActionPacket.Action.UNSPECTATE)))
                .bounds(right - 184, y, 90, 20).build());
    }

    private int panelWidth() {
        // 枠（フル HD の幅が上限）いっぱいに広げる
        return UiFrame.width(width) - 24;
    }

    @Override
    public void tick() {
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby == null) {
            onClose();
            return;
        }
        boolean open = lobby.phase() == RaceLobbyPacket.Phase.OPEN;
        boolean result = lobby.phase() == RaceLobbyPacket.Phase.RESULT;
        leave.visible = lobby.joined();
        join.visible = !lobby.joined() && open;
        // ゴールした人とリザルトの間は切り替えない（リザルトに残すため）
        spectate.visible = !lobby.spectating() && !result && !(lobby.joined() && RaceHud.hasFinished());
        unspectate.visible = lobby.spectating();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 の Screen#render は背景を描かないので、自分で敷く（ワールドの中では半透明の暗幕）
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        RaceLobbyPacket lobby = RaceLobby.lobby();
        if (lobby == null) {
            return;
        }
        Font font = this.font;
        int panel = panelWidth();
        int left = (width - panel) / 2;
        int top = Math.max(26, height / 2 - 120);

        // 見出しの帯
        String tag = lobby.spectating() && lobby.phase() != RaceLobbyPacket.Phase.RESULT ? "WATCHING"
                : switch (lobby.phase()) {
            case OPEN -> lobby.joined() ? "ENTERED" : "ENTRY OPEN";
            case COUNTDOWN, RUNNING -> "LIVE";
            case RESULT -> "RESULT";
            default -> "";
        };
        int tagWidth = font.width(tag) + 14;
        boolean entered = lobby.joined() && lobby.phase() == RaceLobbyPacket.Phase.OPEN;
        Slant.fill(graphics, left, top, tagWidth, 17, entered || lobby.phase() != RaceLobbyPacket.Phase.OPEN
                ? RaceLobby.WHITE : RaceLobby.ACCENT);
        Slant.text(graphics, font, tag, left + 7, top + 5, entered || lobby.phase() != RaceLobbyPacket.Phase.OPEN
                ? RaceLobby.INK : RaceLobby.WHITE);
        Slant.fill(graphics, left + tagWidth, top, panel - tagWidth, 17, RaceLobby.PANEL);
        Component host = lobby.bestMillis() > 0
                ? Component.translatable("screen.kurumamod.race_info.host_best", lobby.hostName(),
                RaceManager.format(lobby.bestMillis()))
                : Component.translatable("screen.kurumamod.race_info.host", lobby.hostName());
        int hostWidth = Math.min(font.width(host), (panel - tagWidth) / 2);
        Slant.fit(graphics, font, host, left + panel - 8 - hostWidth, top + 5, hostWidth, RaceLobby.GREY);
        Slant.fit(graphics, font, Component.translatable("screen.kurumamod.race_info.course",
                lobby.course(), lobby.laps(), lobby.checkpoints()), left + tagWidth + 7, top + 5,
                panel - tagWidth - 7 - hostWidth - 16, RaceLobby.WHITE);

        // 左: 決まりと自分の車の値／右: 参加者
        int columnTop = top + 19;
        int half = (panel - 2) / 2;
        Regulation regulation = RaceLobby.regulation();
        List<RaceLobby.Rule> rules = RaceLobby.rules(regulation);
        RaceLobbyPacket.Entrant self = RaceLobby.self();

        graphics.fill(left, columnTop, left + half, columnTop + 13, RaceLobby.PANEL);
        Slant.text(graphics, font, "REGULATION", left + 7, columnTop + 3, RaceLobby.WHITE);
        Component mineHead = Component.translatable("screen.kurumamod.race_info.rule_mine");
        Slant.text(graphics, font, mineHead, left + half - 7 - font.width(mineHead), columnTop + 3, RaceLobby.GREY);
        // クラスで開いたレースなら、見出しの横にクラスの名前
        String className = RaceLobby.classNameOf(regulation);
        if (className != null) {
            int classX = left + 7 + font.width("REGULATION") + 8;
            int room = left + half - 7 - font.width(mineHead) - 14 - classX;
            if (room > 12) {
                int shown = Math.min(font.width(className), room);
                Slant.fill(graphics, classX - 4, columnTop + 1, shown + 10, 11, RaceLobby.ACCENT);
                Slant.fit(graphics, font, Component.literal(className), classX + 1, columnTop + 3, room,
                        RaceLobby.WHITE);
            }
        }
        int y = columnTop + 14;
        if (rules.isEmpty()) {
            graphics.fill(left, y, left + half, y + ROW - 1, RaceLobby.PANEL_LIGHT);
            Slant.text(graphics, font, Component.translatable("race.kurumamod.rule.free"), left + 7, y + 6,
                    RaceLobby.GREY);
            y += ROW;
        }
        for (int i = 0; i < rules.size(); i++) {
            RaceLobby.Rule rule = rules.get(i);
            graphics.fill(left, y, left + half, y + ROW - 1, (i & 1) == 0 ? RaceLobby.PANEL : RaceLobby.PANEL_LIGHT);
            Slant.text(graphics, font, rule.label(), left + 7, y + 6, RaceLobby.WHITE);
            String mine = RaceLobby.myValue(rule.kind());
            boolean bad = self != null && self.violations().stream().anyMatch(v -> sameRule(v.kind(), rule.kind()));
            int mineWidth = font.width(mine);
            int mineX = left + half - 7 - mineWidth;
            if (bad) {
                graphics.fill(mineX - 3, y + 3, left + half - 4, y + ROW - 4, RaceLobby.NG);
            }
            Slant.text(graphics, font, mine, mineX, y + 6, bad ? RaceLobby.WHITE : RaceLobby.OK_DOT);
            Slant.text(graphics, font, rule.rule(), mineX - 10 - font.width(rule.rule()), y + 6, RaceLobby.WHITE);
            y += ROW;
        }
        int leftBottom = y;

        int rightLeft = left + half + 2;
        int rightWidth = panel - half - 2;
        graphics.fill(rightLeft, columnTop, rightLeft + rightWidth, columnTop + 13, RaceLobby.PANEL);
        Slant.text(graphics, font, "ENTRANTS " + lobby.entrants().size(), rightLeft + 7, columnTop + 3, RaceLobby.WHITE);
        y = columnTop + 14;
        for (int i = 0; i < lobby.entrants().size(); i++) {
            RaceLobbyPacket.Entrant entrant = lobby.entrants().get(i);
            graphics.fill(rightLeft, y, rightLeft + rightWidth, y + ROW - 1,
                    entrant.you() ? 0x40FFFFFF : (i & 1) == 0 ? RaceLobby.PANEL : RaceLobby.PANEL_LIGHT);
            if (entrant.you()) {
                graphics.fill(rightLeft, y, rightLeft + 2, y + ROW - 1, RaceLobby.ACCENT);
            }
            Slant.fit(graphics, font, entrant.name(), rightLeft + 7, y + 6, 80, RaceLobby.WHITE);
            verdictBadge(graphics, font, entrant, rightLeft + rightWidth - 6, y + 4);
            Slant.fit(graphics, font, RaceLobby.carName(entrant.carId()), rightLeft + 92, y + 6, rightWidth - 150,
                    RaceLobby.GREY);
            y += ROW;
        }
        int bottom = Math.max(leftBottom, y) + 6;

        // 自分の判定
        if (self != null) {
            boolean ok = self.verdict() == RaceLobbyPacket.Verdict.OK;
            boolean noCar = self.verdict() == RaceLobbyPacket.Verdict.NO_CAR;
            int color = ok ? RaceLobby.OK : noCar ? 0xFF3A3A3A : RaceLobby.NG;
            int text = RaceLobby.WHITE;
            Slant.fill(graphics, left, bottom, panel, 22, color);
            Component title = ok ? Component.translatable(lobby.phase() == RaceLobbyPacket.Phase.OPEN
                    ? "screen.kurumamod.race_info.ok_waiting" : "screen.kurumamod.race_info.ok")
                    : noCar ? Component.translatable("screen.kurumamod.race_info.no_car")
                    : Component.translatable("screen.kurumamod.race_info.violation",
                    RaceLobby.describeAll(self.violations()));
            Slant.text(graphics, font, title, left + 10, bottom + 7, text);
            Component sub = Component.translatable(ok
                    ? "screen.kurumamod.race_info.ok_sub" : "screen.kurumamod.race_info.violation_sub");
            if (!noCar) {
                Slant.text(graphics, font, sub, left + panel - 10 - font.width(sub), bottom + 7, text);
            }
        }

        Component hint = Component.translatable("screen.kurumamod.race_info.hint",
                KurumaKeys.OPEN_RACE.getTranslatedKeyMessage());
        graphics.drawString(font, hint, left, height - 28, RaceLobby.GREY, false);
    }

    /** 決まりの行と違反の種類が同じものを指すか。運転補助は 1 行にまとめてある。 */
    private static boolean sameRule(Regulation.Kind violation, Regulation.Kind rule) {
        boolean assist = violation == Regulation.Kind.TRACTION_CONTROL || violation == Regulation.Kind.ABS;
        boolean assistRule = rule == Regulation.Kind.TRACTION_CONTROL || rule == Regulation.Kind.ABS;
        return assist ? assistRule : violation == rule;
    }

    /** 判定の札。右端を {@code right} に揃える。 */
    static void verdictBadge(GuiGraphics graphics, Font font, RaceLobbyPacket.Entrant entrant, int right, int y) {
        Component label = switch (entrant.verdict()) {
            case OK -> Component.translatable("race.kurumamod.verdict.ok");
            case VIOLATION -> Component.translatable("race.kurumamod.verdict.violation");
            case NO_CAR -> Component.translatable("race.kurumamod.verdict.no_car");
        };
        int w = font.width(label) + 8;
        int bg = switch (entrant.verdict()) {
            case OK -> RaceLobby.OK;
            case VIOLATION -> RaceLobby.NG;
            case NO_CAR -> 0xFF3A3A3A;
        };
        graphics.fill(right - w, y, right, y + 11, bg);
        graphics.drawString(font, label, right - w + 4, y + 2,
                RaceLobby.WHITE, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (KurumaKeys.OPEN_RACE.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (CarClientDriver.isDrivingKey(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
