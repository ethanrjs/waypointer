package com.babbur.waypointer.render;

public final class RenderCost {
    private static long totalNanos;
    private static long frameStartNanos;
    private static double averageFrameNanos;

    private RenderCost() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void stop(long started) {
        totalNanos += System.nanoTime() - started;
    }

    public static long totalNanos() {
        return totalNanos;
    }

    static void endFrame() {
        long spent = totalNanos - frameStartNanos;
        frameStartNanos = totalNanos;
        averageFrameNanos = averageFrameNanos == 0 ? spent : averageFrameNanos * 0.95 + spent * 0.05;
    }

    public static double averageFrameMillis() {
        return averageFrameNanos / 1e6;
    }
}
