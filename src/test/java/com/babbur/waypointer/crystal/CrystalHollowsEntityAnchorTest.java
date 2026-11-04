package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsEntityAnchorTest {

    @Test
    void mapsKeeperNamesToDivanCentreOffsets() {
        assertKeeper("§6Keeper of Diamond", 33, 3);
        assertKeeper("Keeper of Lapis 20k❤", -33, -3);
        assertKeeper("[NPC] Keeper of Diamond", 33, 3);
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
        assertStructure("Key Guardian", CrystalHollowsStructure.KEY_GUARDIAN);
        assertStructure("Kalhuiki Door Guardian", CrystalHollowsStructure.JUNGLE_TEMPLE);
        assertStructure("Golden Dragon", CrystalHollowsStructure.DRAGONS_LAIR);
        assertStructure("Xalx says hello", CrystalHollowsStructure.XALX);
        assertStructure("Goblin Queen", CrystalHollowsStructure.GOBLIN_QUEENS_DEN);
        assertTrue(CrystalHollowsEntityAnchor.match("ordinary player").isEmpty());
    }

    @Test
    void requiresBossIdentityRatherThanSharedTreasuriteFactionName() {
        assertTrue(CrystalHollowsEntityAnchor.match("Team Treasurite").isEmpty());
        assertTrue(CrystalHollowsEntityAnchor.match("[Lv100] Grunt 30k❤").isEmpty());
        assertTrue(CrystalHollowsEntityAnchor.match("Not Boss Corleone").isEmpty());
        assertTrue(CrystalHollowsEntityAnchor.match("Boss Corleone Fan").isEmpty());
        assertStructure("§8[§7Lv200§8] §cBoss Corleone §a1M§c❤", CrystalHollowsStructure.CORLEONE);
        assertTrue(CrystalHollowsEntityAnchor.isCorleoneProfile("Team Treasurite", 1_000_000));
        assertTrue(CrystalHollowsEntityAnchor.isCorleoneProfile("Team Treasurite", 2_000_000));
        for (double health : new double[] {20, 30_000, 60_000, 100_000, 200_000, Double.NaN}) {
            assertTrue(!CrystalHollowsEntityAnchor.isCorleoneProfile("Team Treasurite", health));
        }
        assertTrue(!CrystalHollowsEntityAnchor.isCorleoneProfile("ordinary player", 1_000_000));
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
