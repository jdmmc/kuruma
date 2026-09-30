package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.Kurumamod;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * ヘッドライトの調整用（<b>デバッグ専用</b>）。Ctrl+D で開く。
 *
 * <p>光の見え方は走らせて見ないと決められないので、既定値を詰めるためだけに置いてある。
 * <b>値は保存しない</b>（再起動で既定に戻る）。決まった値は「ログに出す」で書き出し、
 * {@link Values} の既定値へ反映する。キー割り当てを作らないのは、プレイヤー向けの機能では
 * ないため（一覧に並ぶと触られる）。Ctrl+D はバニラでは使われていない。</p>
 *
 * <p>ゲームは止めず、背景も暗くしない。光を見ながら動かす画面なので。</p>
 */
public final class LightDebugScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 調整できる値。読む側（{@link CarLights} / {@link HeadlightVolume}）はここを見る。 */
    public static final class Values {
        /** 光軸の下向き [度]。 */
        public static final double AIM_DOWN = 0.9;
        /** オートレベライザーの効き（0 で無し、1 で沈み込みを全部打ち消す）。 */
        public static final double LEVELING = 0.85;
        /** 晴れのときの霧の濃さ [1/m]。雨と雷はこれに足される。 */
        public static final double FOG = 0.025;
        /** 照らされた路面を何倍まで明るくするか。 */
        public static final double SURFACE_GAIN = 5.0;
        /** 霧の中の光線の明るさ。 */
        public static final double VOLUME_GAIN = 120.0;
        /** 光の中の煙の光り方。 */
        public static final double SMOKE_GAIN = 4.0;

        public static double aimDown = AIM_DOWN;
        public static double leveling = LEVELING;
        public static double fog = FOG;
        public static double surfaceGain = SURFACE_GAIN;
        public static double volumeGain = VOLUME_GAIN;
        public static double smokeGain = SMOKE_GAIN;

        private Values() {
        }

        static void reset() {
            aimDown = AIM_DOWN;
            leveling = LEVELING;
            fog = FOG;
            surfaceGain = SURFACE_GAIN;
            volumeGain = VOLUME_GAIN;
            smokeGain = SMOKE_GAIN;
        }

        static String describe() {
            return String.format(Locale.ROOT,
                    "aimDown=%.2f leveling=%.2f fog=%.4f surfaceGain=%.2f volumeGain=%.1f smokeGain=%.2f",
                    aimDown, leveling, fog, surfaceGain, volumeGain, smokeGain);
        }
    }

    /** 1 行ぶん。表示の範囲・刻み・読み書き。 */
    private record Row(String key, double min, double max, double step, String format,
                       DoubleSupplier get, DoubleConsumer set, double defaultValue) {
    }

    private static final Row[] ROWS = {
            new Row("aim_down", 0.0, 4.0, 0.05, "%.2f°",
                    () -> Values.aimDown, v -> Values.aimDown = v, Values.AIM_DOWN),
            new Row("leveling", 0.0, 1.0, 0.05, "%.2f",
                    () -> Values.leveling, v -> Values.leveling = v, Values.LEVELING),
            new Row("fog", 0.0, 0.15, 0.0025, "%.4f",
                    () -> Values.fog, v -> Values.fog = v, Values.FOG),
            new Row("surface_gain", 0.0, 12.0, 0.25, "%.2f",
                    () -> Values.surfaceGain, v -> Values.surfaceGain = v, Values.SURFACE_GAIN),
            new Row("volume_gain", 0.0, 400.0, 5.0, "%.0f",
                    () -> Values.volumeGain, v -> Values.volumeGain = v, Values.VOLUME_GAIN),
            new Row("smoke_gain", 0.0, 10.0, 0.25, "%.2f",
                    () -> Values.smokeGain, v -> Values.smokeGain = v, Values.SMOKE_GAIN),
    };

    private static final int ROW_HEIGHT = 24;
    private static final int SLIDER_WIDTH = 200;
    private static final int LABEL_WIDTH = 130;
    private static final int RESET_WIDTH = 20;

    public LightDebugScreen() {
        super(Component.translatable("debug.kurumamod.lights.title"));
    }

    @Override
    protected void init() {
        int total = LABEL_WIDTH + SLIDER_WIDTH + 4 + RESET_WIDTH;
        int left = (width - total) / 2;
        int top = 40;
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            int y = top + i * ROW_HEIGHT;
            Slider slider = new Slider(left + LABEL_WIDTH, y, SLIDER_WIDTH, 20, row);
            addRenderableWidget(slider);
            addRenderableWidget(Button.builder(Component.literal("R"), button -> {
                        row.set().accept(row.defaultValue());
                        slider.sync();
                    })
                    .bounds(left + LABEL_WIDTH + SLIDER_WIDTH + 4, y, RESET_WIDTH, 20)
                    .build());
        }
        int bottom = top + ROWS.length * ROW_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.translatable("debug.kurumamod.lights.reset_all"), button -> {
                    Values.reset();
                    rebuildPending = true;
                })
                .bounds(width / 2 - 154, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("debug.kurumamod.lights.log"), button -> {
                    LOGGER.info("[ライト調整] {}", Values.describe());
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.displayClientMessage(Component.literal(Values.describe()), false);
                    }
                })
                .bounds(width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 54, bottom, 100, 20).build());
    }

    /**
     * 「全部戻す」でスライダーを作り直すための旗。<b>クリックの伝播中に
     * {@code rebuildWidgets()} を呼んではいけない</b>（ウィジェット一覧を入れ替えることになる）。
     */
    private boolean rebuildPending;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
        }
        // renderBackground は呼ばない。光を見ながら動かす画面なので
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        int total = LABEL_WIDTH + SLIDER_WIDTH + 4 + RESET_WIDTH;
        int left = (width - total) / 2;
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            boolean changed = Math.abs(row.get().getAsDouble() - row.defaultValue()) > row.step() * 0.5;
            graphics.drawString(font, Component.translatable("debug.kurumamod.lights." + row.key()),
                    left, 40 + i * ROW_HEIGHT + 6, changed ? 0xFFFF55 : 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // 閉じたときにも書き出しておく。詰めた値をあとから拾えるように
        LOGGER.info("[ライト調整] {}", Values.describe());
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class Slider extends AbstractSliderButton {
        private final Row row;

        Slider(int x, int y, int width, int height, Row row) {
            super(x, y, width, height, Component.empty(), toSlider(row, row.get().getAsDouble()));
            this.row = row;
            updateMessage();
        }

        private static double toSlider(Row row, double v) {
            return Mth.clamp((v - row.min()) / (row.max() - row.min()), 0.0, 1.0);
        }

        void sync() {
            value = toSlider(row, row.get().getAsDouble());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, row.format(), row.get().getAsDouble())));
        }

        @Override
        protected void applyValue() {
            double raw = row.min() + value * (row.max() - row.min());
            double stepped = Math.round(raw / row.step()) * row.step();
            row.set().accept(Mth.clamp(stepped, row.min(), row.max()));
        }
    }

    /** Ctrl+D で開く。 */
    @Mod.EventBusSubscriber(modid = Kurumamod.MODID, value = Dist.CLIENT)
    public static final class Opener {
        private Opener() {
        }

        @SubscribeEvent
        public static void onKey(InputEvent.Key event) {
            if (event.getAction() != GLFW.GLFW_PRESS || event.getKey() != GLFW.GLFW_KEY_D
                    || (event.getModifiers() & GLFW.GLFW_MOD_CONTROL) == 0) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && minecraft.player != null) {
                minecraft.setScreen(new LightDebugScreen());
            }
        }
    }
}
