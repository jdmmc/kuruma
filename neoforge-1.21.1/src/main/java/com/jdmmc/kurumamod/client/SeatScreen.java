package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
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
 * <p><b>デザイン寸法</b>（{@code designWheelBase}・{@code designRideHeight}）は
 * 2 ページ目。メッシュが何 m で作られているかの申告で、間違えると車体やタイヤが小さく／大きく描かれる。
 * 座席の位置は車体の大きさが決まってから合わせるものなので、同じ画面に置いてある。
 * {@link CarModel#setDesignOverride} で、座席と同じく<b>このクライアントだけ・ゲームを閉じるまで</b>効く。</p>
 *
 * <p><b>画面は貼り先のファイルで分ける。</b>上のタブが「車の JSON」（{@code vehicles/<車>.json}）と
 * 「ホイールの JSON」（いま描いているホイールの部品の {@code car_parts/<id>.json}）で、
 * どちらのファイルの値を動かしているのかが常に見える。コピーもそのファイルにそのまま貼れる形だけを出す。</p>
 * <ul>
 *   <li>車の JSON はボディ・座席・ハンドルの 3 セクション。ボディは車体メッシュの寸法
 *       （{@code designWheelBase}・{@code designRideHeight}）。<b>車の JSON はホイールの項目を持たない</b>
 *       ——タイヤの位置は物理の寸法（ホイールベース・トレッド）が決めるので、調整画面（G）で動かす</li>
 *   <li>ホイールの JSON は {@code camber}・{@code scale}・{@code designRadius}・{@code rimRadius}。
 *       車の JSON にメッシュを直接書いた以前の車では動かせない</li>
 * </ul>
 *
 * <p>換装画面の上書きや、履いた部品（メッシュを持たない {@code deep_dish} など）の指定が勝っていて、
 * ここで動かしても見えない行は灰色にする。</p>
 */
public final class SeatScreen extends Screen {

    /** 何を動かすか。 */
    private enum Group {
        SEAT, STEERING, DESIGN, WHEEL_PART
    }

    /** ページ。{@link #WHEEL} だけがホイールの JSON、残りは車の JSON のセクション。 */
    private enum Page {
        BODY, SEAT, STEERING, WHEEL;

        boolean isCar() {
            return this != WHEEL;
        }
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

    /** 座席。車の原点からの位置。 */
    private static final Row[] SEAT_ROWS = {
            new Row(Group.SEAT, "x", 0, -1.5, 1.5, 0.01),
            new Row(Group.SEAT, "y", 1, -1.5, 1.5, 0.01),
            new Row(Group.SEAT, "z", 2, -2.5, 2.5, 0.01),
    };

    /** ハンドル。範囲が狭いのは、メッシュの位置からの<b>ずれ</b>だから。 */
    private static final Row[] STEERING_ROWS = {
            new Row(Group.STEERING, "x", 0, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "y", 1, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "z", 2, -0.5, 0.5, 0.01),
            new Row(Group.STEERING, "tilt", TILT, -30.0, 30.0, 0.5),
    };

    /** {@link Row#axis} のうち、ホイールの JSON の 4 つ。 */
    private static final int CAMBER = 0;
    private static final int WIDTH = 1;
    private static final int DESIGN_RADIUS = 2;
    private static final int RIM_RADIUS = 3;

    /** ボディ。車体メッシュの寸法。メッシュの作りの申告なので、範囲は広めに取ってある。 */
    private static final Row[] BODY_ROWS = {
            new Row(Group.DESIGN, "wheel_base", WHEEL_BASE, 0.5, 8.0, 0.01),
            new Row(Group.DESIGN, "ride_height", RIDE_HEIGHT, -0.5, 2.0, 0.005),
    };

    /** ホイールの JSON。キャンバー・太さの範囲は換装画面と同じ。並びは {@link #CAMBER} などの値と揃える。 */
    private static final Row[] PART_ROWS = {
            new Row(Group.WHEEL_PART, "camber", CAMBER, PartFitment.CAMBER_MIN, PartFitment.CAMBER_MAX, 0.5),
            new Row(Group.WHEEL_PART, "width", WIDTH, PartFitment.WIDTH_MIN, PartFitment.WIDTH_MAX, 0.01),
            new Row(Group.WHEEL_PART, "design_radius", DESIGN_RADIUS, 0.1, 1.5, 0.005),
            new Row(Group.WHEEL_PART, "rim_radius", RIM_RADIUS, 0.05, 1.5, 0.001),
    };

    /** いま開いているページ。開き直しても前のページから。 */
    private static Page page = Page.BODY;
    /** 車の JSON で最後に開いていたセクション。ホイールの JSON から戻るときにここへ戻す。 */
    private static Page carPage = Page.BODY;

    // 1080p の GUI スケール自動（高さ 270）に収まるよう詰めてある。行を足すならここを見直す。
    // 上に 2 段のタブ（ファイル・セクション）があるので、行はその下から
    private static final int ROW_HEIGHT = 21;
    private static final int TOP = 50;
    private static final int TAB_HEIGHT = 14;
    private static final int LABEL_WIDTH = 84;
    private static final int SLIDER_WIDTH = 200;
    private static final int BUTTON_WIDTH = 20;
    private static final int ROW_WIDTH = LABEL_WIDTH + BUTTON_WIDTH + 4 + SLIDER_WIDTH + 4 + BUTTON_WIDTH + 4 + BUTTON_WIDTH;
    /** 下のボタンの位置はいちばん行の多いページ（ハンドル・ホイール）に揃える。ページを替えてもボタンが動かないように。 */
    private static final int BOTTOM_ROW = PART_ROWS.length + 2;

    private final ResourceLocation carId;
    private final List<Slider> sliders = new ArrayList<>();
    /** 「コピーした」を出しておく残りのフレーム数。 */
    private int copiedFrames;

    public SeatScreen(ResourceLocation carId) {
        super(Component.translatable("screen.kurumamod.seat.title"));
        this.carId = carId;
    }

    private static Row[] rows() {
        return switch (page) {
            case BODY -> BODY_ROWS;
            case SEAT -> SEAT_ROWS;
            case STEERING -> STEERING_ROWS;
            case WHEEL -> PART_ROWS;
        };
    }

    private void open(Page next) {
        page = next;
        if (next.isCar()) {
            carPage = next;
        }
        rebuildWidgets();
    }

    private static boolean isWheel(Row row) {
        return row.group() == Group.WHEEL_PART;
    }

    /** いま乗っている車（この画面の車種なら）。乗っていなければ null。 */
    private CarEntity ridingCar() {
        if (minecraft != null && minecraft.player != null
                && minecraft.player.getVehicle() instanceof CarEntity car && car.getCarId().equals(carId)) {
            return car;
        }
        return null;
    }

    /** 描くホイールを決める装着状態。乗っていなければ何も履いていない状態（＝純正）。 */
    private PartFitment fitment() {
        CarEntity car = ridingCar();
        return car != null ? car.getEffectiveFitment() : PartFitment.EMPTY;
    }

    /** 下の 4 行が動かす部品（いま描いているメッシュの持ち主）。車の JSON のメッシュなら null。 */
    @org.jetbrains.annotations.Nullable
    private ResourceLocation meshOwner() {
        return CarPartModel.meshOwner(carId, CarModel.get(carId), fitment());
    }

    /**
     * ホイールの JSON の値。書いていない項目は描くときに使われる値（キャンバー・太さは車の値、
     * 大きさはゲージ、リムの半径は測った値）。
     */
    private double meshValue(CarPartModel part, Row row) {
        CarModel car = CarModel.get(carId);
        if (row.axis() == CAMBER) {
            return part.camber() != null ? part.camber() : car.camber();
        }
        if (row.axis() == WIDTH) {
            return part.scale() != null ? part.scale().x() : car.wheelScale().x();
        }
        if (row.axis() == DESIGN_RADIUS) {
            return part.designRadius() != null && part.designRadius() > 0.0
                    ? part.designRadius() : CarModel.GAUGE_WHEEL_RADIUS;
        }
        if (part.rimRadius() != null && part.rimRadius() > 0.0) {
            return part.rimRadius();
        }
        return measuredRim();
    }

    /** 書いていないときにリムの半径として使われる値（{@code CarObjRenderer#beadRadius}）。 */
    private double measuredRim() {
        CarPartModel.Wheel look = CarPartModel.wheelOf(carId, CarModel.get(carId), fitment());
        CarPartModel.Wheel unwritten = new CarPartModel.Wheel(look.model(), look.texture(), look.offset(),
                look.scale(), look.camber(), look.designRadius(), null);
        return CarObjRenderer.beadRadius(unwritten, ObjModel.get(look.model()));
    }

    /**
     * 付け方の行が、換装画面の上書きか部品の指定に負けていて見えないか。描く値と車の値を比べる。
     * メッシュの行は持ち主がいなければ動かせない。
     */
    private boolean shadowed(Row row) {
        if (!isWheel(row)) {
            return false;
        }
        if (meshOwner() == null) {
            return true;
        }
        CarPartModel.Wheel look = CarPartModel.wheelOf(carId, CarModel.get(carId), fitment());
        double drawn;
        if (row.axis() == CAMBER) {
            drawn = look.camber();
        } else if (row.axis() == WIDTH) {
            drawn = look.scale().x();
        } else {
            return false;
        }
        return Math.abs(drawn - get(row)) > 1.0e-6;
    }

    private CarModel.Vec3 current(Row row) {
        return row.group() == Group.STEERING ? CarSeat.currentSteering(carId) : CarSeat.current(carId);
    }

    private static double component(CarModel.Vec3 v, Row row) {
        return row.axis() == 0 ? v.x() : row.axis() == 1 ? v.y() : v.z();
    }

    private static double component(CarModel.Design d, Row row) {
        return row.axis() == WHEEL_BASE ? d.wheelBase() : d.rideHeight();
    }

    private double get(Row row) {
        if (row.group() == Group.DESIGN) {
            return component(CarModel.get(carId).design(), row);
        }
        if (row.group() == Group.WHEEL_PART) {
            ResourceLocation owner = meshOwner();
            return owner == null ? row.min() : meshValue(CarPartModel.get(owner), row);
        }
        return row.axis() == TILT ? CarSeat.currentSteeringTilt(carId) : component(current(row), row);
    }

    private double original(Row row) {
        CarModel model = CarModel.fromJson(carId);
        if (row.group() == Group.DESIGN) {
            return component(model.design(), row);
        }
        if (row.group() == Group.WHEEL_PART) {
            ResourceLocation owner = meshOwner();
            if (owner == null) {
                return row.min();
            }
            // JSON に書いていない項目は「書いていない」へ戻す（今の値を書いたことにしない）。NaN がその印
            return written(CarPartModel.fromJson(owner), row) == null
                    ? Double.NaN : meshValue(CarPartModel.fromJson(owner), row);
        }
        if (row.axis() == TILT) {
            return model.steeringTilt();
        }
        return component(row.group() == Group.STEERING ? model.steeringOffset() : model.seatOffset(), row);
    }

    private void set(Row row, double value) {
        if (row.group() == Group.WHEEL_PART && Double.isNaN(value)) {
            // 「R」で JSON に書いていない状態へ戻す
            ResourceLocation owner = meshOwner();
            if (owner != null) {
                setPart(owner, row, null);
            }
            return;
        }
        // 0.1mm（傾きは 0.0001 度）単位に丸める（浮動小数の端数が JSON へ出ないよう）
        double v = Math.round(Mth.clamp(value, row.min(), row.max()) * 10000.0) / 10000.0;
        if (row.group() == Group.WHEEL_PART) {
            ResourceLocation owner = meshOwner();
            if (owner != null) {
                setPart(owner, row, v);
            }
            return;
        }
        if (row.group() == Group.DESIGN) {
            CarModel.Design now = CarModel.get(carId).design();
            CarModel.setDesignOverride(carId, new CarModel.Design(
                    row.axis() == WHEEL_BASE ? v : now.wheelBase(),
                    row.axis() == RIDE_HEIGHT ? v : now.rideHeight()));
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

    /**
     * ホイールの JSON の 1 項目を上書きする。ほかの項目はいまの値（書いていなければ書いていないまま）を引き継ぐ。
     *
     * @param value null は「書いていない」へ戻す（リムの半径を測るに戻すときだけ使う）
     */
    private void setPart(ResourceLocation owner, Row row, @org.jetbrains.annotations.Nullable Double value) {
        CarPartModel now = CarPartModel.get(owner);
        Double camber = row.axis() == CAMBER ? value : now.camber();
        Double width = row.axis() == WIDTH ? value : now.scale() != null ? Double.valueOf(now.scale().x()) : null;
        double design = row.axis() == DESIGN_RADIUS && value != null ? value
                : meshValue(now, PART_ROWS[DESIGN_RADIUS]);
        Double rim = row.axis() == RIM_RADIUS ? value : now.rimRadius();
        CarPartModel.setMeshOverride(owner, new CarPartModel.MeshOverride(camber, width, design, rim));
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
            // メッシュの持ち主がいない（車の JSON にメッシュを直接書いた以前の車）ときは動かせない
            slider.active = !(row.group() == Group.WHEEL_PART && meshOwner() == null);
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
        // 上段のタブは貼り先のファイル。ファイル名も出す（どこへ貼るのかが常に見えるように）
        ResourceLocation owner = meshOwner();
        Button carTab = addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.seat.tab.car")
                                .append(": " + carId.getPath() + ".json"),
                        button -> open(carPage))
                .bounds(width / 2 - 162, 16, 160, TAB_HEIGHT).build());
        Button wheelTab = addRenderableWidget(Button.builder(
                        Component.translatable("screen.kurumamod.seat.tab.wheel")
                                .append(owner == null ? "" : ": " + owner.getPath() + ".json"),
                        button -> open(Page.WHEEL))
                .bounds(width / 2 + 2, 16, 160, TAB_HEIGHT).build());
        // 開いているタブは押せなくする（それで選ばれていることも分かる）
        carTab.active = !page.isCar();
        wheelTab.active = page.isCar();
        if (page.isCar()) {
            // 下段は車の JSON のセクション
            Page[] sections = {Page.BODY, Page.SEAT, Page.STEERING};
            for (int i = 0; i < sections.length; i++) {
                Page section = sections[i];
                Button tab = addRenderableWidget(Button.builder(Component.translatable(
                                "screen.kurumamod.seat.section." + section.name().toLowerCase(Locale.ROOT)),
                                button -> open(section))
                        .bounds(width / 2 - 123 + i * 82, 32, 80, TAB_HEIGHT).build());
                tab.active = page != section;
            }
        }

        // 3 つ並べて 308。実効幅 427 の画面にも収まる
        int bottom = rowY(BOTTOM_ROW) + 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.copy"), button -> {
                    if (minecraft != null) {
                        minecraft.keyboardHandler.setClipboard(json());
                        copiedFrames = 60;
                    }
                })
                .bounds(width / 2 - 154, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.seat.reset_all"), button -> {
                    // 戻すのは開いているページの分だけ
                    switch (page) {
                        case BODY -> CarModel.clearDesignOverride(carId);
                        case SEAT -> CarSeat.clearOverride(carId);
                        case STEERING -> CarSeat.clearSteeringOverride(carId);
                        case WHEEL -> {
                            ResourceLocation part = meshOwner();
                            if (part != null) {
                                CarPartModel.clearMeshOverride(part);
                            }
                        }
                    }
                    for (Slider slider : sliders) {
                        slider.sync();
                    }
                })
                .bounds(width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 54, bottom, 100, 20).build());
    }

    private void nudge(Row row, Slider slider, int direction) {
        if (!slider.active) {
            return;
        }
        set(row, get(row) + direction * row.step() * (hasShiftDown() ? 10.0 : 1.0));
        slider.sync();
    }

    /** 開いているタブのファイルにそのまま貼れる行（開いているページの分）。 */
    private String json() {
        return switch (page) {
            case BODY -> designJson();
            case SEAT -> seatJson();
            case STEERING -> steeringJson();
            case WHEEL -> {
                ResourceLocation owner = meshOwner();
                yield owner == null ? "" : wheelMeshJson(owner, true);
            }
        };
    }

    /**
     * 部品の {@code wheel}。<b>ブロックごと置き換えられる形</b>で出す（書いてあったオフセットの既定値も残す）。
     * 書いていない項目は書かない（大きさはゲージと同じなら、リムの半径は測るままなら書かない）。
     *
     * @param full false なら画面に出す短い形（モデルとテクスチャを省く）
     */
    private String wheelMeshJson(ResourceLocation owner, boolean full) {
        CarPartModel part = CarPartModel.get(owner);
        double design = meshValue(part, PART_ROWS[DESIGN_RADIUS]);
        List<String> fields = new ArrayList<>();
        if (full) {
            fields.add("\"model\": \"" + part.model() + "\"");
            if (part.texture() != null) {
                fields.add("\"texture\": \"" + part.texture() + "\"");
            }
            if (part.offset() != null) {
                fields.add("\"offset\": " + array(part.offset()));
            }
        }
        if (part.camber() != null) {
            fields.add("\"camber\": " + number(part.camber()));
        }
        if (part.scale() != null) {
            fields.add("\"scale\": " + array(part.scale()));
        }
        if (Math.abs(design - CarModel.GAUGE_WHEEL_RADIUS) > 1.0e-6) {
            fields.add("\"designRadius\": " + number(design));
        }
        if (part.rimRadius() != null && part.rimRadius() > 0.0) {
            fields.add("\"rimRadius\": " + number(part.rimRadius()));
        }
        return "\"wheel\": { " + String.join(", ", fields) + " }";
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
                + ", \"designRideHeight\": " + number(d.rideHeight());
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // renderBackground は呼ばない。一人称の見え方を見ながら動かす画面なので。
        // 目線は実際のプレイヤーの目から測る（座席の Y に乗り方のずれと目の高さが足される）。
        // 縦に余裕が無いので題名の横に並べる
        Component head = title.copy().append("    " + carId);
        if (minecraft != null && minecraft.player != null && minecraft.player.getVehicle() != null) {
            double eye = minecraft.player.getEyeY() - minecraft.player.getVehicle().getY();
            head = head.copy().append("    ").append(Component.translatable("screen.kurumamod.seat.eye",
                    String.format(Locale.ROOT, "%.3f", eye)));
        }
        graphics.drawCenteredString(font, head, width / 2, 4, 0xFFFFFF);
        Row[] rows = rows();
        int left = left();
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            // JSON の値から動かした項目は黄色（調整画面と同じ）
            double original = original(row);
            boolean changed = Double.isNaN(original)
                    ? row.group() == Group.WHEEL_PART && meshOwner() != null && hasPartOverride(row)
                    : Math.abs(get(row) - original) > 1.0e-6;
            // 座席とハンドルはセクションのタブで分かるので、軸の名前だけ
            Component label = row.group() == Group.DESIGN
                    ? Component.translatable("screen.kurumamod.seat.design." + row.key())
                    : isWheel(row)
                    ? Component.translatable("screen.kurumamod.seat.wheel." + row.key())
                    : Component.translatable("screen.kurumamod.seat." + row.key());
            // 動かしても見えない行は灰色（換装画面の上書き・部品の指定が勝っている、メッシュの持ち主がいない）
            int color = shadowed(row) ? 0x777777 : changed ? 0xFFFF55 : 0xFFFFFF;
            graphics.drawString(font, label, left, rowY(i) + 6, color);
        }
        int below = rowY(rows.length) + 6;
        switch (page) {
            case BODY -> {
                graphics.drawCenteredString(font, Component.literal(designJson()), width / 2, below, 0xFFFFFF);
                graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.design.note"),
                        width / 2, below + 14, 0xAAAAAA);
            }
            case SEAT -> graphics.drawCenteredString(font, Component.literal(seatJson()), width / 2, below, 0xFFFFFF);
            case STEERING -> graphics.drawCenteredString(font, Component.literal(steeringJson()),
                    width / 2, below, 0xFFFFFF);
            case WHEEL -> {
                ResourceLocation owner = meshOwner();
                Component mesh = owner == null
                        ? Component.translatable("screen.kurumamod.seat.wheel.legacy")
                        : Component.literal(wheelMeshJson(owner, false));
                graphics.drawCenteredString(font, mesh, width / 2, below, owner == null ? 0xAAAAAA : 0xFFFFFF);
                // 触らなくてよいことを先に言う。ずれて見えると、まずここを動かしたくなるので
                graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.wheel.gauge_note"),
                        width / 2, below + 14, 0xAAAAAA);
            }
        }
        // 「コピーした」は説明の行に重ねて出す（縦に余裕が無い）
        if (copiedFrames > 0) {
            copiedFrames--;
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.copied"),
                    width / 2, rowY(BOTTOM_ROW) + 26, 0x55FF55);
        } else if (anyShadowed()) {
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.wheel.shadowed"),
                    width / 2, rowY(BOTTOM_ROW) + 26, 0xAAAAAA);
        } else {
            // 貼り先は開いているタブのファイル
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.seat.hint", pasteTarget()),
                    width / 2, rowY(BOTTOM_ROW) + 26, 0xAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** コピーした行の貼り先（画面に収まるよう、assets/<名前空間>/ を省いた形）。 */
    private String pasteTarget() {
        if (page.isCar()) {
            return "vehicles/" + carId.getPath() + ".json";
        }
        ResourceLocation owner = meshOwner();
        return owner == null ? "-" : "car_parts/" + owner.getPath() + ".json";
    }

    /** 動かしても見えない行があるか（メッシュの持ち主がいないのは別に出すので数えない）。 */
    private boolean anyShadowed() {
        if (page == Page.WHEEL && meshOwner() == null) {
            return false;
        }
        for (Row row : rows()) {
            if (shadowed(row)) {
                return true;
            }
        }
        return false;
    }

    /** JSON に書いていない項目に、手で値を入れたか。 */
    private boolean hasPartOverride(Row row) {
        ResourceLocation owner = meshOwner();
        if (owner == null) {
            return false;
        }
        Double now = written(CarPartModel.get(owner), row);
        Double json = written(CarPartModel.fromJson(owner), row);
        return now == null ? json != null : !now.equals(json);
    }

    /** ホイールの JSON にその項目が書いてあればその値、書いていなければ null。スケールは必ず値がある。 */
    @org.jetbrains.annotations.Nullable
    private static Double written(CarPartModel part, Row row) {
        return switch (row.axis()) {
            case CAMBER -> part.camber();
            case WIDTH -> part.scale() != null ? Double.valueOf(part.scale().x()) : null;
            case RIM_RADIUS -> part.rimRadius() != null && part.rimRadius() > 0.0 ? part.rimRadius() : null;
            default -> part.designRadius() != null && part.designRadius() > 0.0
                    ? part.designRadius() : Double.valueOf(CarModel.GAUGE_WHEEL_RADIUS);
        };
    }

    /** 一人称でドラッグしたときの回り方 [度／画面の 1 ピクセル]。 */
    private static final double LOOK_DEGREES_PER_PIXEL = 0.15;

    /**
     * 何も無いところをドラッグしたら視点を動かす。先にウィジェットへ渡すこと（スライダーをつまんだ指が
     * カメラも動かしては困る）。
     *
     * <p>三人称はセッティング画面（G）と同じく車のまわりを回す（{@link CarChaseCamera#orbit}）。
     * <b>一人称は頭の向きを回す</b>——座席は運転席から見回して決めるもので、{@code orbit} は一人称では
     * 効かないため。マウスの移動は GUI の座標で来るので、画面の倍率を掛けて実際のピクセルへ戻す。</p>
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        if (minecraft.options.getCameraType().isFirstPerson()) {
            // Entity#turn は渡した値に 0.15 を掛けて度にする
            double pixels = minecraft.getWindow().getGuiScale();
            minecraft.player.turn(dragX * pixels * LOOK_DEGREES_PER_PIXEL / 0.15,
                    dragY * pixels * LOOK_DEGREES_PER_PIXEL / 0.15);
        } else {
            CarChaseCamera.orbit(dragX, dragY);
        }
        return true;
    }

    /** ホイールで寄り引き（三人称だけ。セッティング画面と同じ）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, delta)) {
            return true;
        }
        if (minecraft != null && !minecraft.options.getCameraType().isFirstPerson()) {
            CarChaseCamera.zoom(delta);
        }
        return true;
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
            if (isWheel(row)) {
                setMessage(Component.literal(wheelText()));
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
            }
            return text;
        }

        private String wheelText() {
            if (row.group() == Group.WHEEL_PART && meshOwner() == null) {
                return "-";
            }
            double v = get(row);
            if (row.axis() == CAMBER) {
                return String.format(Locale.ROOT, "%+.1f°", v);
            }
            if (row.axis() == WIDTH) {
                return String.format(Locale.ROOT, "%.0f %%", v * 100.0);
            }
            if (row.axis() == DESIGN_RADIUS) {
                // 小さい値ほど大きく描かれるので、拡大率も添える
                CarEntity car = ridingCar();
                double radius = car != null ? car.getSpec().wheelRadius() : CarSpec.DEFAULT.wheelRadius();
                return String.format(Locale.ROOT, "%.3f m  (×%.3f)", v, radius / v);
            }
            String text = String.format(Locale.ROOT, "%.3f m", v);
            ResourceLocation owner = meshOwner();
            if (owner != null && CarPartModel.get(owner).rimRadius() == null) {
                text += "  (" + Component.translatable("screen.kurumamod.seat.wheel.measured").getString() + ")";
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
