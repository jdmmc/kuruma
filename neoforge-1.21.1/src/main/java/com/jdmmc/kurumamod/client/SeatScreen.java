package com.jdmmc.kurumamod.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * 座席とハンドルの位置を合わせるデバッグ画面。Ctrl+G（{@link KurumaKeys#OPEN_SEAT_DEBUG}）で開く。
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
 */
public final class SeatScreen extends Screen {

    /**
     * 1 行ぶん。何を動かすか（{@code steering} が false なら座席、true ならハンドル）、
     * どの値か（0: X 右、1: Y 上、2: Z 後ろ [m]、{@link #TILT}: ハンドルの傾き [度]）、
     * 動かせる範囲と、−／＋ 1 回の量（Shift でその 10 倍。スライダーもこの刻み）。
     */
    private record Row(boolean steering, String key, int axis, double min, double max, double step) {
    }

    /** {@link Row#axis} のうち、ハンドルの傾き [度]。 */
    private static final int TILT = 3;

    /**
     * ハンドルは座席から見て位置を決めるので、同じ画面に並べる。範囲が狭いのは、
     * メッシュの位置からの<b>ずれ</b>だから（座席は車の原点からの位置）。
     */
    private static final Row[] ROWS = {
            new Row(false, "x", 0, -1.5, 1.5, 0.01),
            new Row(false, "y", 1, -1.5, 1.5, 0.01),
            new Row(false, "z", 2, -2.5, 2.5, 0.01),
            new Row(true, "x", 0, -0.5, 0.5, 0.01),
            new Row(true, "y", 1, -0.5, 0.5, 0.01),
            new Row(true, "z", 2, -0.5, 0.5, 0.01),
            new Row(true, "tilt", TILT, -30.0, 30.0, 0.5),
    };

    // 1080p の GUI スケール自動（高さ 270）に収まるよう詰めてある。行を足すならここを見直す
    private static final int ROW_HEIGHT = 21;
    private static final int TOP = 34;
    private static final int LABEL_WIDTH = 84;
    private static final int SLIDER_WIDTH = 200;
    private static final int BUTTON_WIDTH = 20;
    private static final int ROW_WIDTH = LABEL_WIDTH + BUTTON_WIDTH + 4 + SLIDER_WIDTH + 4 + BUTTON_WIDTH + 4 + BUTTON_WIDTH;

    private final ResourceLocation carId;
    private final Slider[] sliders = new Slider[ROWS.length];
    /** 「コピーした」を出しておく残りのフレーム数。 */
    private int copiedFrames;

    public SeatScreen(ResourceLocation carId) {
        super(Component.translatable("screen.kurumamod.seat.title"));
        this.carId = carId;
    }

    private CarModel.Vec3 current(Row row) {
        return row.steering() ? CarSeat.currentSteering(carId) : CarSeat.current(carId);
    }

    private static double component(CarModel.Vec3 v, Row row) {
        return row.axis() == 0 ? v.x() : row.axis() == 1 ? v.y() : v.z();
    }

    private double get(Row row) {
        return row.axis() == TILT ? CarSeat.currentSteeringTilt(carId) : component(current(row), row);
    }

    private double original(Row row) {
        CarModel model = CarModel.get(carId);
        if (row.axis() == TILT) {
            return model.steeringTilt();
        }
        return component(row.steering() ? model.steeringOffset() : model.seatOffset(), row);
    }

    private void set(Row row, double value) {
        // 0.1mm（傾きは 0.0001 度）単位に丸める（浮動小数の端数が JSON へ出ないよう）
        double v = Math.round(Mth.clamp(value, row.min(), row.max()) * 10000.0) / 10000.0;
        if (row.axis() == TILT) {
            CarSeat.setSteeringTiltOverride(carId, v);
            return;
        }
        CarModel.Vec3 now = current(row);
        CarModel.Vec3 next = new CarModel.Vec3(
                row.axis() == 0 ? v : now.x(),
                row.axis() == 1 ? v : now.y(),
                row.axis() == 2 ? v : now.z());
        if (row.steering()) {
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
        int x = left() + LABEL_WIDTH;
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            int y = rowY(i);
            Slider slider = new Slider(x + BUTTON_WIDTH + 4, y, SLIDER_WIDTH, 20, row);
            sliders[i] = slider;
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
        int bottom = rowY(ROWS.length + 2) + 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.copy"), button -> {
                    if (minecraft != null) {
                        minecraft.keyboardHandler.setClipboard(json());
                        copiedFrames = 60;
                    }
                })
                .bounds(width / 2 - 154, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.reset_all"), button -> {
                    CarSeat.clearOverride(carId);
                    CarSeat.clearSteeringOverride(carId);
                    for (Slider slider : sliders) {
                        slider.sync();
                    }
                })
                .bounds(width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 54, bottom, 100, 20).build());
    }

    private void nudge(Row row, Slider slider, int direction) {
        set(row, get(row) + direction * row.step() * (hasShiftDown() ? 10.0 : 1.0));
        slider.sync();
    }

    /** カーパックの {@code vehicles/<車>.json} にそのまま貼れる行（座席とハンドル）。 */
    private String json() {
        return seatJson() + ",\n" + steeringJson();
    }

    private String seatJson() {
        return "\"seat\": " + array(CarSeat.current(carId));
    }

    private String steeringJson() {
        return "\"steering\": { \"offset\": " + array(CarSeat.currentSteering(carId))
                + ", \"tilt\": " + number(CarSeat.currentSteeringTilt(carId)) + " }";
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
        int left = left();
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            // JSON の値から動かした項目は黄色（調整画面と同じ）
            boolean changed = Math.abs(get(row) - original(row)) > 1.0e-6;
            Component label = Component.translatable(row.steering()
                            ? "screen.kurumamod.seat.group.steering" : "screen.kurumamod.seat.group.seat")
                    .append(" ").append(Component.translatable("screen.kurumamod.seat." + row.key()));
            graphics.drawString(font, label, left, rowY(i) + 6, changed ? 0xFFFF55 : 0xFFFFFF);
        }
        graphics.drawCenteredString(font, Component.literal(seatJson()), width / 2, rowY(ROWS.length) + 6, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(steeringJson()), width / 2, rowY(ROWS.length + 1) + 2, 0xFFFFFF);
        // 「コピーした」は説明の行に重ねて出す（縦に余裕が無い）
        if (copiedFrames > 0) {
            copiedFrames--;
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.copied"),
                    width / 2, rowY(ROWS.length + 2) + 26, 0x55FF55);
        } else {
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.hint"),
                    width / 2, rowY(ROWS.length + 2) + 26, 0xAAAAAA);
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

        @Override
        protected void applyValue() {
            // スライダーは行の刻み（1cm・0.5 度）。細かくはボタンで
            double raw = row.min() + value * (row.max() - row.min());
            set(row, Math.round(raw / row.step()) * row.step());
        }
    }
}
