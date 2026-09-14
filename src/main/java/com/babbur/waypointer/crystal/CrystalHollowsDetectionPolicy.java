package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;

/** Timing and configuration gates for Crystal Hollows detection passes. */
public final class CrystalHollowsDetectionPolicy {
    public static final int ARRIVAL_RADIUS = 5;

    private CrystalHollowsDetectionPolicy() {}

    public static boolean shouldScanEntities(boolean detectionEnabled, int delayTicks) {
        return detectionEnabled && delayTicks <= 0;
    }

    /** Missing entities are not proof: only a recognized, contradictory location at the marker is. */
    public static boolean contradictsSkyblocker(StructureSighting sighting, String area,
                                                double x, double y, double z) {
        if (sighting.confidence() != SightingConfidence.SHARED_REMOTE
                || !SkyblockerStructureProtocol.SOURCE.equals(sighting.source())
                || sighting.structure().sidebarName() == null) return false;
        CrystalHollowsStructure actual = CrystalHollowsSidebar.structureForArea(area);
        if (actual == sighting.structure()
                || actual == null && CrystalHollowsSidebar.zoneForArea(area) == null) return false;
        double dx = x - sighting.x(), dy = y - sighting.y(), dz = z - sighting.z();
        // Allow arrival beside the marker; displayed distances truncate fractional blocks.
        return dx * dx + dy * dy + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS;
    }

    /** Explicit coordinates and compass navigation remain available when automatic detection is off. */
    public static boolean allows(WaypointerConfig config, StructureSighting sighting) {
        return sighting.confidence() == SightingConfidence.MANUAL
                || sighting.confidence() == SightingConfidence.COMPASS
                || config.crystalHollowsDetectionEnabled(sighting.structure().id());
    }
}
