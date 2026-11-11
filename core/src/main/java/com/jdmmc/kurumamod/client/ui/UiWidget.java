package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

/**
 * 画面の部品 1 つ。バニラの {@code AbstractWidget} の代わり。
 *
 * <p>位置・大きさ・文字・{@link #active}・{@link #visible}・説明（{@link #setTooltip}）は core が持ち、
 * 版ごとの画面（{@code McUiScreen}）が描く前とクリック・キーを渡す前に、対になるバニラの部品へ写す。
 * 名前はバニラに揃えてある。</p>
 */
public abstract class UiWidget {

    private int x;
    private int y;
    private int width;
    private int height;
    private RaceText message;
    private RaceText tooltip;
    /** 押せるか（灰色にする）。 */
    public boolean active = true;
    /** 出すか。隠した部品は描かず、押せない。 */
    public boolean visible = true;
    private boolean hovered;
    /** 版の画面が持つ、対になるバニラの部品。core からは中身を見ない。 */
    private Object peer;

    protected UiWidget(int x, int y, int width, int height, RaceText message) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.message = message;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public RaceText getMessage() {
        return message;
    }

    public void setMessage(RaceText message) {
        this.message = message;
    }

    public void setTooltip(UiTooltip tooltip) {
        this.tooltip = tooltip == null ? null : tooltip.text();
    }

    /** 説明の文面。無ければ null。 */
    public RaceText tooltip() {
        return tooltip;
    }

    /** カーソルが乗っているか（描く直前に版の画面が決める）。 */
    public boolean isHovered() {
        return hovered;
    }

    public void setHovered(boolean hovered) {
        this.hovered = hovered;
    }

    /** バニラと同じ: 押せて、出ていて、範囲の中。 */
    public boolean isMouseOver(double mouseX, double mouseY) {
        return active && visible && mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    public Object peer() {
        return peer;
    }

    public void setPeer(Object peer) {
        this.peer = peer;
    }
}
