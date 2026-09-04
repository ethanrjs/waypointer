package com.babbur.waypointer.crystal;

/** Timing and configuration gates for Crystal Hollows detection passes. */
public final class CrystalHollowsDetectionPolicy {
    private CrystalHollowsDetectionPolicy() {}

    public static boolean shouldScanEntities(boolean detectionEnabled, int delayTicks) {
        return detectionEnabled && delayTicks <= 0;
    }
}
