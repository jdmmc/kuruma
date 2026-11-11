package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * 画面 1 つ。バニラの {@code Screen} の代わり。<b>中身（並べ方・描き方・押したときの動き）だけを持つ。</b>
 *
 * <p>版ごとの {@code McUiScreen}（バニラの {@code Screen} の子）が 1 つずつ包み、大きさが決まるたびに
 * {@link #init} を呼び、描画・ティック・クリック・キー・ホイールを渡す。名前と呼ばれ方はバニラに揃えてある
 * （{@code super.mouseClicked} などはバニラの既定の動き＝部品へ渡す）。</p>
 */
public abstract class UiScreen {

    /** 版の画面。{@link UiScreen} からバニラの既定の動きを呼ぶ口。 */
    public interface Host {
        void addWidget(UiWidget widget);

        void removeWidget(UiWidget widget);

        void setFocused(UiWidget widget);

        UiWidget getFocused();

        void setInitialFocus(UiWidget widget);

        /** 閉じる（バニラの {@code onClose}）。 */
        void close();

        /** 部品を描く（バニラの {@code super.render}）。 */
        void renderWidgets(UiGraphics graphics, int mouseX, int mouseY, float partialTick);

        /** 背景。ワールドの中では半透明の暗幕（ぼかし無し）、外では普通の背景。 */
        void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick);

        boolean mouseClicked(double mouseX, double mouseY, int button);

        boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY);

        boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY);

        boolean keyPressed(UiKey key);

        /** 部品を全部消して {@code init} をやり直す（バニラの {@code rebuildWidgets}）。 */
        void rebuild();

        /** ワールドの中か（背景を描き分ける）。 */
        boolean inWorld();

        /** 半透明の暗幕（ぼかし無し。1.20.1 の {@code renderBackground} と同じ）。 */
        void renderTransparentBackground(UiGraphics graphics);

        /** いま押したまま動かしているか（バニラの {@code isDragging}）。 */
        boolean isDragging();
    }

    protected final RaceText title;
    public int width;
    public int height;
    protected UiFont font;
    private Host host;

    protected UiScreen(RaceText title) {
        this.title = title;
    }

    public RaceText getTitle() {
        return title;
    }

    /** 版の画面が、大きさが決まるたびに呼ぶ（部品は版の側で消してから）。 */
    public final void attach(Host host, int width, int height, UiFont font) {
        this.host = host;
        this.width = width;
        this.height = height;
        this.font = font;
        init();
    }

    protected void init() {
    }

    public void tick() {
    }

    /** 部品と、部品より上に重ねるもの。{@code super.render} で部品を描く。 */
    public void render(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        host.renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    /** 背景と、部品より下に敷くもの（表・文字）。{@code super.renderBackground} で背景を描く。 */
    public void renderBackground(UiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        host.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    /** @param button 0 が左 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return host.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return host.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public boolean keyPressed(UiKey key) {
        return host.keyPressed(key);
    }

    /** 押したまま動かした（既定は部品へ渡す）。 */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return host.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public void onClose() {
        host.close();
    }

    /** 閉じたあと（ほかの画面へ移ったときも）。 */
    public void removed() {
    }

    public boolean isPauseScreen() {
        return true;
    }

    protected <T extends UiWidget> T addRenderableWidget(T widget) {
        host.addWidget(widget);
        return widget;
    }

    protected void removeWidget(UiWidget widget) {
        host.removeWidget(widget);
    }

    protected void setFocused(UiWidget widget) {
        host.setFocused(widget);
    }

    protected UiWidget getFocused() {
        return host.getFocused();
    }

    protected void setInitialFocus(UiWidget widget) {
        host.setInitialFocus(widget);
    }

    /** 部品を作り直す。<b>クリックの伝播中には呼ばないこと</b>（部品の一覧を入れ替えることになる）。 */
    protected void rebuildWidgets() {
        host.rebuild();
    }

    protected boolean isDragging() {
        return host.isDragging();
    }

    /** 半透明の暗幕（ぼかし無し）。走りながら見る画面で、後ろのゲームを見せたまま文字を読めるように。 */
    protected void renderTransparentBackground(UiGraphics graphics) {
        host.renderTransparentBackground(graphics);
    }

    /** ワールドの中か。外（タイトル画面から開いた）なら後ろに何も無いので、ふつうの背景を描く。 */
    protected boolean inWorld() {
        return host.inWorld();
    }
}
