package com.babbur.waypointer.util;

/**
 * Generic numeric helpers shared across Waypointer packages.
 */
public final class MathUtil {

    private MathUtil() {}

    public static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    public static int clampByte(int value) {
        return clamp(value, 0, 255);
    }
}
