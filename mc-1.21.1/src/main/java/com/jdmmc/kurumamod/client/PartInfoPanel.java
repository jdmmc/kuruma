package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import com.jdmmc.kurumamod.physics.Aero;
import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.physics.PartAero;
import com.jdmmc.kurumamod.physics.Tire;
import com.jdmmc.kurumamod.physics.Turbo;
import com.jdmmc.kurumamod.surface.RoadSurface;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 換装画面の右に出す、部品の性能の表。<b>タイヤとタービンだけ</b>（ホイールとブローオフは走りに効かない）。
 *
 * <p>数字は素性（{@code data/} の部品の定義）から読む。タイヤは路面ごとの倍率を、タービンは
 * <b>いま乗っている車に付けたら</b>どうなるか（トルク・出力・立ち上がる回転数）を出す。
 * 回転数は車のレブリミットで変わるので、割合のまま出しても分からない。</p>
 *
 * <p><b>画面が狭ければパネルごと縮める。</b>下にはタブとタイルが並んでいるので、
 * 入りきらない分を下へはみ出させると重なる。</p>
 */
final class PartInfoPanel {

    static final int WIDTH = 174;
    private static final int PADDING = 6;
    private static final int LINE = 11;
    private static final int BAR_LEFT = 58;
    private static final int BAR_WIDTH = 72;

    private static final int COLOR_BACK = 0xB0101010;
    private static final int COLOR_BAR_BACK = 0x40FFFFFF;
    private static final int COLOR_BAR = ScreenStyle.ACCENT;
    private static final int COLOR_NORMAL_MARK = 0xFFFFFFFF;
    private static final int COLOR_BETTER = 0xFF7CD67C;
    private static final int COLOR_WORSE = 0xFFE07070;
    private static final int COLOR_NA = 0xFF8A8A8A;

    /** 舗装 × 最大の倍率くらいまで。これより強いタイヤは棒の端で頭打ちに見える。 */
    private static final double GRIP_FULL_SCALE = 1.3;

    private PartInfoPanel() {
    }

    /** この場所に表を出すか。車体の部品は空力が効くものがあるので出す（見た目だけの部品はそう書く）。 */
    static boolean covers(PartSlot slot) {
        return slot == PartSlot.TIRE || slot == PartSlot.TURBO || slot.isBodyPart();
    }

    /**
     * 描く。
     *
     * @param partId  出す部品。null は「ノーマル」「なし」（何も付けていない状態）
     * @param preview マウスを乗せて見比べているか（装着中ではない）
     * @param spec    いま乗っている車の諸元（部品込み）。タービンのトルクと回転数、空力の比較に使う
     * @param fitment いま実際に付いている状態（初期装備込み）。空力部品の角度と、付け替える前の部品を見る
     * @param bottom  使ってよい下端。入りきらなければ縮める
     */
    static void render(GuiGraphics graphics, Font font, PartSlot slot, @Nullable ResourceLocation partId,
                       Component label, boolean preview, CarSpec spec, PartFitment fitment,
                       int right, int top, int bottom) {
        List<Row> rows = slot == PartSlot.TIRE ? tireRows(tireOf(partId))
                : slot == PartSlot.TURBO ? turboRows(turboOf(partId), spec)
                : slot == PartSlot.EXHAUST ? exhaustRows(partId, fitment)
                : aeroRows(slot, partId, spec, fitment);
        int height = PADDING * 2 + LINE * 2 + rows.stream().mapToInt(Row::height).sum();
        float scale = Math.min(1.0F, (bottom - top) / (float) height);
        if (scale < 0.5F) {
            return; // 読めないほど小さくなるなら出さない
        }

        graphics.pose().pushPose();
        graphics.pose().translate(right - WIDTH * scale, top, 0);
        graphics.pose().scale(scale, scale, 1.0F);

        graphics.fill(0, 0, WIDTH, height, COLOR_BACK);
        int y = PADDING;
        graphics.drawString(font, label, PADDING, y, ScreenStyle.LABEL, false);
        y += LINE;
        graphics.drawString(font, Component.translatable(preview
                ? "screen.kurumamod.part.info.preview" : "screen.kurumamod.part.info.fitted"),
                PADDING, y, ScreenStyle.SUB, false);
        y += LINE;
        for (Row row : rows) {
            row.draw(graphics, font, y);
            y += row.height();
        }
        graphics.pose().popPose();
    }

