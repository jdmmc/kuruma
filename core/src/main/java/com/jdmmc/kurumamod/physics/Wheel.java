package com.jdmmc.kurumamod.physics;

/**
 * 車輪の識別。各輪ごとの値は {@code ordinal()} を添字にした配列で持つ。
 */
public enum Wheel {

    FRONT_LEFT(true, true),
    FRONT_RIGHT(true, false),
    REAR_LEFT(false, true),
    REAR_RIGHT(false, false);

    /** {@code values()} は呼ぶたびに配列を複製するので、毎ティック回す用途ではこちらを使う。 */
    public static final Wheel[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final boolean front;
    private final boolean left;

    Wheel(boolean front, boolean left) {
        this.front = front;
        this.left = left;
    }

    public boolean isFront() {
        return front;
    }

    public boolean isLeft() {
        return left;
    }
}
