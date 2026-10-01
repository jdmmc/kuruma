package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Locale;

/**
 * ヘッドライトの調整画面。Ctrl+L（{@link KurumaKeys#OPEN_HEADLIGHT_SETTINGS}）で開く。
 *
 * <p>光の見え方は走らせて見ないと決められないので、<b>ゲームは止めず、背景も暗くしない</b>
 * （{@code CarTuningScreen} と同じ）。値は {@link ClientConfig} に入り、動かすたびに
 * {@link ClientConfig#refresh()} で効かせ、ファイルへ書くのは閉じるときだけ
 * （{@code KurumaMenuScreen} と同じ流儀）。</p>
 *
 * <p>車の諸元ではなく<b>画面の好み</b>なので、サーバーへは送らない。他の人から見た
 * 自分のライトも、その人の設定で描かれる。</p>
 */
public final class HeadlightScreen extends Screen {

    /** 1 行ぶん。表示の範囲・刻み・書式と、読み書きする設定。 */
    private record Row(String key, double step, String format, ForgeConfigSpec.DoubleValue value) {

        double min() {
            return range().getMin();
        }

        double max() {
            return range().getMax();
        }

        double get() {
            return value.get();
        }

        double defaultValue() {
            return value.getDefault();
        }

        @SuppressWarnings("unchecked")
        private ForgeConfigSpec.Range<Double> range() {
            return (ForgeConfigSpec.Range<Double>) ((ForgeConfigSpec.ValueSpec)
                    ClientConfig.SPEC.getSpec().get(value.getPath())).getRange();
        }

        void set(double v) {
            value.set(Mth.clamp(v, min(), max()));
            ClientConfig.refresh();
        }
    }

    private static final Row[] ROWS = {
            new Row("aim", 0.05, "%.2f°", ClientConfig.HEADLIGHT_AIM),
            new Row("leveling", 0.05, "%.2f", ClientConfig.HEADLIGHT_LEVELING),
            new Row("fog", 0.0025, "%.4f", ClientConfig.HEADLIGHT_FOG),
            new Row("surface", 0.25, "%.2f", ClientConfig.HEADLIGHT_SURFACE),
            new Row("beam", 5.0, "%.0f", ClientConfig.HEADLIGHT_BEAM),
            new Row("smoke", 0.25, "%.2f", ClientConfig.HEADLIGHT_SMOKE),
            new Row("cars", 1.0, "%.0f", ClientConfig.HEADLIGHT_CARS),
    };

    private static final int ROW_HEIGHT = 24;
    private static final int TOP = 40;
    private static final int SLIDER_WIDTH = 200;
    private static final int LABEL_WIDTH = 130;
    private static final int RESET_WIDTH = 20;

    /**
     * 「全部戻す」でスライダーを作り直すための旗。<b>クリックの伝播中に
     * {@code rebuildWidgets()} を呼んではいけない</b>（ウィジェット一覧を入れ替えることになる）。
     */
    private boolean rebuildPending;

    public HeadlightScreen() {
        super(Component.translatable("screen.kurumamod.headlight.title"));
    }

    private int left() {
        return (width - (LABEL_WIDTH + SLIDER_WIDTH + 4 + RESET_WIDTH)) / 2;
    }

    private static int rowY(int index) {
        return TOP + index * ROW_HEIGHT;
    }

    @Override
    protected void init() {
        int left = left();
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            int y = rowY(i);
            Slider slider = new Slider(left + LABEL_WIDTH, y, SLIDER_WIDTH, 20, row);
            addRenderableWidget(slider);
            addRenderableWidget(Button.builder(Component.literal("R"), button -> {
                        row.set(row.defaultValue());
                        slider.sync();
                    })
                    .bounds(left + LABEL_WIDTH + SLIDER_WIDTH + 4, y, RESET_WIDTH, 20)
                    .build());
        }
        int bottom = rowY(ROWS.length) + 8;
        addRenderableWidget(Button.builder(Component.translatable("screen.kurumamod.headlight.reset_all"), button -> {
                    for (Row row : ROWS) {
                        row.set(row.defaultValue());
                    }
                    rebuildPending = true;
                })
                .bounds(width / 2 - 104, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 4, bottom, 100, 20).build());
    }


    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
        }
        // renderBackground は呼ばない。光を見ながら動かす画面なので
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        int left = left();
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            // 既定から変えた項目は黄色（調整画面と同じ）
            boolean changed = Math.abs(row.get() - row.defaultValue()) > row.step() * 0.5;
            graphics.drawString(font, Component.translatable("screen.kurumamod.headlight." + row.key()),
                    left, rowY(i) + 6, changed ? 0xFFFF55 : 0xFFFFFF);
        }
        if (HeadlightVolume.ShaderPacks.inUse()) {
            // シェーダーパックの間は光の効果を描かないので、動かしても何も変わらない
            graphics.drawCenteredString(font, Component.translatable("screen.kurumamod.headlight.shader_pack"),
                    width / 2, rowY(ROWS.length) + 36, 0xFFAA55);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // ファイルへ書くのは閉じるときだけ。値は動かすたびに refresh で効いている
        ClientConfig.SPEC.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class Slider extends AbstractSliderButton {
        private final Row row;

        Slider(int x, int y, int width, int height, Row row) {
            super(x, y, width, height, Component.empty(), toSlider(row, row.get()));
            this.row = row;
            updateMessage();
        }

        private static double toSlider(Row row, double v) {
            return Mth.clamp((v - row.min()) / (row.max() - row.min()), 0.0, 1.0);
        }

        void sync() {
            value = toSlider(row, row.get());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // 台数の右端は「無制限」
            if (row.key().equals("cars") && row.get() >= ClientConfig.UNLIMITED_HEADLIGHT_CARS) {
                setMessage(Component.translatable("screen.kurumamod.headlight.cars.unlimited"));
                return;
            }
            setMessage(Component.literal(String.format(Locale.ROOT, row.format(), row.get())));
        }

        @Override
        protected void applyValue() {
            double raw = row.min() + value * (row.max() - row.min());
            row.set(Math.round(raw / row.step()) * row.step());
        }
    }
}
