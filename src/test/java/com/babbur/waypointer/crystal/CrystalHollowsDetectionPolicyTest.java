package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsDetectionPolicyTest {

    @Test
    void onlyKnownContradictoryAreasAtSkyblockerStructureCoordinatesAreEvidence() {
        var structure = CrystalHollowsStructure.LOST_PRECURSOR_CITY;
        var remote = new StructureSighting(structure, 600, 100, 600,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 1);
        assertTrue(CrystalHollowsDetectionPolicy.contradictsSkyblocker(remote,
                "Precursor Remnants", 600, 100, 600));
        for (String area : new String[] {null, "", "Unknown area", "Lost Precursor City"}) {
            assertFalse(CrystalHollowsDetectionPolicy.contradictsSkyblocker(remote, area, 600, 100, 600));
        }
        assertFalse(CrystalHollowsDetectionPolicy.contradictsSkyblocker(remote,
                "Precursor Remnants", 600, 106, 600));
        for (var sighting : new StructureSighting[] {
                new StructureSighting(structure, 600, 100, 600, SightingConfidence.MANUAL, "command", 1),
                new StructureSighting(structure, 600, 100, 600, SightingConfidence.SHARED_REMOTE, "relay", 1),
                new StructureSighting(CrystalHollowsStructure.KING_YOLKAR, 600, 100, 600,
                        SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 1)}) {
            assertFalse(CrystalHollowsDetectionPolicy.contradictsSkyblocker(sighting,
                    "Precursor Remnants", 600, 100, 600));
        }
    }

    @Test
    void suppressesEntityScansDuringThePostTransitionDelay() {
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 50));
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 1));
        assertTrue(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 0));
    }

    @Test
    void honorsTheEntityDetectionSettingAfterTheDelay() {
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(false, 0));
        assertTrue(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 0));
    }
}
