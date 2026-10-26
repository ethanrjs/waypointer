package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsAreaSessionTest {

    @Test
    void estimateIsTheSpotWeStoodOnNearestTheMiddleAndTheEnvelopeStaysBounded() {
        CrystalHollowsAreaSession session = new CrystalHollowsAreaSession(
                CrystalHollowsStructure.JUNGLE_TEMPLE, 200, 100, 200);
        assertEquals(new CrystalHollowsPosition(220, 110, 240),
                session.sample(220, 110, 240));
        assertEquals(new CrystalHollowsPosition(400, 100, 200),
                session.sample(400, 100, 200));
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE, session.structure());
    }

    @Test
    void neverPlacesTheEstimateWhereNobodyStood() {
        CrystalHollowsAreaSession session = new CrystalHollowsAreaSession(
                CrystalHollowsStructure.MINES_OF_DIVAN, 600.5, 100, 300.5);
        session.sample(640.5, 120, 300.5);
        session.sample(640.5, 120, 340.5);
        CrystalHollowsPosition estimate = session.sample(600.5, 100, 340.5);
        assertTrue(estimate.equals(new CrystalHollowsPosition(600, 100, 300))
                || estimate.equals(new CrystalHollowsPosition(640, 120, 300))
                || estimate.equals(new CrystalHollowsPosition(640, 120, 340))
                || estimate.equals(new CrystalHollowsPosition(600, 100, 340)), estimate.toString());
    }

    @Test
    void remembersWhereWeStoodInsideTheArea() {
        CrystalHollowsAreaSession session = new CrystalHollowsAreaSession(
                CrystalHollowsStructure.FAIRY_GROTTO, 300, 100, 300);
        session.sample(310, 100, 300);
        assertTrue(session.stoodNear(new CrystalHollowsPosition(330, 100, 300)));
        assertFalse(session.stoodNear(new CrystalHollowsPosition(360, 100, 300)));
        session.sample(500, 100, 300);
        assertFalse(session.stoodNear(new CrystalHollowsPosition(300, 100, 300)),
                "spots outside the current envelope are forgotten");
    }

    @Test
    void sameAreaAllowsTheWholeEnvelopePlusSidebarLag() {
        var here = new CrystalHollowsPosition(500, 100, 500);
        assertTrue(CrystalHollowsAreaSession.sameArea(here, new CrystalHollowsPosition(640, 100, 360)));
        assertFalse(CrystalHollowsAreaSession.sameArea(here, new CrystalHollowsPosition(660, 100, 500)));
    }
}
