package com.jdmmc.kurumamod.client.ui;

import com.jdmmc.kurumamod.race.RaceText;

import java.util.function.Consumer;

/**
 * 文字の入力欄。バニラの {@code EditBox} の代わり。
 *
 * <p>打った文字はバニラの欄が持ち、版の画面が {@link Peer} でつなぐ。つなぐ前に置いた値・上限・薄い字は、
 * つないだときに写す。</p>
 */
public class UiEditBox extends UiWidget {

    /** 版の画面が持つバニラの欄。 */
    public interface Peer {
        String getValue();

        void setValue(String value);

        void setMaxLength(int length);

        boolean isFocused();
    }

    private String value = "";
    private int maxLength = 32;
    private RaceText hint;
    private Consumer<String> responder;
    private Peer editPeer;

    public UiEditBox(UiFont font, int x, int y, int width, int height, RaceText message) {
        super(x, y, width, height, message);
    }

    public String getValue() {
        return editPeer != null ? editPeer.getValue() : value;
    }

    public void setValue(String value) {
        this.value = value;
        if (editPeer != null) {
            editPeer.setValue(value);
        }
    }

    public int maxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
        if (editPeer != null) {
            editPeer.setMaxLength(maxLength);
        }
    }

    public RaceText hint() {
        return hint;
    }

    public void setHint(RaceText hint) {
        this.hint = hint;
    }

    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
    }

    /** バニラの欄で文字が変わった（版の画面が呼ぶ）。 */
    public void changed(String text) {
        value = text;
        if (responder != null) {
            responder.accept(text);
        }
    }

    public boolean isFocused() {
        return editPeer != null && editPeer.isFocused();
    }

    public void attach(Peer peer) {
        this.editPeer = peer;
    }
}