    private static Tire tireOf(@Nullable ResourceLocation id) {
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.tire() == null ? Tire.NORMAL : part.tire();
    }

    private static Turbo turboOf(@Nullable ResourceLocation id) {
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null || part.turbo() == null ? Turbo.NONE : part.turbo();
    }

    // ------------------------------------------------------------------
    // タイヤ
    // ------------------------------------------------------------------

    /** 表に並べる路面の順。舗装から、滑りやすい順に。 */
    private static final RoadSurface[] SURFACES = {
            RoadSurface.PAVED, RoadSurface.DIRT, RoadSurface.GRAVEL,
            RoadSurface.SAND, RoadSurface.SNOW, RoadSurface.ICE};

    private static List<Row> tireRows(Tire tire) {
        List<Row> rows = new ArrayList<>();
        rows.add(heading("screen.kurumamod.part.info.response"));
        rows.add(bar(Component.translatable("screen.kurumamod.part.info.stiffness"),
                tire.stiffness() / 1.5, 1.0 / 1.5, ratio(tire.stiffness())));

        rows.add(heading("screen.kurumamod.part.info.grip"));
        for (RoadSurface surface : SURFACES) {
            double base = surface.gripScale();
            double factor = tire.gripOn(surface.surfaceName());
            // 棒は路面込みの実際のグリップ。目印はノーマルの位置
            rows.add(bar(Component.translatable(surface.translationKey()),
                    base * factor / GRIP_FULL_SCALE, base / GRIP_FULL_SCALE, ratio(factor)));
        }

        List<String> rolling = new ArrayList<>();
        for (RoadSurface surface : SURFACES) {
            double factor = tire.rollingOn(surface.surfaceName());
            if (Math.abs(factor - 1.0) > 1e-6) {
                rolling.add(Component.translatable(surface.translationKey()).getString()
                        + String.format(" ×%.2f", factor));
            }
        }
        rows.add(heading("screen.kurumamod.part.info.rolling"));
        rows.add(text(rolling.isEmpty()
                ? Component.translatable("screen.kurumamod.part.info.same_as_normal")
                : Component.literal(String.join("  ", rolling)), ScreenStyle.LABEL));
        return rows;
    }

    // ------------------------------------------------------------------
    // 空力（車体の部品）
    // ------------------------------------------------------------------

    /** ダウンフォースと最高速を比べる速度 [m/s]。調整画面の補足と同じ 200km/h。 */
    private static final double AERO_SPEED = 200.0 / 3.6;

