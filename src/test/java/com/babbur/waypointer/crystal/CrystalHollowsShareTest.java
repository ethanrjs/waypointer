package com.babbur.waypointer.crystal;

import java.util.List;
import net.minecraft.network.chat.ClickEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrystalHollowsShareTest {
    @Test
    void sharePreparesExactTextWithoutRunningACommand() {
        StructureSighting sighting = sighting(CrystalHollowsStructure.ODAWA, 349, 110, 390);
        ClickEvent click = CrystalHollowsShare.suggest(sighting);
        assertInstanceOf(ClickEvent.SuggestCommand.class, click);
        assertEquals(CrystalHollowsChatParser.formatShare(sighting.structure(), 349, 110, 390),
                ((ClickEvent.SuggestCommand) click).command());
        assertFalse(((ClickEvent.SuggestCommand) click).command().startsWith("/"));
    }

    @Test
    void oldCompassButtonKeepsItsCapturedCoordinates() {
        ClickEvent.SuggestCommand first = CrystalHollowsShare.suggest(
                sighting(CrystalHollowsStructure.WISHING_TARGET, 300, 70, 400));
        ClickEvent.SuggestCommand second = CrystalHollowsShare.suggest(
                sighting(CrystalHollowsStructure.WISHING_TARGET, 500, 80, 600));
        assertTrue(first.command().endsWith("300 70 400"));
        assertTrue(second.command().endsWith("500 80 600"));
    }

    @Test
    void routeShareSelectsNumberedSightingAndRejectsMissingInstances() {
        StructureSighting first = sighting(CrystalHollowsStructure.WISHING_TARGET, 300, 70, 400);
        StructureSighting second = sighting(CrystalHollowsStructure.WISHING_TARGET, 500, 80, 600);
        assertSame(second, CrystalHollowsShare.resolve(List.of(first, second), "wishing_target:2", first));
        assertNull(CrystalHollowsShare.resolve(List.of(first), "wishing_target:2", second));
        assertNull(CrystalHollowsShare.resolve(List.of(first), "invalid", second));
    }

    @Test
    void routeShareKeepsCompassAndFixedNucleusFallbacks() {
        StructureSighting compass = sighting(CrystalHollowsStructure.MINES_OF_DIVAN, 700, 90, 400);
        assertSame(compass, CrystalHollowsShare.resolve(List.of(), "wishing_target", compass));
        StructureSighting nucleus = CrystalHollowsShare.resolve(List.of(), "crystal_nucleus", null);
        assertNotNull(nucleus);
        assertEquals(CrystalHollowsStructure.CRYSTAL_NUCLEUS, nucleus.structure());
        assertEquals(513, nucleus.x());
        assertEquals(107, nucleus.y());
        assertEquals(513, nucleus.z());
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure, int x, int y, int z) {
        return new StructureSighting(structure, x, y, z, SightingConfidence.MANUAL, "test", 1L);
    }
}
