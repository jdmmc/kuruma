package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 換装画面。{@link CarTuningScreen} のフッターから開く。
 *
 * <h2>置き場所は下端。画面は暗くしない</h2>
 *
 * <p><b>換装は「どれを選ぶか」ではなく「履かせた車がどう見えるか」で決める</b>ので、
 * 一覧で画面を覆ってしまうと肝心の車が見えない。そこで
 * {@link Screen#renderBackground} を<b>呼ばず</b>、パーツを下端のタイル 1 列と
 * 調整値のスライダーだけに収めて、残りは全部後ろの車に譲っている
 * （設定画面がゲームを止めないのと同じ理由）。</p>
 *
 * <p>あわせて {@link CarChaseCamera} が<b>車を画面の上へ持ち上げる</b>——下端に置いた以上、
 * 車が下半分にいると自分で自分を隠すことになる。</p>
 *
 * <p><b>タイルはパーツそのもの。</b>付ける場所（{@link PartSlot}）はいまホイールしかないので、
 * 場所の切り替えは<b>2 つ以上あるときだけ</b>出す。1 つしかないうちから置くと、
 * 押せないタブが 1 枚あるだけの列になる。</p>
 *
 * <h2>装着中の印は毎フレーム車から読む</h2>
 *
 * <p>プリセット画面と違って、こちらは<b>サーバーへ要求を送る</b>——何を履けるか決めるのは
 * サーバーなので、押した結果が返ってくるまで見た目は変わらない
 * （{@code CarPartRequestPacket} → {@code CarPartsPacket}）。手元の変数に覚えておくと、
 * サーバーが弾いたときに「押したのに履けていない」ことが画面から分からなくなる。</p>
 */
public class CarPartScreen extends Screen {

    // ------------------------------------------------------------------
    // 見た目の寸法。タイルは「アイコン ＋ 下端の名前の帯」の 2 段構え
    // ------------------------------------------------------------------

    private static final int TILE_WIDTH = 92;
    private static final int TILE_HEIGHT = 58;
    private static final int TILE_GAP = 6;
    /** 装着中のタイルだけ、この高さぶん上へ伸びる。<b>色だけでなく形でも分かるように。</b> */
    private static final int TILE_RAISE = 12;
    /** 名前を出す帯の高さ。 */
    private static final int LABEL_HEIGHT = 15;
    private static final int BOTTOM_MARGIN = 8;
    /** 列の左右に残す余白。ここに送りの合図（‹ ›）を出す。 */
    private static final int SIDE_MARGIN = 22;
    private static final int SLIDER_WIDTH = 210;
    private static final int RESET_WIDTH = 18;
    /** 調整値 1 行ぶんの高さ。 */
    private static final int ROW_STEP = 24;
    private static final int TEXT_LEFT = 24;
    private static final int TITLE_Y = 18;

    // 配色は ScreenStyle が持つ（セッティング画面と揃えるため）。
    // ここに残すのはホイールの絵の色だけ
    private static final int COLOR_TIRE = 0xFF2A2A2A;
    private static final int COLOR_RIM = 0xFFB4B4B4;
    private static final int COLOR_RIM_FITTED = ScreenStyle.ACCENT;

    private final Screen parent;
    /** いま並べている場所。場所が 2 つ以上あるときだけ画面から切り替えられる。 */
    private PartSlot slot = PartSlot.WHEEL;
    private final List<PartTile> tiles = new ArrayList<>();
    private final List<LookSlider> sliders = new ArrayList<>();

    /** 左端に出しているタイルの番号。横スクロール。 */
    private int scroll;
    private int visibleTiles;
    private int rowLeft;
    private int rowTop;

    public CarPartScreen(Screen parent) {
        super(Component.translatable("screen.kurumamod.part.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        tiles.clear();
        sliders.clear();

        List<Entry> entries = entries();
        visibleTiles = Math.max(1, (width - SIDE_MARGIN * 2 + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
        visibleTiles = Math.min(visibleTiles, entries.size());
        rowTop = height - BOTTOM_MARGIN - TILE_HEIGHT - TILE_RAISE;

        int rowWidth = visibleTiles * (TILE_WIDTH + TILE_GAP) - TILE_GAP;
        rowLeft = (width - rowWidth) / 2;

        for (Entry entry : entries) {
            tiles.add(addRenderableWidget(new PartTile(entry)));
        }

        addAdjustmentRows();
        addSlotTabs();

        scroll = Math.max(0, Math.min(scroll, Math.max(0, tiles.size() - visibleTiles)));
        layout();
    }

    /**
     * 調整値の行。<b>タイルの真上に積む。</b>
     *
     * <p>履かせた結果を見ながら決めるものなので、別の画面へ持っていくと
     * 履き替えるたびに画面を行き来することになる。</p>
     *
     * <p>いじれるのはホイールだけ。他の場所には角度もオフセットも無いので置かない。</p>
     */
    private void addAdjustmentRows() {
        if (slot != PartSlot.WHEEL) {
            return;
        }
        int left = (width - (SLIDER_WIDTH + TILE_GAP + RESET_WIDTH)) / 2;
        Adjustment[] all = Adjustment.VALUES;
        for (int i = 0; i < all.length; i++) {
            Adjustment adjustment = all[i];
            int y = rowTop - ROW_STEP * (all.length - i);
            LookSlider slider = addRenderableWidget(new LookSlider(adjustment, left, y, SLIDER_WIDTH));
            sliders.add(slider);
            addRenderableWidget(Button.builder(Component.literal("R"), button -> {
                        // 上書きをやめる＝部品（カーパック）が指定した値へ戻す
                        apply(adjustment, null);
                        sendCurrent();
                        slider.syncFromCar();
                    })
                    .tooltip(Tooltip.create(Component.translatable(adjustment.key("reset"))))
                    .bounds(left + SLIDER_WIDTH + TILE_GAP, y, RESET_WIDTH, 20)
                    .build());
        }
    }

    /**
     * 場所の切り替え。<b>2 つ以上あるときだけ出す。</b>
     *
     * <p>いまはホイールしかないので何も置かれない。エアロが増えた時点で自動的に現れる。</p>
     */
    private void addSlotTabs() {
        if (PartSlot.VALUES.length < 2) {
            return;
        }
        int tabWidth = 68;
        int y = rowTop - ROW_STEP * (Adjustment.VALUES.length + 1);
        int totalWidth = PartSlot.VALUES.length * (tabWidth + TILE_GAP) - TILE_GAP;
        int left = (width - totalWidth) / 2;
        for (int i = 0; i < PartSlot.VALUES.length; i++) {
            PartSlot value = PartSlot.VALUES[i];
            Button tab = addRenderableWidget(Button.builder(value.displayName(), button -> {
                        slot = value;
                        scroll = 0;
                        rebuildWidgets();
                    })
                    .bounds(left + i * (tabWidth + TILE_GAP), y, tabWidth, 18)
                    .build());
            tab.active = value != slot;
        }
    }

    /**
     * 並べるもの。<b>先頭は必ず「純正」（＝何も履いていない状態）。</b>
     *
     * <p>外す手段が列の中に無いと、一度履いたら戻せなくなる。</p>
     */
    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(null, Component.translatable("screen.kurumamod.part.stock"), null));
        for (CarPart part : CarParts.forSlot(slot)) {
            // どのカーパックのものかを小さく出す。同梱のぶんは名前空間を出さない
            String namespace = part.id().getNamespace();
            entries.add(new Entry(part.id(), part.displayName(),
                    Kurumamod.MODID.equals(namespace) ? null : namespace));
        }
        return entries;
    }

    /** 表示範囲に入るタイルだけ並べ、外れたものは隠す。 */
    private void layout() {
        for (int i = 0; i < tiles.size(); i++) {
            int index = i - scroll;
            PartTile tile = tiles.get(i);
            tile.visible = index >= 0 && index < visibleTiles;
            tile.setPosition(rowLeft + index * (TILE_WIDTH + TILE_GAP), rowTop);
        }
    }

    /**
     * ホイールは<b>指している場所で意味が変わる</b>。
     *
     * <p>タイルの上なら列を送り、それ以外（＝車が写っているところ）なら寄り引き。
     * どちらか一方に固めると、もう片方をやりたいときに手立てが無くなる。</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY < rowTop) {
            CarChaseCamera.zoom(delta);
            return true;
        }
        int max = Math.max(0, tiles.size() - visibleTiles);
        // 横一列なので、手前へ回したら右へ送る
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            layout();
        }
        return true;
    }

    /**
     * 何も無いところをドラッグしたら、車のまわりを回す。
     *
     * <p>先にウィジェットへ渡すこと。スライダーをつまんだ指がカメラも動かしては困る。
     * ウィジェットの上で押していなければ {@code super} は false を返す。</p>
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        CarChaseCamera.orbit(dragX, dragY);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // renderBackground は呼ばない。ここでは後ろの車を見せることが目的なので、
        // 画面を暗くしてしまうと肝心のものが見えなくなる

        // 装着中かどうかは車から読む。押した結果が返ってきて初めてここが変わる
        CarEntity car = CarClientDriver.driving();
        ResourceLocation fitted = car == null ? null : car.getFitment().getPart(slot);
        for (PartTile tile : tiles) {
            tile.update(car != null, sameId(tile.entry.id(), fitted));
        }
        for (LookSlider slider : sliders) {
            slider.active = car != null;
            // つまんでいる間に入れ直すと指の下で値が飛ぶ。離しているときだけ車から取り直す
            if (!isDragging()) {
                slider.syncFromCar();
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, TEXT_LEFT, TITLE_Y, 0xFFFFFFFF);
        graphics.drawString(font, slot.displayName(), TEXT_LEFT, TITLE_Y + 12, ScreenStyle.ACCENT);
        // 運転していないと換装できない。理由を出さないと、押せないタイルの意味が分からない
        graphics.drawString(font, Component.translatable(car == null
                        ? "screen.kurumamod.part.need_driving"
                        : "screen.kurumamod.part.hint"),
                TEXT_LEFT, TITLE_Y + 26, ScreenStyle.HINT);

        renderScrollHints(graphics);
    }

    /** 列の左右に、まだ先があることを出す。<b>出さないと 1 画面に収まっていると読める。</b> */
    private void renderScrollHints(GuiGraphics graphics) {
        int centerY = rowTop + TILE_RAISE + (TILE_HEIGHT - LABEL_HEIGHT) / 2;
        if (scroll > 0) {
            graphics.drawCenteredString(font, "‹", rowLeft - SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
        }
        if (scroll + visibleTiles < tiles.size()) {
            int right = rowLeft + visibleTiles * (TILE_WIDTH + TILE_GAP) - TILE_GAP;
            graphics.drawCenteredString(font, "›", right + SIDE_MARGIN / 2, centerY, ScreenStyle.LABEL);
        }
    }

    private static boolean sameId(@Nullable ResourceLocation a, @Nullable ResourceLocation b) {
        return a == null ? b == null : a.equals(b);
    }

    // ------------------------------------------------------------------
    // 車との読み書き
    // ------------------------------------------------------------------

    private PartFitment fitment() {
        CarEntity car = CarClientDriver.driving();
        return car == null ? PartFitment.EMPTY : car.getFitment();
    }

    /** その部品を履いた状態。<b>角度もオフセットも持ち越す</b>（どちらも車の設定なので）。 */
    private PartFitment.Setting settingWithPart(@Nullable ResourceLocation partId) {
        return fitment().withPart(slot, partId).get(slot);
    }

    /**
     * 調整値を手元の車へ入れる。<b>調整値は先に反映してよい。</b>
     *
     * <p>部品と違ってサーバーに弾かれることが無く、範囲へ丸められるだけなので、
     * 手元で先に出しても食い違わない。往復を待つとスライダーが指に付いてこない。</p>
     */
    private void apply(Adjustment adjustment, @Nullable Double value) {
        CarEntity car = CarClientDriver.driving();
        if (car != null) {
            car.setFitment(adjustment.apply(car.getFitment(), slot, value));
        }
    }

    /** いまの状態をサーバーへ送る。スライダーを離したときと、画面を閉じるとき。 */
    private void sendCurrent() {
        CarEntity car = CarClientDriver.driving();
        if (car != null) {
            CarClientDriver.requestPart(slot, car.getFitment().get(slot));
        }
    }

    @Override
    public void removed() {
        // 離さずに Esc を押されても取りこぼさないよう、閉じるときにも送る
        sendCurrent();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** セッティング画面と揃える。履かせた結果をその場で見られるように。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 一覧の 1 件。
     *
     * @param id    部品の id。<b>null が「純正」</b>（何も履いていない状態）
     * @param label 画面に出す名前
     * @param pack  どのカーパックのものか。同梱のぶんは null（出さない）
     */
    private record Entry(@Nullable ResourceLocation id, Component label, @Nullable String pack) {
    }

    /**
     * 手で変えられる見た目の値。
     *
     * <p><b>どれも「上書き」であって、書かなければ部品（カーパック）の指定に従う。</b>
     * 範囲は {@link PartFitment} が持っている定数をそのまま使う——画面とサーバーが
     * 別々の数字を持つと、改造クライアントが桁違いの値を他人の画面へ出せる。</p>
     */
    private enum Adjustment {

        /** キャンバー角 [度]。負で「上が内側」。 */
        CAMBER(PartFitment.CAMBER_MIN, PartFitment.CAMBER_MAX, 0.5, 1.0, "camber", "%.1f") {
            @Override
            double packDefault(CarModel model, PartFitment fitment) {
                return CarPartModel.defaultCamber(model, fitment);
            }

            @Override
            @Nullable
            Double override(PartFitment fitment, PartSlot slot) {
                return fitment.getCamber(slot);
            }

            @Override
            PartFitment apply(PartFitment fitment, PartSlot slot, @Nullable Double value) {
                return fitment.withCamber(slot, value);
            }
        },

        /** ホイールオフセット [mm]。<b>実車と同じ向きで、小さいほど外へ出る。</b> */
        OFFSET(PartFitment.OFFSET_MIN, PartFitment.OFFSET_MAX, 1.0, 1.0, "offset", "%.0f") {
            @Override
            double packDefault(CarModel model, PartFitment fitment) {
                return CarPartModel.defaultOffset(model, fitment);
            }

            @Override
            @Nullable
            Double override(PartFitment fitment, PartSlot slot) {
                return fitment.getOffset(slot);
            }

            @Override
            PartFitment apply(PartFitment fitment, PartSlot slot, @Nullable Double value) {
                return fitment.withOffset(slot, value);
            }
        },

        /**
         * タイヤの太さ [%]。<b>メッシュのままが 100%。</b>
         *
         * <p>ミリで出さないのは、<b>何ミリなのかはメッシュ次第</b>だから。倍率なら、
         * 部品を履き替えても「その部品の作りに対してどれだけ太いか」の意味が変わらない。</p>
         */
        WIDTH(PartFitment.WIDTH_MIN * 100.0, PartFitment.WIDTH_MAX * 100.0, 5.0, 0.01,
                "width", "%.0f") {
            @Override
            double packDefault(CarModel model, PartFitment fitment) {
                return CarPartModel.defaultWidth(model, fitment);
            }

            @Override
            @Nullable
            Double override(PartFitment fitment, PartSlot slot) {
                return fitment.getWidth(slot);
            }

            @Override
            PartFitment apply(PartFitment fitment, PartSlot slot, @Nullable Double value) {
                return fitment.withWidth(slot, value);
            }
        };

        static final Adjustment[] VALUES = values();

        /** 範囲と刻みは<b>表示の単位</b>で書く（度・mm・%）。 */
        private final double min;
        private final double max;
        /** 目盛りの刻み。<b>これより細かくしても見た目では分からず、保存される値が半端になる。</b> */
        private final double step;
        /**
         * 表示の単位 1 つぶんが内部の単位でいくつか。
         *
         * <p>太さだけは画面が % で、持っているのは倍率（100% = 1.0）。<b>換算はここ 1 か所</b>で、
         * 画面の中は最後まで表示の単位で通す。</p>
         */
        private final double unit;
        private final String name;
        private final String format;

        Adjustment(double min, double max, double step, double unit, String name, String format) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.unit = unit;
            this.name = name;
            this.format = format;
        }

        /** 部品（カーパック）が指定している値。<b>内部の単位</b>。 */
        abstract double packDefault(CarModel model, PartFitment fitment);

        /** プレイヤーの上書き。<b>内部の単位</b>。していなければ null。 */
        @Nullable
        abstract Double override(PartFitment fitment, PartSlot slot);

        /** 上書きを入れ替える。<b>内部の単位</b>。null で上書きをやめる。 */
        abstract PartFitment apply(PartFitment fitment, PartSlot slot, @Nullable Double value);

        double toDisplay(double internal) {
            return internal / unit;
        }

        double toInternal(double display) {
            return display * unit;
        }

        String key(String suffix) {
            return "screen.kurumamod.part." + name + "_" + suffix;
        }

        String key() {
            return "screen.kurumamod.part." + name;
        }

        String text(double value) {
            return String.format(format, value);
        }

        double fromSlider(double fraction) {
            return Math.round((min + fraction * (max - min)) / step) * step;
        }

        double toSlider(double value) {
            return (value - min) / (max - min);
        }
    }

    /**
     * 調整値 1 つぶんのスライダー。
     *
     * <p>キャンバーもオフセットも「上書きするか、部品の指定に従うか」という同じ形なので、
     * 1 つで賄って {@link Adjustment} で振り分ける。</p>
     */
    private final class LookSlider extends AbstractSliderButton {

        private final Adjustment adjustment;

        private LookSlider(Adjustment adjustment, int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), 0.0);
            this.adjustment = adjustment;
            syncFromCar();
        }

        /** 車が持っている値を入れ直す。履き替えで既定値が変わったときもここで追いつく。 */
        void syncFromCar() {
            this.value = adjustment.toSlider(effective());
            updateMessage();
        }

        /** いま描かれている値（表示の単位）。上書きがあればそれ、無ければ部品の指定。 */
        private double effective() {
            Double override = adjustment.override(fitment(), slot);
            return override != null ? adjustment.toDisplay(override) : defaultValue();
        }

        /** 部品（カーパック）が指定している値（表示の単位）。 */
        private double defaultValue() {
            CarEntity car = CarClientDriver.driving();
            return car == null ? 0.0 : adjustment.toDisplay(
                    adjustment.packDefault(CarModel.get(car.getCarId()), car.getFitment()));
        }

        @Override
        protected void updateMessage() {
            double current = adjustment.fromSlider(this.value);
            double base = defaultValue();
            // 上書きしているときだけ既定値を添える。いつも出ていると、
            // どちらが効いているのか読み取れない
            setMessage(Math.abs(current - base) < adjustment.step * 0.5
                    ? Component.translatable(adjustment.key(), adjustment.text(current))
                    : Component.translatable(adjustment.key("changed"),
                            adjustment.text(current), adjustment.text(base)));
        }

        @Override
        protected void applyValue() {
            // つまんでいる間はここが毎フレーム呼ばれる。送るのは離したときだけ
            apply(adjustment, adjustment.toInternal(adjustment.fromSlider(this.value)));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            sendCurrent();
        }
    }

    /**
     * パーツ 1 つぶんのタイル。
     *
     * <p><b>装着中は色だけでなく高さも変える。</b>色だけだと、色覚の差や小さい画面で
     * どれが付いているのか分からなくなる。</p>
     */
    private final class PartTile extends AbstractWidget {

        private final Entry entry;
        private boolean fitted;

        private PartTile(Entry entry) {
            super(0, 0, TILE_WIDTH, TILE_HEIGHT + TILE_RAISE, entry.label());
            this.entry = entry;
        }

        /** 毎フレーム車から読んだ状態を入れる。 */
        void update(boolean driving, boolean fitted) {
            this.fitted = fitted;
            // 履いているものは押せない。押しても何も起きないタイルを残すと、
            // 効かなかったのか同じものだったのか区別がつかない
            this.active = driving && !fitted;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            CarClientDriver.requestPart(slot, settingWithPart(entry.id()));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHovered() && active;
            int left = getX();
            int right = left + TILE_WIDTH;
            int bottom = getY() + TILE_HEIGHT + TILE_RAISE;
            // 装着中だけ上へ伸ばす。下端は揃えたままにするので、列の底が波打たない
            int top = fitted ? getY() : getY() + TILE_RAISE;
            int labelTop = bottom - LABEL_HEIGHT;

            // 箱の描き方はセッティング画面のタブと共通（ScreenStyle）
            ScreenStyle.tileBox(graphics, left, top, TILE_WIDTH, bottom - top,
                    fitted, hovered, LABEL_HEIGHT);

            renderWheelIcon(graphics, (left + right) / 2.0F, (top + labelTop) / 2.0F);

            // 図形を積んだら、文字を描く前に必ず流すこと。溜めたままだと文字を覆う
            GaugePainter.flush(graphics);

            int textColor = fitted ? ScreenStyle.LABEL_ON_ACCENT : ScreenStyle.LABEL;
            String name = font.plainSubstrByWidth(entry.label().getString(), TILE_WIDTH - 8);
            graphics.drawString(font, name, (left + right - font.width(name)) / 2,
                    labelTop + 4, textColor, false);
            if (entry.pack() != null) {
                // どのカーパックのものか。名前の上に小さく置く
                String pack = font.plainSubstrByWidth(entry.pack(), TILE_WIDTH - 8);
                graphics.drawString(font, pack, (left + right - font.width(pack)) / 2,
                        labelTop - 11, fitted ? ScreenStyle.LABEL : ScreenStyle.SUB, false);
            }
        }

        /**
         * ホイールの絵。<b>アイコンの絵を用意せずに済ませる。</b>
         *
         * <p>メーターと同じ {@link GaugePainter}（四角形を積んで円弧を描く）で組む。
         * テクスチャを足すと、カーパックが部品を増やすたびに絵も要ることになる。</p>
         */
        private void renderWheelIcon(GuiGraphics graphics, float centerX, float centerY) {
            float radius = 14.0F;
            int rim = fitted ? COLOR_RIM_FITTED : COLOR_RIM;
            GaugePainter.arc(graphics, centerX, centerY, radius * 0.72F, radius, 0.0F, 360.0F, COLOR_TIRE);
            GaugePainter.arc(graphics, centerX, centerY, 0.0F, radius * 0.72F, 0.0F, 360.0F, 0xFF3C3C3C);
            // スポーク 5 本。本数で見分けさせるものではないので、あくまで「ホイールらしさ」だけ
            for (int i = 0; i < 5; i++) {
                GaugePainter.radialBar(graphics, centerX, centerY,
                        radius * 0.18F, radius * 0.66F, i * 72.0F, 2.6F, rim);
            }
            GaugePainter.arc(graphics, centerX, centerY, 0.0F, radius * 0.22F, 0.0F, 360.0F, rim);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
