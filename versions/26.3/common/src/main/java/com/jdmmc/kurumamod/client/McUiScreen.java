package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.client.ui.UiButton;
import com.jdmmc.kurumamod.client.ui.UiEditBox;
import com.jdmmc.kurumamod.client.ui.UiGraphics;
import com.jdmmc.kurumamod.client.ui.UiKey;
import com.jdmmc.kurumamod.client.ui.UiPressable;
import com.jdmmc.kurumamod.client.ui.UiScreen;
import com.jdmmc.kurumamod.client.ui.UiWidget;
import com.jdmmc.kurumamod.race.RaceText;
import com.jdmmc.kurumamod.race.RaceTexts;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * core の {@link UiScreen}（レースの K の画面）を包むバニラの画面。
 *
 * <p>core の部品（{@link UiWidget}）1 つにつき、バニラの部品を 1 つ持つ。位置・文字・{@code active}・
 * {@code visible}・説明は、描く前とクリック・キー・ホイールを渡す前後に core から写す（{@link #sync}）。</p>
 */
public final class McUiScreen extends Screen {

    private final UiScreen model;
    private final Map<UiWidget, AbstractWidget> peers = new IdentityHashMap<>();
    private final Map<AbstractWidget, UiWidget> owners = new IdentityHashMap<>();
    private final Host host = new Host();
    /** いま渡しているクリックとキー（core が既定の動きを呼んだとき、そのまま返す）。 */
    private MouseButtonEvent clickEvent;
    private boolean clickDouble;
    private KeyEvent keyEvent;

    public McUiScreen(UiScreen model) {
        super(RaceTexts.of(model.getTitle()));
        this.model = model;
    }

    public UiScreen model() {
        return model;
    }

    @Override
    protected void init() {
        // Screen#init(Minecraft, int, int) が部品を消してから呼ぶ（大きさが変わったときも）
        peers.clear();
        owners.clear();
        model.attach(host, width, height, McUiFont.INSTANCE);
        sync();
    }

    @Override
    public void tick() {
        model.tick();
        sync();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        sync();
        model.render(new McUiGraphics(graphics), mouseX, mouseY, partialTick);
    }

    /** 26.3 の画面は先に背景の段を集めてから部品を集め、描いた順で重なる。表をボタンより下に置くのはここ。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        model.renderBackground(new McUiGraphics(graphics), mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        sync();
        clickEvent = event;
        clickDouble = doubleClick;
        // core は GLFW の番号（左 0・右 1・中 2）で受ける。<b>26.3 は SDL3 で左が 1</b>
        int button = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? 0
                : event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? 1
                : event.button() == InputConstants.MOUSE_BUTTON_MIDDLE ? 2 : event.button();
        boolean used = model.mouseClicked(event.x(), event.y(), button);
        sync();
        return used;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        sync();
        boolean used = model.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        sync();
        return used;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        sync();
        keyEvent = event;
        // Enter は isConfirmation() を使わない（スペースも含むので、名前に空白を打てなくなる）
        boolean used = model.keyPressed(new UiKey(event.key(), event.keycode(), event.modifiers(),
                event.isEscape(),
                event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER,
                KurumaKeys.OPEN_RACE.matches(event),
                ScreenStyle.isDrivingKey(event)));
        sync();
        return used;
    }

    @Override
    public void onClose() {
        model.onClose();
    }

    @Override
    public void removed() {
        model.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return model.isPauseScreen();
    }

    /** core の部品の値をバニラの部品へ写す。 */
    private void sync() {
        for (Map.Entry<UiWidget, AbstractWidget> entry : peers.entrySet()) {
            UiWidget widget = entry.getKey();
            AbstractWidget peer = entry.getValue();
            peer.setX(widget.getX());
            peer.setY(widget.getY());
            peer.setWidth(widget.getWidth());
            peer.setHeight(widget.getHeight());
            peer.active = widget.active;
            peer.visible = widget.visible;
            if (peer instanceof Linked linked) {
                linked.state().apply(widget, peer);
            }
        }
    }

    private AbstractWidget peerOf(UiWidget widget) {
        AbstractWidget peer;
        if (widget instanceof UiButton button) {
            peer = new WidgetButton(button);
        } else if (widget instanceof UiPressable pressable) {
            peer = new WidgetPressable(pressable);
        } else if (widget instanceof UiEditBox box) {
            peer = editBox(box);
        } else {
            throw new IllegalArgumentException("この部品は画面に置けません: " + widget);
        }
        return peer;
    }

    private EditBox editBox(UiEditBox box) {
        LinkedEditBox edit = new LinkedEditBox(font, box);
        if (box.hint() != null) {
            edit.setHint(RaceTexts.of(box.hint()));
        }
        edit.setMaxLength(box.maxLength());
        edit.setValue(box.getValue());
        edit.setResponder(box::changed);
        box.attach(new UiEditBox.Peer() {
            @Override
            public String getValue() {
                return edit.getValue();
            }

            @Override
            public void setValue(String value) {
                edit.setValue(value);
            }

            @Override
            public void setMaxLength(int length) {
                edit.setMaxLength(length);
            }

            @Override
            public boolean isFocused() {
                return edit.isFocused();
            }
        });
        return edit;
    }

    /** 文字と説明は、変わったときだけ作り直す（毎フレーム Component を作らない）。 */
    private static final class PeerState {
        private RaceText message;
        private RaceText tooltip;

        void apply(UiWidget widget, AbstractWidget peer) {
            if (widget.getMessage() != message) {
                message = widget.getMessage();
                peer.setMessage(message == null ? Component.empty() : RaceTexts.of(message));
            }
            if (widget.tooltip() != tooltip) {
                tooltip = widget.tooltip();
                peer.setTooltip(tooltip == null ? null : Tooltip.create(RaceTexts.of(tooltip)));
            }
        }
    }

    private interface Linked {
        PeerState state();
    }

    /** バニラの見た目のボタン。文字だけ core が描くこともある（ドロップダウンの「▼」）。 */
    static final class WidgetButton extends Button.Plain implements Linked {

        private final UiButton widget;
        private final PeerState state = new PeerState();

        WidgetButton(UiButton widget) {
            super(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), Component.empty(),
                    button -> widget.onPress(), DEFAULT_NARRATION);
            this.widget = widget;
        }

        @Override
        public PeerState state() {
            return state;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (widget.customLabel()) {
                extractDefaultSprite(graphics);
                widget.renderLabel(new McUiGraphics(graphics, this), McUiFont.INSTANCE,
                        active ? 0xFFFFFFFF : 0xFFA0A0A0);
            } else {
                super.extractContents(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    /** core が描く押せる部品（タブ・入れ切り・札・一覧の行）。 */
    private static final class WidgetPressable extends AbstractButton implements Linked {

        private final UiPressable widget;
        private final PeerState state = new PeerState();

        WidgetPressable(UiPressable widget) {
            super(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), Component.empty());
            this.widget = widget;
        }

        @Override
        public PeerState state() {
            return state;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            widget.onPress();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            widget.setHovered(isHovered());
            widget.renderWidget(new McUiGraphics(graphics, this), mouseX, mouseY, partialTick);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static final class LinkedEditBox extends EditBox implements Linked {

        private final PeerState state = new PeerState();

        LinkedEditBox(Font font, UiEditBox box) {
            super(font, box.getX(), box.getY(), box.getWidth(), box.getHeight(),
                    box.getMessage() == null ? Component.empty() : RaceTexts.of(box.getMessage()));
        }

        @Override
        public PeerState state() {
            return state;
        }
    }

    // ------------------------------------------------------------------
    // core から呼ばれる口（バニラの既定の動き）
    // ------------------------------------------------------------------

    private boolean superMouseClicked() {
        return clickEvent != null && super.mouseClicked(clickEvent, clickDouble);
    }

    private boolean superMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean superKeyPressed() {
        return keyEvent != null && super.keyPressed(keyEvent);
    }

    private void superRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void superRenderBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft == null || minecraft.level == null) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        } else {
            // 26.3 では画面を開いているあいだの字幕を背景の段で描く（Screen#extractBackground）。止めると字幕が消える
            minecraft.gui.hud.extractDeferredSubtitles();
            // 1.20.1 と同じ半透明の暗幕（ぼかし無し）。走りながら見る画面なので
            extractTransparentBackground(graphics);
        }
    }

    private void superClose() {
        super.onClose();
    }

    private final class Host implements UiScreen.Host {

        @Override
        public void addWidget(UiWidget widget) {
            AbstractWidget peer = peerOf(widget);
            peers.put(widget, peer);
            owners.put(peer, widget);
            addRenderableWidget(peer);
            sync();
        }

        @Override
        public void removeWidget(UiWidget widget) {
            AbstractWidget peer = peers.remove(widget);
            if (peer != null) {
                owners.remove(peer);
                McUiScreen.this.removeWidget(peer);
            }
        }

        @Override
        public void setFocused(UiWidget widget) {
            McUiScreen.this.setFocused(widget == null ? null : peers.get(widget));
        }

        @Override
        public UiWidget getFocused() {
            GuiEventListener focused = McUiScreen.this.getFocused();
            return focused instanceof AbstractWidget peer ? owners.get(peer) : null;
        }

        @Override
        public void setInitialFocus(UiWidget widget) {
            AbstractWidget peer = peers.get(widget);
            if (peer != null) {
                McUiScreen.this.setInitialFocus(peer);
            }
        }

        @Override
        public void close() {
            superClose();
        }

        @Override
        public void renderWidgets(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            superRender(((McUiGraphics) graphics).graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            superRenderBackground(((McUiGraphics) graphics).graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return superMouseClicked();
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return superMouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        @Override
        public boolean keyPressed(UiKey key) {
            return superKeyPressed();
        }
    }
}
