package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;

/** Timing and configuration gates for Crystal Hollows detection passes. */
public final class CrystalHollowsDetectionPolicy {
    private CrystalHollowsDetectionPolicy() {}

    public static boolean shouldScanEntities(boolean detectionEnabled, int delayTicks) {
        return detectionEnabled && delayTicks <= 0;
    }

    /** Explicit coordinates and compass navigation remain available when automatic detection is off. */
    public static boolean allows(WaypointerConfig config, StructureSighting sighting) {
        return sighting.confidence() == SightingConfidence.MANUAL
                || sighting.confidence() == SightingConfidence.COMPASS
                || config.crystalHollowsDetectionEnabled(sighting.structure().id());
    }
}
