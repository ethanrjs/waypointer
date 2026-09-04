package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class CrystalHollowsSightingSelectorTest {

    @Test
    void parsesAndSelectsStableMultiInstanceReferences() {
        StructureSighting first = sighting(CrystalHollowsStructure.FAIRY_GROTTO, 300);
        StructureSighting second = sighting(CrystalHollowsStructure.FAIRY_GROTTO, 500);
        List<StructureSighting> sightings = List.of(
                first, sighting(CrystalHollowsStructure.ODAWA, 400), second);

        CrystalHollowsSightingSelector.Selection selection =
                CrystalHollowsSightingSelector.parse("fairy_grotto:2");
        assertEquals(CrystalHollowsStructure.FAIRY_GROTTO, selection.structure());
        assertEquals(2, selection.instance());
        assertEquals(second, CrystalHollowsSightingSelector.find(sightings, selection));
        assertEquals("fairy_grotto:2",
                CrystalHollowsSightingSelector.referenceFor(sightings, second));
        assertEquals("odawa", CrystalHollowsSightingSelector.referenceAt(sightings, 1));
    }

    @Test
    void aliasesRemainSupportedAndInvalidInstancesAreRejected() {
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                CrystalHollowsSightingSelector.parse("divan").structure());
        assertNull(CrystalHollowsSightingSelector.parse("odawa:2"));
        assertNull(CrystalHollowsSightingSelector.parse("fairy_grotto:0"));
        assertNull(CrystalHollowsSightingSelector.parse("fairy_grotto:nope"));
        assertNull(CrystalHollowsSightingSelector.parse("unknown"));
    }

    private static StructureSighting sighting(
            CrystalHollowsStructure structure, int coordinate) {
        return new StructureSighting(structure, coordinate, 100, coordinate,
                SightingConfidence.SHARED_CHAT, "test", coordinate);
    }
}
