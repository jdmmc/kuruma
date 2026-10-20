package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 座席とハンドルの位置、車体とホイールのデザイン寸法を合わせるデバッグ画面。
 * Ctrl+G（{@link KurumaKeys#OPEN_SEAT_DEBUG}）で開く。
 *
 * <p>座席（{@code vehicles/car.json} の {@code seat}）は、乗って一人称で見ないと決められない。
 * JSON を書き換えて F3+T で読み直すのを繰り返す代わりに、ここで動かしてその場で見る。
 * 値は {@link CarSeat#setOverride} で<b>この画面を開いたクライアントだけ</b>に効き、
 * ゲームを閉じれば消える。決まったら「JSON をコピー」で {@code seat} の行を取り出して、
 * カーパックの JSON へ貼る。</p>
 *
 * <p>ゲームは止めず、背景も暗くしない（{@code HeadlightScreen} と同じ）。助手席は運転席の
 * 左右反転なので、ここで動かせば一緒に動く。</p>
 *
 * <p><b>ハンドル</b>（{@code steering.offset}）も同じ画面で動かす。ハンドルの位置は座席から
 * 見て決めるもので、片方ずつ別の画面で合わせると行き来することになる。動かせるのは位置だけで、
 * 回す中心と軸はメッシュから読む（{@code SteeringWheelAxis}）。</p>
 *
 * <p><b>デザイン寸法</b>（{@code designWheelBase}・{@code designRideHeight}・{@code designWheelRadius}）は
 * 2 ページ目。メッシュが何 m で作られているかの申告で、間違えると車体やタイヤが小さく／大きく描かれる。
 * 座席の位置は車体の大きさが決まってから合わせるものなので、同じ画面に置いてある。
 * {@link CarModel#setDesignOverride} で、座席と同じく<b>このクライアントだけ・ゲームを閉じるまで</b>効く。</p>
 */
public final class SeatScreen extends Screen {

    /** 何を動かすか。{@link #SEAT} と {@link #STEERING} は 1 ページ目、{@link #DESIGN} は 2 ページ目。 */
    private enum Group {
        SEAT, STEERING, DESIGN
    }

    /**
     * 1 行ぶん。何を動かすか、どの値か（座席・ハンドルは 0: X 右、1: Y 上、2: Z 後ろ [m]、
     * {@link #TILT}: ハンドルの傾き [度]。デザイン寸法は {@link #WHEEL_BASE} など）、
     * 動かせる範囲と、−／＋ 1 回の量（Shift でその 10 倍。スライダーもこの刻み）。
     */
    private record Row(Group group, String key, int axis, double min, double max, double step) {
    }

    /** {@link Row#axis} のうち、ハンドルの傾き [度]。 */
    private static final int TILT = 3;

    /** {@link Row#axis} のうち、デザイン寸法の 3 つ。 */
    private static final int WHEEL_BASE = 0;
    private static final int RIDE_HEIGHT = 1;
    private static final int WHEEL_RADIUS = 2;

    /**
     * ハンドルは座席から見て位置を決めるので、同じ画面に並べる。範囲が狭いのは、
     * メッシュの位置からの<b>ずれ</b>だから（座席は車の原点からの位置）。
     */
    private static final Row[] SEAT_ROWS = {
            new Row(Group.SEAT, "x", 0, -1.5, 1.5, 0.01),
            new Row(Group.SEAT, "y", 1, -1.5, 1.5, 0.01),
            new Row(Group.SEAT, "z", 2, -2.5, 2.5, 0.01),
            new Row(Group.STEERING, "x", 0, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "y", 1, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "z", 2, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "tilt", TILT, -30.0, 30.0, 0.5),
    };

    /** デザイン寸法。メッシュの作りの申告なので、範囲は広めに取ってある。 */
    private static final Row[] DESIGN_ROWS = {
            new Row(Group.DESIGN, "wheel_base", WHEEL_BASE, 0.5, 8.0, 0.01),
            new Row(Group.DESIGN, "ride_height", RIDE_HEIGHT, -0.5, 2.0, 0.005),
            new Row(Group.DESIGN, "wheel_radius", WHEEL_RADIUS, 0.05, 2.0, 0.005),
    };

    /** いま開いているページ（0: 座席・ハンドル、1: デザイン寸法）。開き直しても前のページから。 */
    private static int page;

    // 1080p の GUI スケール自動（高さ 270）に収まるよう詰めてある。行を足すならここを見直す
    private static final int ROW_HEIGHT = 21;
    private static final int TOP = 34;
    private static final int LABEL_WIDTH = 84;
    private static final int SLIDER_WIDTH = 200;
    private static final int BUTTON_WIDTH = 20;
    private static final int ROW_WIDTH = LABEL_WIDTH + BUTTON_WIDTH + 4 + SLIDER_WIDTH + 4 + BUTTON_WIDTH + 4 + BUTTON_WIDTH;
    /** 下のボタンの位置は 1 ページ目（行が多い方）に揃える。ページを替えてもボタンが動かないように。 */
    private static final int BOTTOM_ROW = SEAT_ROWS.length + 2;

    private final ResourceLocation carId;
    private final List<Slider> sliders = new ArrayList<>();
    /** 「コピーした」を出しておく残りのフレーム数。 */
    private int copiedFrames;

    public SeatScreen(ResourceLocation carId) {
        super(Component.translatable("screen.kurumamod.seat.title"));
        this.carId = carId;
    }

    private static Row[] rows() {
        return page == 0 ? SEAT_ROWS : DESIGN_ROWS;
    }

    private CarModel.Vec3 current(Row row) {
        return row.group() == Group.STEERING ? CarSeat.currentSteering(carId) : CarSeat.current(carId);
    }

    private static double component(CarModel.Vec3 v, Row row) {
        return row.axis() == 0 ? v.x() : row.axis() == 1 ? v.y() : v.z();
    }

    private static double component(CarModel.Design d, Row row) {
        return row.axis() == WHEEL_BASE ? d.wheelBase() : row.axis() == RIDE_HEIGHT ? d.rideHeight() : d.wheelRadius();
    }

    private double get(Row row) {
        if (row.group() == Group.DESIGN) {
            return component(CarModel.get(carId).design(), row);
        }
        return row.axis() == TILT ? CarSeat.currentSteeringTilt(carId) : component(current(row), row);
    }

    private double original(Row row) {
        CarModel model = CarModel.fromJson(carId);
        if (row.group() == Group.DESIGN) {
            return component(model.design(), row);
        }
        if (row.axis() == TILT) {
            return model.steeringTilt();
        }
        return component(row.group() == Group.STEERING ? model.steeringOffset() : model.seatOffset(), row);
    }

    private void set(Row row, double value) {
        // 0.1mm（傾きは 0.0001 度）単位に丸める（浮動小数の端数が JSON へ出ないよう）
        double v = Math.round(Mth.clamp(value, row.min(), row.max()) * 10000.0) / 10000.0;
        if (row.group() == Group.DESIGN) {
            CarModel.Design now = CarModel.get(carId).design();
            CarModel.setDesignOverride(carId, new CarModel.Design(
                    row.axis() == WHEEL_BASE ? v : now.wheelBase(),
                    row.axis() == RIDE_HEIGHT ? v : now.rideHeight(),
                    row.axis() == WHEEL_RADIUS ? v : now.wheelRadius()));
            return;
        }
        if (row.axis() == TILT) {
            CarSeat.setSteeringTiltOverride(carId, v);
            return;
        }
        CarModel.Vec3 now = current(row);
        CarModel.Vec3 next = new CarModel.Vec3(
                row.axis() == 0 ? v : now.x(),
                row.axis() == 1 ? v : now.y(),
                row.axis() == 2 ? v : now.z());
        if (row.group() == Group.STEERING) {
            CarSeat.setSteeringOverride(carId, next);
        } else {
            CarSeat.setOverride(carId, next);
        }
    }

    private int left() {
        return (width - ROW_WIDTH) / 2;
    }

    private static int rowY(int index) {
        return TOP + index * ROW_HEIGHT;
    }

    @Override
    protected void init() {
        sliders.clear();
        Row[] rows = rows();
        int x = left() + LABEL_WIDTH;
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            int y = rowY(i);
            Slider slider = new Slider(x + BUTTON_WIDTH + 4, y, SLIDER_WIDTH, 20, row);
            sliders.add(slider);
            addRenderableWidget(Button.builder(Component.literal("-"), button -> nudge(row, slider, -1))
                    .bounds(x, y, BUTTON_WIDTH, 20).build());
            addRenderableWidget(slider);
            addRenderableWidget(Button.builder(Component.literal("+"), button -> nudge(row, slider, 1))
                    .bounds(x + BUTTON_WIDTH + 4 + SLIDER_WIDTH + 4, y, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.literal("R"), button -> {
                        set(row, original(row));
                        slider.sync();
                    })
                    .bounds(x + BUTTON_WIDTH + 4 + SLIDER_WIDTH + 4 + BUTTON_WIDTH + 4, y, BUTTON_WIDTH, 20).build());
        }
        // 4 つ並べて 412。実効幅 427 の画面にも収まる
        int bottom = rowY(BOTTOM_ROW) + 2;
        addRenderableWidget(Button.builder(Component.translatable(page == 0
                        ? "screen.kurumamod.seat.page.design" : "screen.kurumamod.seat.page.seat"), button -> {
                    page = 1 - page;
                    rebuildWidgets();
                })
                .bounds(width / 2 - 206, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.copy"), button -> {
                    if (minecraft != null) {
                        minecraft.keyboardHandler.setClipboard(json());
                        copiedFrames = 60;
                    }
                })
                .bounds(width / 2 - 102, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.reset_all"), button -> {
                    // 戻すのは開いているページの分だけ
                    if (page == 0) {
                        CarSeat.clearOverride(carId);
                        CarSeat.clearSteeringOverride(carId);
                    } else {
                        CarModel.clearDesignOverride(carId);
                    }
                    for (Slider slider : sliders) {
                        slider.sync();
                    }
                })
                .bounds(width / 2 + 2, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 106, bottom, 100, 20).build());
    }

    private void nudge(Row row, Slider slider, int direction) {
        set(row, get(row) + direction * row.step() * (hasShiftDown() ? 10.0 : 1.0));
        slider.sync();
    }

    /** カーパックの {@code vehicles/<車>.json} にそのまま貼れる行（開いているページの分）。 */
    private String json() {
        return page == 0 ? seatJson() + ",\n" + steeringJson() : designJson();
    }

    private String seatJson() {
        return "\"seat\": " + array(CarSeat.current(carId));
    }

    private String steeringJson() {
        return "\"steering\": { \"offset\": " + array(CarSeat.currentSteering(carId))
                + ", \"tilt\": " + number(CarSeat.currentSteeringTilt(carId)) + " }";
    }

    private String designJson() {
        CarModel.Design d = CarModel.get(carId).design();
        return "\"designWheelBase\": " + number(d.wheelBase())
                + ", \"designRideHeight\": " + number(d.rideHeight())
                + ", \"designWheelRadius\": " + number(d.wheelRadius());
    }

    private static String array(CarModel.Vec3 v) {
        return String.format(Locale.ROOT, "[%s, %s, %s]", number(v.x()), number(v.y()), number(v.z()));
    }

    private static String number(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        // 末尾の 0 を落とす（0.5500 → 0.55、-0.0000 → 0.0）
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s += "0";
        }
        return s.equals("-0.0") ? "0.0" : s;
    }

    /** いま乗っている車（この画面の車種なら）。乗っていなければ null。 */
    private CarEntity ridingCar() {
        if (minecraft != null && minecraft.player != null
                && minecraft.player.getVehicle() instanceof CarEntity car && car.getCarId().equals(carId)) {
            return car;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // renderBackground は呼ばない。一人称の見え方を見ながら動かす画面なので
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        // 目線は実際のプレイヤーの目から測る（座席の Y に乗り方のずれと目の高さが足される）。
        // 縦に余裕が無いので車種の横に並べる
        Component sub = Component.literal(carId.toString());
        if (minecraft != null && minecraft.player != null && minecraft.player.getVehicle() != null) {
            double eye = minecraft.player.getEyeY() - minecraft.player.getVehicle().getY();
            sub = sub.copy().append("    ").append(Component.translatable("screen.kurumamod.seat.eye",
                    String.format(Locale.ROOT, "%.3f", eye)));
        }
        graphics.drawCenteredString(font, sub, width / 2, 21, 0xAAAAAA);
        Row[] rows = rows();
        int left = left();
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            // JSON の値から動かした項目は黄色（調整画面と同じ）
            boolean changed = Math.abs(get(row) - original(row)) > 1.0e-6;
            Component label = row.group() == Group.DESIGN
                    ? Component.translatable("screen.kurumamod.seat.design." + row.key())
                    : Component.translatable(row.group() == Group.STEERING
                                    ? "screen.kurumamod.seat.group.steering" : "screen.kurumamod.seat.group.seat")
                            .append(" ").append(Component.translatable("screen.kurumamod.seat." + row.key()));
            graphics.drawString(font, label, left, rowY(i) + 6, changed ? 0xFFFF55 : 0xFFFFFF);
        }
        if (page == 0) {
            graphics.drawCenteredString(font, Component.literal(seatJson()), width / 2, rowY(rows.length) + 6, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.literal(steeringJson()), width / 2, rowY(rows.length + 1) + 2, 0xFFFFFF);
        } else {
            graphics.drawCenteredString(font, Component.literal(designJson()), width / 2, rowY(rows.length) + 6, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.design.note"),
                    width / 2, rowY(rows.length) + 20, 0xAAAAAA);
            // 履いているホイール部品が自分の designRadius を持っていると、車種の値は使われない
            CarEntity car = ridingCar();
            if (car != null && CarPartModel.wheelOf(CarModel.fromJson(carId), car.getEffectiveFitment()).designRadius()
                    != CarModel.fromJson(carId).designWheelRadius()) {
                graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.design.part_radius"),
                        width / 2, rowY(rows.length) + 32, 0xFF7755);
            }
        }
        // 「コピーした」は説明の行に重ねて出す（縦に余裕が無い）
        if (copiedFrames > 0) {
            copiedFrames--;
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.copied"),
                    width / 2, rowY(BOTTOM_ROW) + 26, 0x55FF55);
        } else {
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.hint"),
                    width / 2, rowY(BOTTOM_ROW) + 26, 0xAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 運転に使うキーは画面へ渡さない。開いたまま運転できるので（{@link CarClientDriver#isDrivableScreen}）、
     * 渡すとスペース（サイドブレーキ）でフォーカス中のボタンが押されたり、
     * 矢印キーに割り当てた人のハンドルでフォーカスやスライダーが動いたりする。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CarClientDriver.isDrivingKey(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 1.21 の {@code Screen#render} は先に背景（暗幕とぼかし）を描く。一人称の見え方を見ながら
     * 動かす画面なので描かない。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft == null || minecraft.level == null) {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class Slider extends AbstractSliderButton {
        private final Row row;

        Slider(int x, int y, int width, int height, Row row) {
            super(x, y, width, height, Component.empty(), 0.0);
            this.row = row;
            sync();
        }

        void sync() {
            value = Mth.clamp((get(row) - row.min()) / (row.max() - row.min()), 0.0, 1.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (row.group() == Group.DESIGN) {
                setMessage(Component.literal(designText()));
                return;
            }
            if (row.axis() != TILT) {
                setMessage(Component.literal(String.format(Locale.ROOT, "%.3f m", get(row))));
                return;
            }
            // 足し分だけでは何度になったか分からないので、コラムの角度（水平から）も出す
            double column = ObjModel.get(CarModel.get(carId).bodyModel()).steeringColumnAngle();
            String text = String.format(Locale.ROOT, "%+.1f°", get(row));
            if (!Double.isNaN(column)) {
                text += String.format(Locale.ROOT, "  (%.1f°)", column + get(row));
            }
            setMessage(Component.literal(text));
        }

        /** 値だけでは何倍に描かれるのか分からないので、拡大率も添える（小さい値ほど大きく描かれる）。 */
        private String designText() {
            double v = get(row);
            String text = String.format(Locale.ROOT, "%.3f m", v);
            if (row.axis() == WHEEL_BASE) {
                text += String.format(Locale.ROOT, "  (×%.3f)", CarSpec.DEFAULT.wheelBase() / v);
            } else if (row.axis() == WHEEL_RADIUS) {
                CarEntity car = ridingCar();
                double radius = car != null ? car.getSpec().wheelRadius() : CarSpec.DEFAULT.wheelRadius();
                text += String.format(Locale.ROOT, "  (×%.3f)", radius / v);
            }
            return text;
        }

        @Override
        protected void applyValue() {
            // スライダーは行の刻み（1cm・0.5 度）。細かくはボタンで
            double raw = row.min() + value * (row.max() - row.min());
            set(row, Math.round(raw / row.step()) * row.step());
        }
    }
}
