package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsGeometryTest {

    @Test
    void usesOrderedZoneBoxesAtOverlapsAndCorners() {
        assertEquals(CrystalHollowsZone.CRYSTAL_NUCLEUS,
                CrystalHollowsGeometry.zoneAt(512, 64, 512));
        assertEquals(CrystalHollowsZone.JUNGLE,
                CrystalHollowsGeometry.zoneAt(201, 63, 201));
        assertEquals(CrystalHollowsZone.MITHRIL_DEPOSITS,
                CrystalHollowsGeometry.zoneAt(823, 188, 201));
        assertEquals(CrystalHollowsZone.GOBLIN_HOLDOUT,
                CrystalHollowsGeometry.zoneAt(201, 188, 823));
        assertEquals(CrystalHollowsZone.PRECURSOR_REMNANTS,
                CrystalHollowsGeometry.zoneAt(823, 188, 823));
        assertEquals(CrystalHollowsZone.MAGMA_FIELDS,
                CrystalHollowsGeometry.zoneAt(201, 30, 201));
    }

    @Test
    void maxBoundsAreExclusiveAndOutsideIsSafe() {
        assertNull(CrystalHollowsGeometry.zoneAt(824, 100, 512));
        assertNull(CrystalHollowsGeometry.zoneAt(512, 189, 512));
        assertNull(CrystalHollowsGeometry.zoneAt(200, 100, 512));
        assertTrue(CrystalHollowsGeometry.insideHollows(823.999, 188.999, 823.999));
        assertFalse(CrystalHollowsGeometry.insideHollows(824, 100, 512));
    }

    @Test
    void nucleusBoundsWinOverQuadrants() {
        assertTrue(CrystalHollowsGeometry.insideNucleus(462, 63, 461));
        assertFalse(CrystalHollowsGeometry.insideNucleus(564, 100, 500));
        assertEquals(CrystalHollowsZone.CRYSTAL_NUCLEUS,
                CrystalHollowsGeometry.zoneAt(500, 100, 500));
    }
}
