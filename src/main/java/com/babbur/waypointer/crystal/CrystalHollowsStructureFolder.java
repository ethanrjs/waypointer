package com.babbur.waypointer.crystal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Pure planner for the session-only Structures folder and its generated route groups. */
public final class CrystalHollowsStructureFolder {

    public static final String FOLDER_ID = "crystal_hollows:structures";
    public static final String GROUP_PREFIX = "crystal_hollows:structure:";
    public static final String ZONE_ID = "crystal_hollows";
    public static final int FOLDER_COLOR = 0x55FFFF;

    public record PlannedWaypoint(String name, CrystalHollowsPosition position, int color) {}
    public record PlannedGroup(String id, String structureId, String name,
                               List<PlannedWaypoint> waypoints, int instance,
                               boolean approximate,
                               List<CrystalHollowsStructure> candidates,
                               boolean nucleus) {
        public PlannedGroup {
            waypoints = List.copyOf(waypoints);
            candidates = List.copyOf(candidates);
        }
    }

    private CrystalHollowsStructureFolder() {}

    public static List<PlannedGroup> plan(List<StructureSighting> sightings,
                                          boolean showRough, boolean includeNucleus) {
        Map<CrystalHollowsStructure, Integer> instances =
                new EnumMap<>(CrystalHollowsStructure.class);
        List<PlannedGroup> groups = new ArrayList<>();
        for (StructureSighting sighting : sightings) {
            CrystalHollowsStructure structure = sighting.structure();
            int instance = instances.merge(structure, 1, Integer::sum);
            if (!showRough && sighting.confidence() == SightingConfidence.ROUGH_AREA) continue;
            String suffix = structure.multiInstance() && instance > 1 ? ":" + instance : "";
            String number = structure.multiInstance() && instance > 1 ? " #" + instance : "";
            String label = label(sighting) + number;
            groups.add(new PlannedGroup(GROUP_PREFIX + structure.id() + suffix,
                    structure.id(), label,
                    List.of(new PlannedWaypoint(label, sighting.position(), structure.rgb())),
                    instance, sighting.confidence() == SightingConfidence.ROUGH_AREA,
                    sighting.candidates(), false));
        }
        if (includeNucleus) groups.add(nucleusGroup());
        return List.copyOf(groups);
    }

    private static PlannedGroup nucleusGroup() {
        List<PlannedWaypoint> waypoints = new ArrayList<>();
        waypoints.add(new PlannedWaypoint("Crystal Nucleus",
                new CrystalHollowsPosition(513, 107, 513),
                CrystalHollowsStructure.CRYSTAL_NUCLEUS.rgb()));
        for (CrystalHollowsGeometry.Entrance entrance : CrystalHollowsGeometry.NUCLEUS_ENTRANCES) {
            waypoints.add(new PlannedWaypoint(entrance.displayName(), entrance.position(),
                    CrystalHollowsStructure.CRYSTAL_NUCLEUS.rgb()));
        }
        return new PlannedGroup(GROUP_PREFIX + "nucleus", CrystalHollowsStructure.CRYSTAL_NUCLEUS.id(),
                "Crystal Nucleus", waypoints, 1, false, List.of(), true);
    }

    private static String label(StructureSighting sighting) {
        if (sighting.structure() == CrystalHollowsStructure.WISHING_TARGET) {
            if (sighting.candidates().isEmpty()) return "Compass target: Unknown";
            return "Compass target: " + String.join(" / ", sighting.candidates().stream()
                    .map(CrystalHollowsStructure::displayName).toList());
        }
        String suffix = sighting.confidence() == SightingConfidence.ROUGH_AREA
                ? " (approx.)"
                : "";
        return sighting.structure().displayName() + suffix;
    }
}
