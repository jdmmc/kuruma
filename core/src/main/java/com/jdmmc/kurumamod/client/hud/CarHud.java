package com.jdmmc.kurumamod.client.hud;

import com.jdmmc.kurumamod.client.ui.ScreenStyle;
import com.jdmmc.kurumamod.client.ui.UiFont;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.config.CameraMode;
import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.physics.CarPhysics;
import com.jdmmc.kurumamod.physics.Wheel;
import com.jdmmc.kurumamod.surface.RoadSurface;
import com.jdmmc.kurumamod.client.CarClient;
import com.jdmmc.kurumamod.client.race.RaceClient;

/**
 * 運転中のテレメトリ表示。<b>セッティングを詰めるための数字</b>で、H キーで切り替える。
 *
 * <p>調整画面で 43 項目もいじれるのに、効果を確かめる数字が画面に何も出ていない状態を
 * 埋めるためのもの。空転・ロック・スリップ角は特に、見えないと分からない。</p>
 *
 * <p>出すかどうかは H キーのメニュー（{@code KurumaMenuScreen}）で切り替える。
 * <b>以前は H を押すとその場で切り替わっていた</b>が、画面の好みは 1 か所に集めた。</p>
 *
 * <p><b>速度計と回転計は {@link CarGaugeHud} が持つ。</b>あちらは運転するための計器で、
 * 針の角度で「あとどれだけ残っているか」を見せる。数字で読みたいものと、視界の端で
 * 分かればいいものは別なので、消し方も別にしてある（こちらは H、あちらは設定）。</p>
 */
public final class CarHud {

    private static final int MARGIN = 6;
    private static final int PANEL_WIDTH = 116;
    private static final int LINE_HEIGHT = 10;

    /** 車を上から見た図の大きさ */
    private static final int DIAGRAM_WIDTH = 32;
    private static final int DIAGRAM_HEIGHT = 34;
    private static final int WHEEL_WIDTH = 8;
    private static final int WHEEL_HEIGHT = 13;

    private static final int COLOR_BACKGROUND = 0x90000000;
    private static final int COLOR_LABEL = 0xFFA0A0A0;
    private static final int COLOR_GRIP = 0xFF4CAF50;    // 掴んでいる
    private static final int COLOR_SLIDING = 0xFFFFC107; // 滑り始め
    private static final int COLOR_LOST = 0xFFF44336;    // 空転・ロック
    private static final int COLOR_AIRBORNE = 0xFF37474F; // 接地していない

    /** これを超える滑り率で「滑っている」、さらに超えると「失っている」とみなす */
    private static final double SLIP_WARN = 0.10;
    private static final double SLIP_LOST = 0.35;

    /** これ以上濡れていたら「濡れ」と表示する。 */
    private static final double WET_SHOWN = 0.05;

    private CarHud() {
    }

    /** HUD をすべて描き終えた後に呼ぶ（版の {@code ClientHooks#renderGuiPost}）。 */
    public static void onRenderGui(UiGraphics graphics) {
        if (!ClientConfig.showTelemetry) {
            return;
        }
        // 換装・セッティング画面が開いている間は引っ込める（メーターと同じ理由）
        if (ScreenStyle.isCarScreen()) {
            return;
        }
        RaceClient client = RaceClient.get();
        // 運転者に限らない。速度も回転数も路面も同期されているので同乗者にも出せるし、
        // カメラの状態は同乗者も切り替えられる（H で消せるのも同じ）。
        // 荷重の棒だけは手元で解いていないと停車時の値で止まるが、色は同期された
        // 車輪の角速度から出るので滑りは見える
        HudCar car = CarClient.get().ridingHud();
        if (client.hideGui() || car == null) {
            return;
        }
        renderTelemetry(graphics, client.font(), car, client.guiWidth(), client.guiHeight());
    }

