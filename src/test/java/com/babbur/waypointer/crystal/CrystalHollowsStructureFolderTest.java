package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class CrystalHollowsStructureFolderTest {

    @Test
    void plansStablePerInstanceGroupsAndLabels() {
        List<CrystalHollowsStructureFolder.PlannedGroup> groups =
                CrystalHollowsStructureFolder.plan(List.of(
                        sighting(CrystalHollowsStructure.FAIRY_GROTTO, 300, 100, 300,
                                SightingConfidence.ENTITY),
                        sighting(CrystalHollowsStructure.FAIRY_GROTTO, 600, 100, 600,
                                SightingConfidence.SHARED_CHAT),
                        sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 286, 108, 403,
                                SightingConfidence.ROUGH_AREA)), true, false);
        assertEquals("crystal_hollows:structure:fairy_grotto", groups.get(0).id());
        assertEquals("crystal_hollows:structure:fairy_grotto:2", groups.get(1).id());
        assertEquals("Fairy Grotto #2", groups.get(1).name());
        assertEquals("Jungle Temple (approx.)", groups.get(2).name());
    }

    @Test
    void roughGroupsCanBeOmittedAndAmbiguousTargetsListCandidates() {
        StructureSighting rough = sighting(CrystalHollowsStructure.ODAWA, 349, 110, 390,
                SightingConfidence.ROUGH_AREA);
        StructureSighting target = new StructureSighting(CrystalHollowsStructure.WISHING_TARGET,
                400, 100, 400, SightingConfidence.COMPASS, "compass", 1,
                List.of(CrystalHollowsStructure.ODAWA, CrystalHollowsStructure.JUNGLE_TEMPLE), "");
        List<CrystalHollowsStructureFolder.PlannedGroup> groups =
                CrystalHollowsStructureFolder.plan(List.of(rough, target), false, false);
        assertEquals(1, groups.size());
        assertEquals("Compass target: Odawa / Jungle Temple", groups.getFirst().name());
        assertFalse(groups.getFirst().waypoints().isEmpty());
    }

    @Test
    void optionalNucleusGroupHasCentreAndEightEntrances() {
        List<CrystalHollowsStructureFolder.PlannedGroup> groups =
                CrystalHollowsStructureFolder.plan(List.of(), true, true);
        assertEquals(1, groups.size());
        assertEquals("crystal_hollows:structure:nucleus", groups.getFirst().id());
        assertEquals(9, groups.getFirst().waypoints().size());
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure,
                                               int x, int y, int z,
                                               SightingConfidence confidence) {
        return new StructureSighting(structure, x, y, z, confidence, "test", 1);
    }
}