    /**
     * 空力部品の表。<b>その部品だけのダウンフォース</b>と、<b>付け替えたら車全体がどう変わるか</b>。
     *
     * <p>車全体は「この場所の今の部品を外して、この部品を付けた」状態と今を比べる。ほかの場所の部品と
     * 調整画面のダウンフォースはそのまま。角度は今の設定（上書きが無ければその部品の既定）。</p>
     */
    private static List<Row> aeroRows(PartSlot slot, @Nullable ResourceLocation partId, CarSpec spec,
                                      PartFitment fitment) {
        List<Row> rows = new ArrayList<>();
        Aero aero = aeroOf(partId);
        Double angle = fitment.getAngle(slot);
        PartAero candidate = aero == null ? PartAero.NONE : aero.at(angle != null ? angle : aero.defaultAngle());
        Aero fittedAero = aeroOf(fitment.getPart(slot));
        PartAero fitted = fittedAero == null ? PartAero.NONE
                : fittedAero.at(angle != null ? angle : fittedAero.defaultAngle());
        PartAero others = new PartAero(spec.partAero().front() - fitted.front(), spec.partAero().rear() - fitted.rear());
        CarSpec now = spec;
        CarSpec after = spec.toBuilder().partAero(others.plus(candidate)).build();

        rows.add(heading("screen.kurumamod.part.info.downforce"));
        if (aero == null || (candidate.front() <= 0.0 && candidate.rear() <= 0.0 && !aero.adjustable())) {
            rows.add(text(Component.translatable("screen.kurumamod.part.info.no_aero"), ScreenStyle.SUB));
        } else {
            rows.add(text(Component.translatable("screen.kurumamod.part.info.downforce_value",
                    kg(candidate.front()), kg(candidate.rear())), COLOR_BETTER));
            if (aero.adjustable()) {
                PartAero low = aero.at(0.0);
                PartAero high = aero.at(1.0);
                rows.add(text(Component.translatable("screen.kurumamod.part.info.downforce_range",
                        kg(low.front() + low.rear()), kg(high.front() + high.rear())), ScreenStyle.SUB));
            }
        }

        rows.add(heading("screen.kurumamod.part.info.whole_car"));
        rows.add(text(Component.literal(String.format("%s → %s kg",
                kgForce(now.totalDownforce(AERO_SPEED)), kgForce(after.totalDownforce(AERO_SPEED)))),
                compare(after.totalDownforce(AERO_SPEED), now.totalDownforce(AERO_SPEED))));
        if (after.totalDownforce(1.0) > 0.0) {
            rows.add(text(Component.translatable("screen.kurumamod.part.info.balance",
                    String.format("%.0f", after.downforceFrontShare() * 100.0)), ScreenStyle.LABEL));
        }
        rows.add(heading("screen.kurumamod.part.info.grip_at_speed"));
        rows.add(text(Component.literal(String.format("×%.2f → ×%.2f",
                now.aeroGripFactor(AERO_SPEED), after.aeroGripFactor(AERO_SPEED))),
                compare(after.aeroGripFactor(AERO_SPEED), now.aeroGripFactor(AERO_SPEED))));
        rows.add(heading("screen.kurumamod.part.info.top_speed"));
        double nowTop = now.estimatedTopSpeed() * 3.6;
        double afterTop = after.estimatedTopSpeed() * 3.6;
        // 最高速は下がるほど悪い。比べる向きが逆なので入れ替えて渡す
        rows.add(text(Component.literal(String.format("%.0f → %.0f km/h", nowTop, afterTop)),
                compare(afterTop, nowTop)));
        rows.add(text(Component.translatable("screen.kurumamod.part.info.drag_area",
                String.format("%.2f", now.effectiveDragArea()), String.format("%.2f", after.effectiveDragArea())),
                ScreenStyle.SUB));
        return rows;
    }

    /**
     * マフラーの表。<b>音だけ</b>なので、音程と音量と音源。走りが変わらないことも書いておく
     * （性能の表が出ているのに何も変わらないと、効いていないのかと迷う）。
     */
    private static List<Row> exhaustRows(@Nullable ResourceLocation partId, PartFitment fitment) {
        List<Row> rows = new ArrayList<>();
        CarPartModel.Exhaust exhaust = partId == null || PartFitment.isNone(partId)
                ? null : CarPartModel.get(partId).exhaust();
        rows.add(heading("screen.kurumamod.part.info.exhaust_sound"));
        if (exhaust == null) {
            rows.add(text(Component.translatable("screen.kurumamod.part.info.exhaust_stock"), ScreenStyle.SUB));
        } else {
            Double tone = fitment.getTone(PartSlot.EXHAUST);
            double now = exhaust.pitchAt(tone != null ? tone : exhaust.defaultTone());
            rows.add(text(Component.translatable("screen.kurumamod.part.info.exhaust_pitch",
                    String.format("%.2f", now)), now < 1.0 ? COLOR_BETTER : ScreenStyle.LABEL));
            if (exhaust.adjustable()) {
                rows.add(text(Component.translatable("screen.kurumamod.part.info.exhaust_range",
                        String.format("%.2f", exhaust.pitchMax()), String.format("%.2f", exhaust.pitchMin())),
                        ScreenStyle.SUB));
            }
            rows.add(text(Component.translatable("screen.kurumamod.part.info.exhaust_volume",
                    String.format("%.2f", exhaust.volume())), ScreenStyle.LABEL));
            rows.add(text(Component.translatable(exhaust.sound() != null
                    ? "screen.kurumamod.part.info.exhaust_own_sound"
                    : "screen.kurumamod.part.info.exhaust_car_sound"), ScreenStyle.SUB));
        }
        rows.add(text(Component.translatable("screen.kurumamod.part.info.exhaust_no_performance"), ScreenStyle.SUB));
        return rows;
    }

    @Nullable
    private static Aero aeroOf(@Nullable ResourceLocation id) {
        CarPart part = id == null ? null : CarParts.get(id);
        return part == null ? null : part.aero();
    }

    /** ダウンフォースの面積 [m²] を、比べる速度で何 kg 押し付けるかに直す。 */
    private static String kg(double area) {
        return kgForce(0.5 * CarSpec.AIR_DENSITY * area * AERO_SPEED * AERO_SPEED);
    }

