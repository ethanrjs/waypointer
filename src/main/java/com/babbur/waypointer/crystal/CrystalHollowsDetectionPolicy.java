package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;

public final class CrystalHollowsDetectionPolicy {
    public static final int ARRIVAL_RADIUS = 5;

    private CrystalHollowsDetectionPolicy() {}

    public static boolean shouldScanEntities(boolean detectionEnabled, int delayTicks) {
        return detectionEnabled && delayTicks <= 0;
    }

    public static boolean contradictsSkyblocker(StructureSighting sighting, String area,
                                                double x, double y, double z) {
        if (sighting.confidence() != SightingConfidence.SHARED_REMOTE
                || !SkyblockerStructureProtocol.SOURCE.equals(sighting.source())
                || sighting.structure().sidebarName() == null) return false;
        CrystalHollowsStructure actual = CrystalHollowsSidebar.structureForArea(area);
        if (actual == sighting.structure()
                || actual == null && CrystalHollowsSidebar.zoneForArea(area) == null) return false;
        double dx = x - sighting.x(), dy = y - sighting.y(), dz = z - sighting.z();
        return dx * dx + dy * dy + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS;
    }

    public static boolean allows(WaypointerConfig config, StructureSighting sighting) {
        return sighting.confidence() == SightingConfidence.MANUAL
                || sighting.confidence() == SightingConfidence.COMPASS
                || config.crystalHollowsDetectionEnabled(sighting.structure().id());
    }
}
