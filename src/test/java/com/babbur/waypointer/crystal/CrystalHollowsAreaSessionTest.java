package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrystalHollowsAreaSessionTest {

    @Test
    void midpointRefinesAndEnvelopeStaysBounded() {
        CrystalHollowsAreaSession session = new CrystalHollowsAreaSession(
                CrystalHollowsStructure.JUNGLE_TEMPLE, 200, 100, 200);
        assertEquals(new CrystalHollowsPosition(210, 105, 220),
                session.sample(220, 110, 240));
        assertEquals(new CrystalHollowsPosition(335, 105, 220),
                session.sample(400, 100, 200));
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE, session.structure());
    }
}
