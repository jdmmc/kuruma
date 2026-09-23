package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import com.jdmmc.kurumamod.GaugeTheme;
import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 90 年代の国産スポーツの純正メーターに寄せた 2 眼の計器。
 * <b>左に回転計、右に速度計、間にシフトインジケーターとデジタル表示。</b>
 *
 * <p>数字だけの {@link CarHud} とは役目が違う。<b>あちらはセッティングを詰めるための計器で、
 * こちらは運転するための計器。</b>針は「あとどれだけ残っているか」を角度で見せるので、
 * 数字を読まずに視界の端で分かる。H でまとめて消えるのはテレメトリだけで、
 * こちらは設定（{@link ClientConfig#showGauges}）で切る。</p>
 *
 * <p><b>回転計が左、速度計が右。</b>実車の作法で、マニュアルで走るときに<b>回転計が画面の
 * 中央寄り</b>——つまり視線の近くに来る。段を自分で選ぶ以上、見る回数が多いのは回転計のほう。</p>
 *
 * <p><b>文字盤は毎フレーム諸元から組み立てる。</b>最高速もレブリミットもセッティングと
 * カーパックで変わるので、目盛りの上限を画像に焼き込むことができない。</p>
 *
 * <h2>色</h2>
 *
 * <p>色は {@link GaugeTheme} が持ち、設定で切り替える。<b>形は共通で色だけが変わる</b>ので、
 * ここには色の定数を書かない——書くと、片方のテーマだけ直したつもりが両方に効いたり、
 * 逆に片方が取り残されたりする。</p>
 *
 * <h2>線の太さ</h2>
 *
 * <p>リング・目盛り・針の太さは {@code LINE_*} にまとめてある（<b>細</b>＝0.6〜0.85 倍）。
 * <b>太さは GUI スケールで実際の画素数が変わる</b>ことに注意。1 GUI ピクセルはスケール 4 なら
 * 4 画素、スケール 1 なら 1 画素で、{@link GaugePainter} は四角形を積むだけで画素に
 * 吸着させていないので、これより細くすると小さい画面では滲んで消える。</p>
 *
 * <h2>置き場所</h2>
 *
 * <p>メーターは<b>ホットバーの左右</b>に置く。画面の下端はバニラのホットバー（182px）と
 * その上の体力・空腹バーが使っているので、そこを避けると自然に左右へ寄る——結果として
 * 実車のメーターパネルに近い並びになる。中央のデジタル表示だけは、ホットバーより上へ逃がす。</p>
 *
 * <p><b>大きさは画面の空きから決める。</b>GUI スケールを上げている人ほど画面が狭くなり、
 * 固定の大きさではホットバーや左下のテレメトリと重なる。ホットバーの左右に残っている幅に
 * 収まるところまで縮め、{@link ClientConfig#gaugeScale} で好みの倍率を掛ける。</p>
 */
@Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
public final class CarGaugeHud {

    /** メーターの外周半径（倍率 1.0 のとき）。 */
    private static final float RADIUS = 44.0F;
    /** 画面の下端との隙間。 */
    private static final float BOTTOM_MARGIN = 6.0F;
    /** バニラのホットバーの幅の半分。この外側にメーターを置く。 */
    private static final float HOTBAR_HALF_WIDTH = 91.0F;
    /** ホットバーとメーターの間隔。 */
    private static final float HOTBAR_GAP = 8.0F;
    /** 縮める下限。これより小さいと目盛りの数字が読めない。 */
    private static final float MIN_SCALE = 0.5F;
    /**
     * 倍率をこの刻みに丸める。<b>半端な倍率だとフォントが滲んで小さい文字が読めなくなる。</b>
     *
     * <p>Minecraft のフォントは 1px 単位で作られているので、0.78 倍のような端数では
     * 単位の「km/h」が潰れる（実効 427x240 の画面で実際に読めなくなった）。</p>
     */
    private static final float SCALE_STEP = 0.25F;
    /**
     * メーターの直径が画面の高さのこれを超えないようにする。前が見えなくなるため。
     *
     * <p>直径 88px は実効 480x270 の 32.6% にあたるので、ここを 0.30 にすると
     * <b>いちばん普通の画面で 0.75 倍まで縮んでしまう</b>（0.25 刻みへ切り捨てるため）。</p>
     */
    private static final float MAX_HEIGHT_FRACTION = 0.34F;

    /** 文字盤の開始角と回る量 [度]。160 度から時計回りに 220 度。<b>空くのは真下</b>。 */
    private static final float ANGLE_START = 160.0F;
    private static final float ANGLE_SWEEP = 220.0F;

    // 線の太さ（1.0 がカタログの「いま」、ここは「細」）
    private static final float LINE_RING = 0.6F;
    private static final float LINE_TICK_MAJOR = 0.75F;
    private static final float LINE_TICK_MINOR = 0.7F;
    private static final float LINE_NEEDLE = 0.75F;
    private static final float LINE_HUB = 0.85F;

    // 文字盤の各部の半径（RADIUS を 44 としたときの値）
    private static final float BEZEL_WIDTH = 5.0F * LINE_RING;
    private static final float GLASS_RADIUS = RADIUS - BEZEL_WIDTH;
    /** レッドゾーンの弧。<b>外周のリングではなく文字盤の内側</b>に置くのが当時の作法。 */
    private static final float RED_OUTER = RADIUS - 8.5F;
    private static final float RED_WIDTH = 3.5F * LINE_RING;
    private static final float TICK_OUTER = RADIUS - 8.5F;
    private static final float TICK_MAJOR_INNER = RADIUS - 15.0F;
    private static final float TICK_MINOR_INNER = RADIUS - 12.0F;
    /** 目盛りの数字を置く半径。<b>内側へ寄せすぎると針の軸と単位の文字にぶつかる。</b> */
    private static final float LABEL_RADIUS = RADIUS - 22.5F;
    private static final float NEEDLE_OUTER = RADIUS - 11.0F;
    private static final float NEEDLE_INNER = -10.0F;
    private static final float HUB_RADIUS = 5.5F * LINE_HUB;

    /** 単位の文字と、その下に置く小計器の位置（中心からの下向き）。 */
    private static final float UNIT_DY = 12.0F;
    private static final float SUBGAUGE_TOP = 19.0F;
    /**
     * 警告灯の文字の大きさ。<b>ここだけ {@link #SMALL_TEXT_SCALE} より小さい。</b>
     *
     * <p>0.75 では灯が目立ちすぎた。0.5 にしてあるのは、GUI スケールが偶数のとき
     * フォントの 1px がちょうど画面の 1 画素に乗って滲まないから（0.6 のような端数は滲む）。</p>
     */
    private static final float LAMP_TEXT_SCALE = 0.5F;
    /** 警告灯の高さと、文字の左右の余白・灯どうしの間隔。 */
    private static final float LAMP_HEIGHT = 5.0F;
    private static final float LAMP_PADDING = 1.0F;
    private static final float LAMP_GAP = 1.0F;

    /** 目盛りの数字の大きさ。1.0 のままだと 3 桁が文字盤をふさぐ。 */
    private static final float LABEL_TEXT_SCALE = 0.75F;
    /** 単位や AT/MT のような添え字の大きさ。<b>これ以上小さくすると潰れる。</b> */
    private static final float SMALL_TEXT_SCALE = 0.75F;
    /**
     * 速度計で数字を出す区切りの数の上限。
     *
     * <p>220 度をこれ以上割ると、半径 21.5 のところで隣との間隔が 3 桁の幅（13.5px）を
     * 下回って<b>重なる</b>。5 分割なら 44 度＝16.5px あるので収まる。</p>
     *
     * <p>回転計は 1 桁（4.5px）しか出さないので、この制限は要らない。</p>
     */
    private static final int MAX_SPEED_STEPS = 5;

    // 中央のデジタル表示
    private static final float PANEL_WIDTH = 84.0F;
    private static final float PANEL_HEIGHT = 32.0F;
    /**
     * 小窓の下端をここより下へ置かない（画面の下端から数えた位置）。
     *
     * <p><b>バニラの体力バーは画面下端から 39px のところが上端</b>（Forge の
     * {@code leftHeight} が 39 から始まり、バーが 1 段増えるごとに 10 ずつ積み上がる）。
     * <b>防具を着ていると、その 1 段上（下端から 49px）にもう 1 列出る</b>ので、
     * そこに 2px の隙間を足した 51 にしてある。</p>
     *
     * <p>体力バーだけを避けるなら 42 まで下げられるが、<b>防具を着た瞬間に重なる</b>。
     * バーの増減で小窓が動くのは避けたいので、<b>いちばん出っ張る場合に合わせて
     * 固定してある</b>。</p>
     *
     * <p>メーターの中心から決めるだけだと、<b>倍率が小さいときに小窓が下がって
     * 体力バーを覆う</b>（実効 427x240 で実際に重なった）。画面の下端から数えた位置で
     * 頭打ちにすれば、どの倍率でも重ならない。</p>
     */
    private static final float HEARTS_ROW_TOP = 51.0F;
    /** シフトインジケーターの LED の数。 */
    private static final int SHIFT_LIGHTS = 21;
    /** LED の並びが描く弧の半径。大きいほど平らになる。 */
    private static final float SHIFT_ARC_RADIUS = 340.0F;
    /** LED を並べる角度の広がり [度]。 */
    private static final float SHIFT_SPREAD_DEGREES = 10.0F;
    /** LED が点きはじめる回転数の割合。ここから下は 1 つも点かない。 */
    private static final double SHIFT_LIGHT_FROM = 0.45;

    /**
     * メーターを描く高さ（Z）。<b>チャットより手前に出すために持ち上げる。</b>
     *
     * <p><b>描く順番だけでは手前にならない。</b>{@code RenderGuiEvent.Post} は Forge の
     * オーバーレイ（チャットを含む）をすべて描いた<b>後</b>に飛ぶ——ここまではバイトコードで
     * 確認したとおり——が、<b>チャットは {@code pose().translate(0, 0, 50)} で Z を上げて
     * 描かれている</b>ので、Z=0 のまま描くと深度テストで負けて<b>チャットの下に潜る</b>。</p>
     *
     * <p>バニラが使っている高さは、チャット 50・アイテム 100〜150・ツールチップ 400。
     * その間の 200 に置けば、チャットより手前で、ツールチップより奥になる。</p>
     */
    private static final float HUD_LAYER_Z = 200.0F;

    /** レブリミットのこの割合を超えたら針と LED を白く（警告色に）する。 */
    private static final double REDLINE_WARN = 0.95;
    private static final int COLOR_NEEDLE_WARN = 0xFFFFFFFF;

    /** 最後に計算した占有高さと右端。{@link CarHelp} が右下の置き場所を決めるのに使う。 */
    private static int reservedHeight;
    private static int leftEdge;
    private static int rightEdge;

    private CarGaugeHud() {
    }

    /**
     * メーターが画面下端から使っている高さ。{@link CarHelp} がその上に並べる。
     *
     * <p>実際に描いたときの値を覚えておく——大きさが画面とスケールで変わるので、
     * 定数から計算し直すと食い違う。</p>
     */
    static int reservedBottomHeight() {
        return ClientConfig.showGauges ? reservedHeight : 0;
    }

    /**
     * 右のメーターの右端。<b>{@link CarHelp} はここに重なるときだけ上へ逃げる。</b>
     *
     * <p>画面が広ければヘルプはメーターの横に並べられるので、常に上へ押しやると
     * 何もない場所に浮くことになる。</p>
     */
    static int gaugeRightEdge() {
        return ClientConfig.showGauges ? rightEdge : 0;
    }

    /**
     * 左のメーターの左端。<b>{@link CarHud} のテレメトリはここに重なるときだけ上へ逃げる。</b>
     *
     * <p>出していないときは十分に大きい値を返す。呼ぶ側に「出しているか」を
     * 判定させると、条件の書き方が 2 か所でずれる。</p>
     */
    static int gaugeLeftEdge() {
        return ClientConfig.showGauges ? leftEdge : Integer.MAX_VALUE;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientConfig.showGauges) {
            clearBounds();
            return;
        }
        // 換装・セッティング画面が開いている間は引っ込める。どちらも下端を使うので
        // 重なるし、開いているあいだは運転の入力も止まっている
        if (ScreenStyle.isCarScreen()) {
            clearBounds();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 同乗者にも出す。速度も回転数も段も同期されているので描ける
        if (minecraft.player == null || minecraft.options.hideGui
                || !(minecraft.player.getVehicle() instanceof CarEntity car)) {
            clearBounds();
            return;
        }
        render(event.getGuiGraphics(), minecraft, car);
    }

    /** 出していないことを、置き場所を聞いてくる側へ伝える。 */
    private static void clearBounds() {
        reservedHeight = 0;
        leftEdge = Integer.MAX_VALUE;
        rightEdge = 0;
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft, CarEntity car) {
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        // ホットバーの外側に残っている幅と、画面の高さの両方に収まるまで縮める。
        // 幅だけで決めると、GUI スケールを上げた人の画面で前が見えなくなる
        float available = (screenWidth - HOTBAR_HALF_WIDTH * 2.0F) / 2.0F - HOTBAR_GAP * 2.0F;
        float fit = Math.min(available, screenHeight * MAX_HEIGHT_FRACTION) / (RADIUS * 2.0F);
        // 切り上げると空きを超えるので、刻みへは必ず切り捨てで丸める
        float base = Math.max(MIN_SCALE,
                (float) Math.floor(Math.min(1.0F, fit) / SCALE_STEP) * SCALE_STEP);
        float scale = base * (float) ClientConfig.gaugeScale;

        float radius = RADIUS * scale;
        float centerY = screenHeight - BOTTOM_MARGIN - radius;
        float leftX = screenWidth * 0.5F - HOTBAR_HALF_WIDTH - HOTBAR_GAP - radius;
        float rightX = screenWidth * 0.5F + HOTBAR_HALF_WIDTH + HOTBAR_GAP + radius;
        reservedHeight = (int) Math.ceil(radius * 2.0F + BOTTOM_MARGIN);
        leftEdge = (int) Math.floor(leftX - radius);
        rightEdge = (int) Math.ceil(rightX + radius);

        Font font = minecraft.font;
        CarSpec spec = car.getSpec();
        GaugeTheme theme = ClientConfig.gaugeTheme;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, HUD_LAYER_Z);
        renderTachometer(graphics, font, theme, car, spec, leftX, centerY, scale);
        renderSpeedometer(graphics, font, theme, car, spec, rightX, centerY, scale);
        // 小窓は下げられるだけ下げる。2 眼の間で高い位置にあると浮いて見えるので、
        // 体力バーの手前まで落とす（メーターの中心より下がることになる）
        float panelBottom = screenHeight - HEARTS_ROW_TOP;
        renderCenterPanel(graphics, font, theme, car, spec, screenWidth * 0.5F,
                panelBottom, scale);
        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 回転計（左）
    // ------------------------------------------------------------------

    private static void renderTachometer(GuiGraphics graphics, Font font, GaugeTheme theme,
                                         CarEntity car, CarSpec spec,
                                         float x, float y, float scale) {
        double redline = spec.redlineRpm();
        // レブリミットの少し上まで目盛りを伸ばす。実車と同じで、限界が文字盤の端に来ない
        double top = Math.ceil((redline + 500.0) / 1000.0) * 1000.0;
        double rpm = car.getRenderRpm();
        boolean warn = rpm >= redline * REDLINE_WARN;
        // レブリミットが高い車では 1000rpm ごとに数字を出すと詰まる（13000rpm で 14 個）
        double major = top > 9000.0 ? 2.0 : 1.0;

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);

        plate(graphics, theme);
        // レッドゾーンは文字盤の内側の弧。その範囲の数字も赤くする
        GaugePainter.arc(graphics, 0.0F, 0.0F, RED_OUTER - RED_WIDTH, RED_OUTER,
                angleFor(redline / top), angleFor(1.0), theme.redline);
        ticks(graphics, theme, top / 1000.0, major, major / 2.0, redline / top);
        aidLamps(graphics, font, theme, car, false);
        needle(graphics, theme, rpm / top, warn);

        GaugePainter.flush(graphics);

        labels(graphics, font, theme, top / 1000.0, major, redline / top);
        aidLamps(graphics, font, theme, car, true);
        centerText(graphics, font, Component.translatable("hud.kurumamod.rpm_scale"),
                UNIT_DY, theme.unit);

        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 速度計（右）
    // ------------------------------------------------------------------

    private static void renderSpeedometer(GuiGraphics graphics, Font font, GaugeTheme theme,
                                          CarEntity car, CarSpec spec,
                                          float x, float y, float scale) {
        double maxKmh = spec.maxSpeed() * 3.6;
        // 20km/h 刻みから始めて、数字が重ならないところまで粗くする。
        // 最高速はセッティングとカーパックで変わるので、決め打ちにはできない
        int major = 20;
        while (maxKmh / major > MAX_SPEED_STEPS) {
            major += 20;
        }
        double top = Math.ceil(maxKmh / major) * major;
        double speed = Math.abs(car.getRenderSpeed()) * 3.6;

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);

        plate(graphics, theme);
        ticks(graphics, theme, top, major, major / 2.0, Double.MAX_VALUE);
        // アクセルとブレーキの踏み量。実車の燃料計の位置
        pedalBars(graphics, theme, car);
        needle(graphics, theme, speed / top, false);

        GaugePainter.flush(graphics);

        labels(graphics, font, theme, top, major, Double.MAX_VALUE);
        centerText(graphics, font, Component.translatable("hud.kurumamod.kmh"),
                UNIT_DY, theme.unit);

        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 文字盤の部品（原点はメーターの中心、半径は倍率 1.0 の値）
    // ------------------------------------------------------------------

    /**
     * 文字盤の地。縁（ベゼル）とガラスの照り。
     *
     * <p><b>照りは同心の円を重ねて作る。</b>グラデーションを塗る手段が無いので、
     * 中心寄りの明るい色を薄く 2 枚重ねて近似している。枚数を増やせば滑らかになるが、
     * この大きさでは 2 枚で足りる。</p>
     */
    private static void plate(GuiGraphics graphics, GaugeTheme theme) {
        GaugePainter.arc(graphics, 0.0F, 0.0F, 0.0F, RADIUS, 0.0F, 360.0F, backdrop(theme.face));
        GaugePainter.arc(graphics, 0.0F, 0.0F, GLASS_RADIUS, RADIUS, 0.0F, 360.0F,
                backdrop(theme.bezel));
        GaugePainter.arc(graphics, 0.0F, 0.0F, 0.0F, GLASS_RADIUS, 0.0F, 360.0F,
                backdrop(theme.glassOuter));
        GaugePainter.arc(graphics, 0.0F, 0.0F, 0.0F, GLASS_RADIUS * 0.66F, 0.0F, 360.0F,
                backdrop(theme.glassInner, 0.45F));
        GaugePainter.arc(graphics, 0.0F, 0.0F, 0.0F, GLASS_RADIUS * 0.36F, 0.0F, 360.0F,
                backdrop(theme.glassInner, 0.45F));
        // 縁の内側に入る細い金属光沢
        GaugePainter.arc(graphics, 0.0F, 0.0F, GLASS_RADIUS, GLASS_RADIUS + 0.9F * LINE_RING,
                0.0F, 360.0F, backdrop(theme.bezelRing));
    }

    /**
     * 目盛りの線。
     *
     * @param top     文字盤の上限（表示する単位のまま）
     * @param major   数字を出す間隔
     * @param minor   細い線の間隔
     * @param redFrom この割合から先は赤くする。無ければ {@link Double#MAX_VALUE}
     */
    private static void ticks(GuiGraphics graphics, GaugeTheme theme,
                              double top, double major, double minor, double redFrom) {
        int count = (int) Math.round(top / minor);
        for (int i = 0; i <= count; i++) {
            double v = minor * i;
            boolean isMajor = Math.abs(v / major - Math.round(v / major)) < 1.0e-6;
            boolean hot = v / top >= redFrom - 1.0e-9;
            GaugePainter.radialBar(graphics, 0.0F, 0.0F,
                    isMajor ? TICK_MAJOR_INNER : TICK_MINOR_INNER, TICK_OUTER,
                    angleFor(v / top),
                    isMajor ? 2.0F * LINE_TICK_MAJOR : 1.0F * LINE_TICK_MINOR,
                    isMajor ? (hot ? theme.redline : theme.tickMajor) : theme.tickMinor);
        }
    }

    /** 目盛りの数字。<b>図形を流したあとに描く。</b> */
    private static void labels(GuiGraphics graphics, Font font, GaugeTheme theme,
                               double top, double major, double redFrom) {
        int count = (int) Math.round(top / major);
        for (int i = 0; i <= count; i++) {
            double v = major * i;
            float angle = angleFor(v / top);
            float lx = (float) Math.cos(Math.toRadians(angle)) * LABEL_RADIUS;
            float ly = (float) Math.sin(Math.toRadians(angle)) * LABEL_RADIUS;
            String text = String.format("%.0f", v);
            int color = v / top >= redFrom - 1.0e-9 ? theme.redline : theme.label;
            graphics.pose().pushPose();
            graphics.pose().translate(lx, ly, 0.0F);
            graphics.pose().scale(LABEL_TEXT_SCALE, LABEL_TEXT_SCALE, 1.0F);
            graphics.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2, color, false);
            graphics.pose().popPose();
        }
    }

    /** 針とその軸。 */
    private static void needle(GuiGraphics graphics, GaugeTheme theme, double fraction, boolean warn) {
        float angle = angleFor(fraction);
        GaugePainter.needle(graphics, 0.0F, 0.0F, NEEDLE_INNER, NEEDLE_OUTER, angle,
                3.0F * LINE_NEEDLE, warn ? COLOR_NEEDLE_WARN : theme.needle);
        GaugePainter.arc(graphics, 0.0F, 0.0F, 0.0F, HUB_RADIUS, 0.0F, 360.0F, theme.hub);
        GaugePainter.arc(graphics, 0.0F, 0.0F, HUB_RADIUS - 0.8F, HUB_RADIUS, 0.0F, 360.0F,
                theme.hubRing);
    }

    /** 文字盤の中央に置く小さな文字。{@code dy} は中心からの上下（正が下）。 */
    private static void centerText(GuiGraphics graphics, Font font, Component text,
                                   float dy, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, dy, 0.0F);
        graphics.pose().scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2, color, false);
        graphics.pose().popPose();
    }

    /**
     * アクセルとブレーキの踏み量。速度計の中、実車の燃料計の位置。
     *
     * <p><b>キーボードでも 0/100 では動かない</b>（変化率に上限がある）ので、
     * 押している長さがそのまま棒の伸びとして見える。コントローラならなおさら。</p>
     *
     * <p>置けるのは<b>文字盤の下の、目盛りが回ってこない範囲</b>。開始角 160 度から
     * 220 度なので、真下（90 度）の周りが空く。</p>
     */
    private static void pedalBars(GuiGraphics graphics, GaugeTheme theme, CarEntity car) {
        float width = 24.0F;
        float height = 2.5F;
        float left = -width / 2.0F;
        pedalBar(graphics, theme, left, SUBGAUGE_TOP, width, height,
                car.getRenderThrottle(), theme.throttle);
        pedalBar(graphics, theme, left, SUBGAUGE_TOP + 4.5F, width, height,
                car.getRenderBrake(), theme.brake);
    }

    private static void pedalBar(GuiGraphics graphics, GaugeTheme theme,
                                 float left, float top, float width, float height,
                                 double value, int color) {
        GaugePainter.rect(graphics, left, top, left + width, top + height, theme.aidOff);
        float filled = (float) Math.max(0.0, Math.min(1.0, value)) * width;
        if (filled > 0.0F) {
            GaugePainter.rect(graphics, left, top, left + filled, top + height, color);
        }
    }

    /**
     * 警告灯。回転計の中、実車の警告灯の位置。上の段に ABS と TCS、下の段にサイドブレーキ。
     *
     * <p><b>灯そのものに名前を書く。</b>色の付いた四角だけでは何の灯なのか分からなかった。
     * 実車の警告灯と同じく、消えているときも文字は薄く見えている——点いたときに初めて
     * 名前が現れるのでは、何が点いたのか読む前に消えてしまう。</p>
     *
     * <p>図形と文字で 2 回呼ぶ（{@code text}）。図形を積んだら文字の前に
     * {@link GaugePainter#flush} が要るため、置き場所の計算を 1 か所にまとめてある。</p>
     */
    private static void aidLamps(GuiGraphics graphics, Font font, GaugeTheme theme,
                                 CarEntity car, boolean text) {
        Component abs = Component.translatable("hud.kurumamod.lamp_abs");
        Component tcs = Component.translatable("hud.kurumamod.lamp_tcs");
        Component handbrake = Component.translatable("hud.kurumamod.lamp_handbrake");
        // 上の段は左右対称にしたいので、幅の広いほうに揃える
        float pairWidth = Math.max(lampWidth(font, abs), lampWidth(font, tcs));
        float offset = pairWidth / 2.0F + LAMP_GAP / 2.0F;
        float secondRow = SUBGAUGE_TOP + LAMP_HEIGHT + LAMP_GAP;
        lamp(graphics, font, theme, abs, -offset, SUBGAUGE_TOP, pairWidth,
                car.isAbsActive(), theme.aidOn, text);
        lamp(graphics, font, theme, tcs, offset, SUBGAUGE_TOP, pairWidth,
                car.isTractionControlActive(), theme.aidOn, text);
        lamp(graphics, font, theme, handbrake, 0.0F, secondRow, lampWidth(font, handbrake),
                car.isHandbrakeOn(), theme.handbrake, text);
    }

    private static float lampWidth(Font font, Component label) {
        return font.width(label) * LAMP_TEXT_SCALE + LAMP_PADDING * 2.0F;
    }

    private static void lamp(GuiGraphics graphics, Font font, GaugeTheme theme, Component label,
                             float centerX, float top, float width, boolean on, int onColor,
                             boolean text) {
        float left = centerX - width / 2.0F;
        if (!text) {
            GaugePainter.rect(graphics, left, top, left + width, top + LAMP_HEIGHT,
                    on ? onColor : theme.aidOff);
            return;
        }
        // 大文字の字面（7px）を灯の高さの真ん中へ
        float glyph = 7.0F * LAMP_TEXT_SCALE;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - font.width(label) * LAMP_TEXT_SCALE / 2.0F,
                top + (LAMP_HEIGHT - glyph) / 2.0F, 0.0F);
        graphics.pose().scale(LAMP_TEXT_SCALE, LAMP_TEXT_SCALE, 1.0F);
        graphics.drawString(font, label, 0, 0, on ? theme.aidLabelOn : theme.aidLabel, false);
        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 中央のデジタル表示
    // ------------------------------------------------------------------

    /**
     * シフトインジケーターとデジタル速度・段。
     *
     * <p>置き場所は<b>2 眼の間、ホットバーの上</b>。下端に置くとホットバーと体力バーに
     * 重なるので、メーターの中心からわずかに下げたところで止めてある。</p>
     */
    private static void renderCenterPanel(GuiGraphics graphics, Font font, GaugeTheme theme,
                                          CarEntity car, CarSpec spec,
                                          float x, float bottomY, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, bottomY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);

        float half = PANEL_WIDTH / 2.0F;
        GaugePainter.rect(graphics, -half, -PANEL_HEIGHT, half, 0.0F, backdrop(theme.panel));
        GaugePainter.rect(graphics, -half, -PANEL_HEIGHT, half, -PANEL_HEIGHT + 1.0F,
                theme.aidOff);
        shiftLights(graphics, theme, car, spec, -PANEL_HEIGHT - 4.0F);

        // 段。赤地に白抜きで、いちばん目立たせる
        float boxLeft = -half + 6.0F;
        float boxTop = -PANEL_HEIGHT + 5.0F;
        float boxSize = 22.0F;
        GaugePainter.rect(graphics, boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize,
                theme.gearBox);

        GaugePainter.flush(graphics);

        int gear = car.getRenderGear();
        String gearText = gear > 0 ? String.valueOf(gear) : gear < 0 ? "R" : "N";
        float gearScale = 1.7F;
        graphics.pose().pushPose();
        graphics.pose().translate(boxLeft + boxSize / 2.0F - font.width(gearText) * gearScale / 2.0F,
                boxTop + boxSize / 2.0F - font.lineHeight * gearScale / 2.0F, 0.0F);
        graphics.pose().scale(gearScale, gearScale, 1.0F);
        graphics.drawString(font, gearText, 0, 0, theme.gearText, false);
        graphics.pose().popPose();

        Component mode = Component.translatable(spec.isManual()
                ? "hud.kurumamod.transmission_mt" : "hud.kurumamod.transmission_at");
        graphics.pose().pushPose();
        graphics.pose().translate(boxLeft + boxSize / 2.0F - font.width(mode) * SMALL_TEXT_SCALE / 2.0F,
                -8.0F, 0.0F);
        graphics.pose().scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE, 1.0F);
        graphics.drawString(font, mode, 0, 0, theme.unit, false);
        graphics.pose().popPose();

        // 速度。いちばん見る数字なので大きく、右詰め
        String speed = String.format("%.0f", Math.abs(car.getRenderSpeed()) * 3.6);
        float speedScale = 1.6F;
        graphics.pose().pushPose();
        graphics.pose().translate(half - 6.0F - font.width(speed) * speedScale,
                -PANEL_HEIGHT + 6.0F, 0.0F);
        graphics.pose().scale(speedScale, speedScale, 1.0F);
        graphics.drawString(font, speed, 0, 0, theme.text, false);
        graphics.pose().popPose();

        Component unit = Component.translatable("hud.kurumamod.kmh");
        graphics.pose().pushPose();
        graphics.pose().translate(half - 6.0F - font.width(unit) * SMALL_TEXT_SCALE, -8.0F, 0.0F);
        graphics.pose().scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE, 1.0F);
        graphics.drawString(font, unit, 0, 0, theme.unit, false);
        graphics.pose().popPose();

        graphics.pose().popPose();
    }

    /**
     * シフトインジケーター。回転数が上がるほど左から順に点き、レブ手前で赤くなる。
     *
     * <p><b>回転計より先に気づけるのが値打ち。</b>針の角度を読むより、視界の端で色が変わる
     * ほうが速い。マニュアルで走るときにいちばん効く。</p>
     *
     * <p>当時の車には無い装置なので 90 年代の文字盤とは年代が合わないが、<b>段を自分で
     * 選ぶ以上これが無いと回転計を読み続けることになる</b>ので残してある。</p>
     */
    private static void shiftLights(GuiGraphics graphics, GaugeTheme theme,
                                    CarEntity car, CarSpec spec, float bottomY) {
        double redline = spec.redlineRpm();
        double fraction = redline > 0.0 ? car.getRenderRpm() / redline : 0.0;
        // 下のほうは点けない。全域を使うと「いつも半分点いている」ことになって意味が薄れる
        double lit = (fraction - SHIFT_LIGHT_FROM) / (1.0 - SHIFT_LIGHT_FROM);
        int litCount = (int) Math.floor(Math.max(0.0, Math.min(1.0, lit)) * SHIFT_LIGHTS + 1.0e-6);

        // LED は緩い弧に沿って並べる。<b>中心は下、弧は上向き（∩）。</b>
        // 中心を上に置くと弧が下向き（∪）になり、両端が上がって左右のメーターへ
        // 逃げていくように見える。上向きなら中央がいちばん高く、視線の通り道に沿う
        float spread = SHIFT_SPREAD_DEGREES;
        // 弧の膨らみ（サジッタ）。これを引いておくと、いちばん低くなる両端が
        // ちょうど bottomY に来る——中央を bottomY に合わせると両端が下がって
        // 小窓に食い込む
        float sagitta = SHIFT_ARC_RADIUS
                * (1.0F - (float) Math.cos(Math.toRadians(spread)));
        float centerY = bottomY + SHIFT_ARC_RADIUS - sagitta;
        for (int i = 0; i < SHIFT_LIGHTS; i++) {
            float t = (float) i / (SHIFT_LIGHTS - 1);
            // 角度は時計回りで、真上が 270 度。中心を下に置いたので、
            // 角度が大きいほうが右になる（中心が上のときと逆）
            float angle = 270.0F - spread + spread * 2.0F * t;
            int color = i < litCount ? theme.shiftLight(t) : theme.ledOff;
            // 外側（＝上）へ伸ばす。内側へ伸ばすと弧の線より下に出て小窓に近づく
            GaugePainter.radialBar(graphics, 0.0F, centerY,
                    SHIFT_ARC_RADIUS, SHIFT_ARC_RADIUS + 3.0F, angle, 3.0F, color);
        }
    }

    /**
     * 下敷き（文字盤と中央パネルの地）の色。<b>設定の濃さを掛ける。</b>
     *
     * <p>薄くすると後ろのチャットが透けて見える。<b>掛けるのは地だけで、目盛り・数字・針には
     * 掛けない</b>——読めなくなっては計器の意味がない。HUD 自体はチャットより手前に描かれて
     * いる（{@code RenderGuiEvent.Post} は {@code Gui#render} の後）ので、
     * <b>順序ではなく濃さの問題</b>だった。</p>
     */
    private static int backdrop(int color) {
        return backdrop(color, 1.0F);
    }

    /** 同上。{@code extra} はさらに掛ける不透明度（ガラスの照りを重ねるのに使う）。 */
    private static int backdrop(int color, float extra) {
        int alpha = Math.round((color >>> 24) * (float) ClientConfig.gaugeOpacity * extra);
        return (Math.min(255, alpha) << 24) | (color & 0x00FFFFFF);
    }

    /** 割合（0..1）を文字盤の角度へ。範囲の外はクランプする。 */
    private static float angleFor(double fraction) {
        float t = (float) Math.max(0.0, Math.min(1.0, fraction));
        return ANGLE_START + ANGLE_SWEEP * t;
    }
}
