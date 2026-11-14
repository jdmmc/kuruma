package com.jdmmc.kurumamod.client.hud;

import com.jdmmc.kurumamod.client.race.RaceClient;
import com.jdmmc.kurumamod.client.ui.Slant;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.race.RaceText;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 画面中央の知らせの帯（見出し＋説明）。観戦への切り替え・受付の開始・中止と終了・リタイアで出す。
 *
 * <p><b>チャットの 1 行だけでは気づかない</b>——走っている最中や信号の間にチャットは読まない。自分に関わる
 * 出来事は、ここで大きく出す（チャットにも残す。帯は数秒で消える）。</p>
 *
 * <p>一度に出すのは 1 つ。新しい知らせは前のものを置き換える（続けて起きたなら、新しい方が今の状態）。
 * 出している間に前提が崩れたら（観戦をやめた・参加した）、残りの時間を待たずに消す。</p>
 */
public final class RaceNotice {

    /** 赤。観戦・中止・リタイアなど、自分の走りが止まった知らせ。 */
    public static final int RED = 0xFFE1261C;
    /** 緑。受付が始まった（参加できる）。 */
    public static final int GREEN = 0xFF16A34A;
    /** 金。レースが終わった。 */
    public static final int GOLD = 0xFFE0A21B;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF111111;
    /** 出しておく長さ（3 秒）。 */
    private static final int TICKS = 60;

    private static RaceText title;
    private static RaceText sub;
    private static int accent = RED;
    private static BooleanSupplier still = () -> true;
    private static int ticks;

    private RaceNotice() {
    }

    /**
     * 知らせを出す。
     *
     * @param still 出し続けてよいか。毎ティック見て、false になったらすぐ消す
     */
    public static void show(RaceText title, RaceText sub, int accent, BooleanSupplier still) {
        RaceNotice.title = title;
        RaceNotice.sub = sub;
        RaceNotice.accent = accent;
        RaceNotice.still = still;
        ticks = TICKS;
    }

    public static void show(RaceText title, RaceText sub, int accent) {
        show(title, sub, accent, () -> true);
    }

    public static void clear() {
        ticks = 0;
    }

    public static boolean showing() {
        return ticks > 0;
    }

    static void tick() {
        if (ticks <= 0) {
            return;
        }
        if (!still.getAsBoolean()) {
            ticks = 0;
        } else if (RaceClient.get().raceScreen() == null) {
            // K の画面を開いている間は減らさない（受付を始めた・中止したのは K の画面の中で、帯は画面の下に隠れる）。
            // 閉じてから 3 秒出す
            ticks--;
        }
    }

    /** 帯を描く。高さは説明の行数で決まる。 */
    static void render(UiGraphics graphics, UiFont font, int width, int y) {
        if (ticks <= 0 || title == null) {
            return;
        }
        // 説明は画面の幅で折り返す（英語だと 1 行では GUI スケール自動の幅 480 に収まらない）
        List<RaceText> lines = sub == null ? List.of() : font.split(sub, Math.max(100, width - 40));
        int height = (lines.isEmpty() ? 24 : 28) + lines.size() * 10;
        graphics.fill(0, y, width, y + height, INK);
        graphics.fill(0, y, width, y + 2, accent);
        graphics.fill(0, y + height - 2, width, y + height, accent);
        String text = title.getString();
        float scale = 2.0F;
        int w = Slant.width(font, text, scale);
        Slant.scaled(graphics, font, text, (width - w) / 2.0F, y + 6, scale, accent);
        int lineY = y + 25;
        for (RaceText line : lines) {
            graphics.drawString(font, line, (width - font.width(line)) / 2, lineY, WHITE, false);
            lineY += 10;
        }
    }
}