    /** 左下。テレメトリの数字と、その上にタイヤの図。 */
    private static void renderTelemetry(UiGraphics graphics, UiFont font, HudCar car,
                                        int screenWidth, int screenHeight) {
        // 画面の左下の角に固定する。メーターと重なってもずらさない（2026-11-10、ユーザーの指示）。
        // ホットバーとだけは重ねない（横に重なるときは、ホットバーの上まで持ち上げる）
        int left = MARGIN;
        int bottom = screenHeight - MARGIN - HotbarClearance.lift(left, left + PANEL_WIDTH, screenWidth);
        int panelHeight = LINE_HEIGHT * 6 + 8;
        int top = bottom - panelHeight;
        graphics.fill(left, top, left + PANEL_WIDTH, bottom, COLOR_BACKGROUND);

        int y = top + 4;
        double lateralG = car.getRenderLateralAcceleration() / CarPhysics.GRAVITY;
        graphics.drawString(font,
                RaceText.translatable("hud.kurumamod.lateral_g", String.format("%.2f", Math.abs(lateralG))),
                left + 5, y, COLOR_LABEL, false);
        y += LINE_HEIGHT;

        // 空気抵抗。力と、それが車重に対して何 G の減速になるか。
        // 速度も諸元も同期されているので同乗者にも正しく出る
        double speed = car.getRenderSpeed();
        double drag = car.getSpec().dragForce(speed);
        graphics.drawString(font,
                RaceText.translatable("hud.kurumamod.drag",
                        String.format("%.0f", drag),
                        String.format("%.3f", drag / car.getSpec().mass() / CarPhysics.GRAVITY)),
                left + 5, y, COLOR_LABEL, false);
        y += LINE_HEIGHT;

        // ダウンフォース。前後それぞれ何 kg ぶん押し付けているか。物理と同じく前後方向の速度で見る
        graphics.drawString(font,
                RaceText.translatable("hud.kurumamod.downforce",
                        String.format("%.0f", car.getSpec().downforce(true, speed) / CarPhysics.GRAVITY),
                        String.format("%.0f", car.getSpec().downforce(false, speed) / CarPhysics.GRAVITY)),
                left + 5, y, COLOR_LABEL, false);
        y += LINE_HEIGHT;

        double slip = car.getRenderSlipAngleDegrees();
        graphics.drawString(font,
                RaceText.translatable("hud.kurumamod.drift", String.format("%+.1f", slip)),
                left + 5, y, slipColor(Math.abs(slip)), false);
        y += LINE_HEIGHT;

        // 路面。タグの設定が効いているかを目で確かめられる。
        // 4 輪で違う路面に乗っていることもあるので、いちばんグリップの低い輪を出す
        RoadSurface worst = car.getRenderSurface();
        double wetness = car.getRenderWetness();
        RaceText surfaceText = wetness > WET_SHOWN
                ? RaceText.translatable("hud.kurumamod.surface_wet",
                        RaceText.translatable(worst.translationKey()),
                        String.format("%.2f", worst.gripScale(wetness)))
                : RaceText.translatable("hud.kurumamod.surface",
                        RaceText.translatable(worst.translationKey()),
                        String.format("%.2f", worst.gripScale()));
        graphics.drawString(font, surfaceText, left + 5, y,
                worst.gripScale() < 1.0 || wetness > WET_SHOWN ? COLOR_SLIDING : COLOR_LABEL, false);
        y += LINE_HEIGHT;

        // カメラの状態と切り替えキー。キーは変更できるので、割り当てを実際に引いて出す
        CameraMode cameraMode = CarClient.get().cameraMode();
        graphics.drawString(font,
                RaceText.translatable("hud.kurumamod.camera",
                        RaceText.translatable(cameraMode.translationKey()),
                        RaceText.literal(CarClient.get().cameraKeyName())),
                left + 5, y, !cameraMode.followsView() && cameraMode != CameraMode.HEADING ? COLOR_SLIDING : COLOR_LABEL, false);

        // タイヤの図はテレメトリの上
        int diagramTop = top - 4 - DIAGRAM_HEIGHT - 8;
        graphics.fill(left, diagramTop, left + DIAGRAM_WIDTH + 8, top - 4, COLOR_BACKGROUND);
        renderWheelDiagram(graphics, car, left + 4, diagramTop + 4);
    }

    /** 上から見た 4 輪の図。棒の高さが接地荷重、色が滑り具合。 */
    private static void renderWheelDiagram(UiGraphics graphics, HudCar car, int left, int top) {
        int gapX = DIAGRAM_WIDTH - WHEEL_WIDTH * 2;
        int gapY = DIAGRAM_HEIGHT - WHEEL_HEIGHT * 2;

        for (Wheel wheel : Wheel.VALUES) {
            int x = left + (wheel.isLeft() ? 0 : WHEEL_WIDTH + gapX);
            int y = top + (wheel.isFront() ? 0 : WHEEL_HEIGHT + gapY);
            renderWheel(graphics, car, wheel, x, y);
        }
    }

    private static void renderWheel(UiGraphics graphics, HudCar car, Wheel wheel, int x, int y) {
        double load = car.getRenderWheelLoad(wheel);
        double staticLoad = car.getSpec().staticWheelLoad(wheel);
        // 停車時の 2 倍で満杯になる目盛り
        double fill = Math.min(1.0, load / (staticLoad * 2.0));

        graphics.fill(x, y, x + WHEEL_WIDTH, y + WHEEL_HEIGHT, 0x60000000);
        int filled = (int) Math.round(WHEEL_HEIGHT * fill);
        if (filled > 0) {
            int color = load <= 0.0 ? COLOR_AIRBORNE : slipColor(Math.abs(car.getRenderSlipRatio(wheel)) * 100.0);
            graphics.fill(x, y + WHEEL_HEIGHT - filled, x + WHEEL_WIDTH, y + WHEEL_HEIGHT, color);
        }
        // 停車時の荷重の位置に目印を置くと、荷重移動の量が読み取れる
        int markY = y + WHEEL_HEIGHT - (int) Math.round(WHEEL_HEIGHT * 0.5);
        graphics.fill(x, markY, x + WHEEL_WIDTH, markY + 1, 0x60FFFFFF);
    }

    /**
     * 滑り具合の色。
     *
     * @param magnitude 滑り率なら百分率、ドリフト角なら度。どちらも「10 で注意、35 で限界」の目安に揃えてある
     */
    private static int slipColor(double magnitude) {
        if (magnitude >= SLIP_LOST * 100.0) {
            return COLOR_LOST;
        }
        if (magnitude >= SLIP_WARN * 100.0) {
            return COLOR_SLIDING;
        }
        return COLOR_GRIP;
    }
}