    private static String kgForce(double newtons) {
        return String.format("%.0f", newtons / 9.81);
    }

    /** 増えたら緑、減ったら赤、変わらなければ控えめに。 */
    private static int compare(double after, double now) {
        return Math.abs(after - now) < 1e-6 ? ScreenStyle.SUB : after > now ? COLOR_BETTER : COLOR_WORSE;
    }

    /** ノーマルに対する倍率の文字。良くなるなら緑、悪くなるなら赤。 */
    private static Ratio ratio(double factor) {
        int color = Math.abs(factor - 1.0) < 1e-6 ? ScreenStyle.SUB
                : factor > 1.0 ? COLOR_BETTER : COLOR_WORSE;
        return new Ratio(String.format("×%.2f", factor), color);
    }

    // ------------------------------------------------------------------
    // タービン
    // ------------------------------------------------------------------

    private static final int GRAPH_HEIGHT = 34;
    private static final int GRAPH_COLUMNS = 52;

    private static List<Row> turboRows(Turbo turbo, CarSpec spec) {
        List<Row> rows = new ArrayList<>();
        double[] natural = peakTorqueAndPower(spec, Turbo.NONE);
        double[] boosted = peakTorqueAndPower(spec, turbo);
        rows.add(heading("screen.kurumamod.part.info.torque"));
        rows.add(text(Component.literal(String.format("%.0f → %.0f N·m", natural[0], boosted[0])),
                turbo.present() ? COLOR_BETTER : ScreenStyle.LABEL));
        rows.add(heading("screen.kurumamod.part.info.power"));
        rows.add(text(Component.literal(String.format("%.0f → %.0f ps", natural[1], boosted[1])),
                turbo.present() ? COLOR_BETTER : ScreenStyle.LABEL));
        if (!turbo.present()) {
            rows.add(text(Component.translatable("screen.kurumamod.part.info.natural"), ScreenStyle.SUB));
        } else {
            rows.add(heading("screen.kurumamod.part.info.spool"));
            rows.add(text(Component.literal(String.format("%.0f 〜 %.0f rpm",
                    rpmAt(spec, turbo.spoolFrom()), rpmAt(spec, turbo.spoolFull()))), ScreenStyle.LABEL));
            rows.add(heading("screen.kurumamod.part.info.lag"));
            rows.add(text(Component.translatable("screen.kurumamod.part.info.lag_value",
                    String.format("%.1f", turbo.spoolRate()), lagWord(turbo.spoolRate())), ScreenStyle.LABEL));
        }
        rows.add(heading("screen.kurumamod.part.info.curve"));
        rows.add(new Graph(spec, turbo));
        return rows;
    }

    /** 回転の割合（アイドル 0 〜 レブ 1）から回転数。 */
    private static double rpmAt(CarSpec spec, double fraction) {
        return spec.idleRpm() + fraction * (spec.redlineRpm() - spec.idleRpm());
    }

    /** 全開で回転を保ったとき、ブーストが落ち着く先（CarPhysics#updateBoost と同じ式）。 */
    private static double steadyBoost(CarSpec spec, Turbo turbo, double rpm) {
        if (!turbo.present()) {
            return 0.0;
        }
        double span = Math.max(1.0, spec.redlineRpm() - spec.idleRpm());
        double rev = Math.max(0.0, Math.min(1.0, (rpm - spec.idleRpm()) / span));
        double spool = Math.max(0.0, Math.min(1.0,
                (rev - turbo.spoolFrom()) / (turbo.spoolFull() - turbo.spoolFrom())));
        return spool * spool * (3.0 - 2.0 * spool);
    }

    private static double torqueWith(CarSpec spec, Turbo turbo, double rpm) {
        return spec.engineTorque(rpm) * (1.0 + turbo.boostGain() * steadyBoost(spec, turbo, rpm));
    }

    /** {最大トルク [N·m], 最高出力 [ps]}。アイドルからレブまでを刻んで探す。 */
    private static double[] peakTorqueAndPower(CarSpec spec, Turbo turbo) {
        double torque = 0.0;
        double power = 0.0;
        for (int i = 0; i <= 200; i++) {
            double rpm = spec.idleRpm() + (spec.redlineRpm() - spec.idleRpm()) * i / 200.0;
            double t = torqueWith(spec, turbo, rpm);
            torque = Math.max(torque, t);
            power = Math.max(power, t * rpm * 2.0 * Math.PI / 60.0 / 735.5);
        }
        return new double[]{torque, power};
    }

