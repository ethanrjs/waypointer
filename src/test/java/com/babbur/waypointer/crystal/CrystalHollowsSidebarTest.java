package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CrystalHollowsSidebarTest {

    @Test
    void recognizesResourcePackLocationGlyphFromLiveReport() {
        String blob = "09/06/26 m12DR\nEarly Winter 10th\n\uE067 Mines of Divan\nBits: 703";
        assertEquals("Mines of Divan", CrystalHollowsSidebar.areaName(blob));
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN,
                CrystalHollowsSidebar.structureForArea(CrystalHollowsSidebar.areaName(blob)));
        assertEquals(CrystalHollowsStructure.LOST_PRECURSOR_CITY,
                CrystalHollowsSidebar.structureForArea(CrystalHollowsSidebar.areaName("\uE067 Lost Precursor City")));
    }

    @Test
    void extractsAreaWithFormattingAndCoordinateSuffix() {
        String blob = "§7Early Autumn 1st\n §b⏣ §5Jungle Temple §7(343, 72, 424)\n§ePurse";
        assertEquals("Jungle Temple", CrystalHollowsSidebar.areaName(blob));
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE,
                CrystalHollowsSidebar.structureForArea(CrystalHollowsSidebar.areaName(blob)));
    }

    @Test
    void normalizesAllKhazadSpellings() {
        assertEquals(CrystalHollowsStructure.KHAZAD_DUM,
                CrystalHollowsSidebar.structureForArea("Khazad-dûm"));
        assertEquals(CrystalHollowsStructure.KHAZAD_DUM,
                CrystalHollowsSidebar.structureForArea("Khazad-dum"));
        assertEquals(CrystalHollowsStructure.KHAZAD_DUM,
                CrystalHollowsSidebar.structureForArea("khazad-dm"));
    }

    @Test
    void extractsBothDateAndClosingServerIds() {
        assertEquals("m197CD", CrystalHollowsSidebar.serverId("07/15/26 m197CD AQUA_C"));
        assertEquals("mini123A", CrystalHollowsSidebar.serverId("09/04/26 mini123A"));
        assertEquals("m77A", CrystalHollowsSidebar.serverId("Server closing: 03:11 m77A"));
    }

    @Test
    void doesNotTreatOtherModesAsLobbyIds() {
        assertNull(CrystalHollowsSidebar.serverId("BED WARS\n09/04/26 lobby1"));
        assertNull(CrystalHollowsSidebar.areaName("No area here"));
        assertNull(CrystalHollowsSidebar.zoneForArea("Mines of Divan"));
        assertEquals(CrystalHollowsZone.JUNGLE, CrystalHollowsSidebar.zoneForArea("Jungle"));
    }
}
