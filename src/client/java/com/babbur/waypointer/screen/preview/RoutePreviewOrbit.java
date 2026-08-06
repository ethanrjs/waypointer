package com.babbur.waypointer.screen.preview;

/** Pause-safe 20-second route-preview orbit. */
public final class RoutePreviewOrbit {

    public static final double START_YAW_DEGREES = 45.0;
    public static final double DEGREES_PER_SECOND = 18.0;

    private long lastNanos = Long.MIN_VALUE;
    private double yawDegrees = START_YAW_DEGREES;
    private boolean wasActive;

    public double update(long nowNanos, boolean active) {
        if (lastNanos != Long.MIN_VALUE && active && wasActive) {
            long elapsed = Math.max(0L, nowNanos - lastNanos);
            yawDegrees = normalize(yawDegrees + elapsed * DEGREES_PER_SECOND / 1_000_000_000.0);
        }
        lastNanos = nowNanos;
        wasActive = active;
        return Math.toRadians(yawDegrees);
    }

    public double yawDegrees() {
        return yawDegrees;
    }

    public static double angleAtSeconds(double seconds) {
        return normalize(START_YAW_DEGREES + Math.max(0.0, seconds) * DEGREES_PER_SECOND);
    }

    private static double normalize(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }
}
