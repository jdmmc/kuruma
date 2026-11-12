package com.jdmmc.kurumamod.util;

/** 数の道具。<b>バニラの {@code net.minecraft.util.Mth} と同じ名前・同じ動き</b>（core は Minecraft を知らないので写した）。 */
public final class Mth {

    public static final float PI = 3.1415927F;
    public static final float TWO_PI = 6.2831855F;
    public static final float DEG_TO_RAD = 0.017453292F;
    public static final float RAD_TO_DEG = 57.295776F;

    private Mth() {
    }

    public static float rotLerp(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }
}
