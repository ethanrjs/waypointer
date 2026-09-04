package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsEntityAnchorTest {

    @Test
    void mapsKeeperNamesToDivanCentreOffsets() {
        assertKeeper("§6Keeper of Diamond", 33, 3);
        assertKeeper("Keeper of Lapis 20k❤", -33, -3);
        CrystalHollowsEntityAnchor.Match emerald =
                CrystalHollowsEntityAnchor.match("Keeper of Emerald").orElseThrow();
        assertEquals(-3, emerald.offsetX());
        assertEquals(33, emerald.offsetZ());
        CrystalHollowsEntityAnchor.Match gold =
                CrystalHollowsEntityAnchor.match("Keeper of Gold").orElseThrow();
        assertEquals(3, gold.offsetX());
        assertEquals(-33, gold.offsetZ());
    }

    @Test
    void mapsEveryNamedEntityRule() {
        assertStructure("[NPC] King Yolkar", CrystalHollowsStructure.KING_YOLKAR);
        assertStructure("Odawa 1000❤", CrystalHollowsStructure.ODAWA);
        assertStructure("Professor Robot", CrystalHollowsStructure.LOST_PRECURSOR_CITY);
        assertStructure("Boss Corleone", CrystalHollowsStructure.CORLEONE);
        assertStructure("Team Treasurite", CrystalHollowsStructure.CORLEONE);
        assertStructure("Key Guardian", CrystalHollowsStructure.KEY_GUARDIAN);
        assertStructure("Kalhuiki Door Guardian", CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertStructure("Golden Dragon", CrystalHollowsStructure.DRAGONS_LAIR);
        assertStructure("Xalx says hello", CrystalHollowsStructure.XALX);
        assertStructure("Goblin Queen", CrystalHollowsStructure.GOBLIN_QUEENS_DEN);
        assertTrue(CrystalHollowsEntityAnchor.match("ordinary player").isEmpty());
    }

    private static void assertKeeper(String name, int x, int z) {
        CrystalHollowsEntityAnchor.Match match = CrystalHollowsEntityAnchor.match(name).orElseThrow();
        assertEquals(CrystalHollowsStructure.MINES_OF_DIVAN, match.structure());
        assertEquals(x, match.offsetX());
        assertEquals(z, match.offsetZ());
        assertTrue(match.divanKeeper());
    }

    private static void assertStructure(String name, CrystalHollowsStructure structure) {
        assertEquals(structure, CrystalHollowsEntityAnchor.match(name).orElseThrow().structure());
    }
}
