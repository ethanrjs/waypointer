package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CrystalHollowsStructureZones {

    public static final String ZONE_PREFIX = "crystal_hollows_structure_";

    public record Definition(String zoneId, String displayName) {}

    private static final Map<String, CrystalHollowsStructure> BY_ZONE = byZone();

    private CrystalHollowsStructureZones() {}

    private static Map<String, CrystalHollowsStructure> byZone() {
        Map<String, CrystalHollowsStructure> out = new LinkedHashMap<>();
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (supports(structure)) out.put(zoneId(structure), structure);
        }
        return Map.copyOf(out);
    }

    public static boolean supports(CrystalHollowsStructure structure) {
        return structure != null
                && structure.kind() != CrystalHollowsStructure.Kind.FIXED
                && structure != CrystalHollowsStructure.WISHING_TARGET
                && !structure.multiInstance();
    }

    public static List<Definition> definitions() {
        List<Definition> out = new ArrayList<>();
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (supports(structure)) {
                out.add(new Definition(zoneId(structure), displayName(structure)));
            }
        }
        return List.copyOf(out);
    }

    public static String zoneId(CrystalHollowsStructure structure) {
        return ZONE_PREFIX + structure.id();
    }

    public static String displayName(CrystalHollowsStructure structure) {
        return structure.displayName();
    }

    public static CrystalHollowsStructure structureFor(String zoneId) {
        return zoneId == null ? null : BY_ZONE.get(zoneId);
    }

    public static boolean isStructureZone(String zoneId) {
        return structureFor(zoneId) != null;
    }

    public static boolean exactLocation(StructureSighting sighting) {
        return sighting != null
                && (sighting.confidence() == SightingConfidence.ENTITY
                    || sighting.confidence() == SightingConfidence.MANUAL);
    }

    public static CrystalHollowsPosition pivot(Collection<StructureSighting> sightings,
                                               CrystalHollowsStructure structure,
                                               CrystalHollowsPosition near) {
        if (sightings == null || structure == null) return null;
        CrystalHollowsPosition best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (StructureSighting sighting : sightings) {
            if (sighting.structure() != structure || !exactLocation(sighting)) continue;
            CrystalHollowsPosition position = sighting.position();
            if (near == null) return position;
            double distance = position.distanceSquared(near);
            if (distance < bestDistance) {
                best = position;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static Waypoint toStructureLocal(Waypoint actual, CrystalHollowsPosition pivot) {
        int[] local = toStructureLocalPrecise(actual.preciseX(), actual.preciseY(), actual.preciseZ(), pivot);
        return actual.withPreciseSixteenths(local[0], local[1], local[2]);
    }

    public static Waypoint toWorld(Waypoint local, CrystalHollowsPosition pivot) {
        return local.withPreciseSixteenths(
                local.preciseX() + pivot.x() * Waypoint.PRECISE_SCALE,
                local.preciseY() + pivot.y() * Waypoint.PRECISE_SCALE,
                local.preciseZ() + pivot.z() * Waypoint.PRECISE_SCALE);
    }

    public static int[] toStructureLocalPrecise(int preciseX, int preciseY, int preciseZ,
                                                CrystalHollowsPosition pivot) {
        return new int[] {
                preciseX - pivot.x() * Waypoint.PRECISE_SCALE,
                preciseY - pivot.y() * Waypoint.PRECISE_SCALE,
                preciseZ - pivot.z() * Waypoint.PRECISE_SCALE };
    }
}