    /** 立ち上がりの速さの言葉。数字だけでは速いのか遅いのか分からない。 */
    private static Component lagWord(double rate) {
        String key = rate >= 2.5 ? "quick" : rate >= 1.5 ? "medium" : "slow";
        return Component.translatable("screen.kurumamod.part.info.lag." + key);
    }

    // ------------------------------------------------------------------
    // 行
    // ------------------------------------------------------------------

    private record Ratio(String text, int color) {
    }

    private interface Row {
        int height();

        void draw(GuiGraphics graphics, Font font, int y);
    }

    private static Row heading(String key) {
        return new Row() {
            public int height() {
                return LINE + 2;
            }

            public void draw(GuiGraphics graphics, Font font, int y) {
                graphics.drawString(font, Component.translatable(key), PADDING, y + 2, ScreenStyle.ACCENT, false);
            }
        };
    }

    private static Row text(Component line, int color) {
        return new Row() {
            public int height() {
                return LINE;
            }

            public void draw(GuiGraphics graphics, Font font, int y) {
                graphics.drawString(font, font.plainSubstrByWidth(line.getString(), WIDTH - PADDING * 2),
                        PADDING + 4, y, color, false);
            }
        };
    }

    /**
     * 名前・棒・倍率の 1 行。
     *
     * @param fill   棒の長さ（0..1）
     * @param normal ノーマルの位置（0..1）。白い縦線を引く
     */
    private static Row bar(Component name, double fill, double normal, Ratio ratio) {
        return new Row() {
            public int height() {
                return LINE;
            }

            public void draw(GuiGraphics graphics, Font font, int y) {
                graphics.drawString(font, name, PADDING + 4, y, ScreenStyle.LABEL, false);
                int top = y + 1;
                int bottom = y + 8;
                graphics.fill(BAR_LEFT, top, BAR_LEFT + BAR_WIDTH, bottom, COLOR_BAR_BACK);
                int length = (int) Math.round(Math.max(0.0, Math.min(1.0, fill)) * BAR_WIDTH);
                graphics.fill(BAR_LEFT, top, BAR_LEFT + length, bottom, COLOR_BAR);
                int mark = BAR_LEFT + (int) Math.round(Math.max(0.0, Math.min(1.0, normal)) * BAR_WIDTH);
                graphics.fill(mark, top - 1, mark + 1, bottom + 1, COLOR_NORMAL_MARK);
                graphics.drawString(font, ratio.text(), BAR_LEFT + BAR_WIDTH + 4, y, ratio.color(), false);
            }
        };
    }

    /** 回転数ごとのトルク。灰色が自然吸気、その上に重なる黄色が過給で増えた分。 */
    private record Graph(CarSpec spec, Turbo turbo) implements Row {
        public int height() {
            return GRAPH_HEIGHT + 12;
        }

        public void draw(GuiGraphics graphics, Font font, int y) {
            int left = PADDING + 4;
            int width = WIDTH - PADDING * 2 - 8;
            int base = y + GRAPH_HEIGHT;
            double max = peakTorqueAndPower(spec, turbo)[0];
            graphics.fill(left, y, left + width, base, COLOR_BAR_BACK);
            int column = Math.max(1, width / GRAPH_COLUMNS);
            for (int i = 0; i * column < width; i++) {
                double rpm = spec.idleRpm() + (spec.redlineRpm() - spec.idleRpm()) * (i + 0.5) * column / width;
                int boostedTop = base - (int) Math.round(torqueWith(spec, turbo, rpm) / max * GRAPH_HEIGHT);
                int naturalTop = base - (int) Math.round(spec.engineTorque(rpm) / max * GRAPH_HEIGHT);
                int x = left + i * column;
                graphics.fill(x, boostedTop, x + column, base, COLOR_BAR);
                graphics.fill(x, naturalTop, x + column, base, COLOR_NA);
            }
            graphics.drawString(font, String.format("%.0f", spec.idleRpm()), left, base + 2, ScreenStyle.SUB, false);
            String red = String.format("%.0f rpm", spec.redlineRpm());
            graphics.drawString(font, red, left + width - font.width(red), base + 2, ScreenStyle.SUB, false);
        }
    }
}
