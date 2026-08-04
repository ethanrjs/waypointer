package com.babbur.waypointer.location;

import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoreboardZoneResolverTest {

    @Test
    void resolvesRepresentativeSidebarFixtures() {
        assertZone("hub", """
                Purse: 1,234
                ⏣ Hub
                Profile: Apple
                """);
        assertZone("lotus_atoll", """
                Event: Example
                ⏣ Lotus Atoll, (12, 70, -5)
                """);
        assertZone("dwarven_mines", "⏣ Glacite Tunnels");
        assertZone("dwarven_mines", "⏣ Dwarven Base Camp");
        assertZone("dwarven_mines", "⏣ Great Glacite Lake");
        assertZone("mineshaft_crystal", """
                ⏣ Glacite Mineshafts
                07/15/26 m197CD AQUA_C
                """);
        assertZone("dungeon_f7", """
                The Catacombs (F7)
                ⏣ The Catacombs
                """);
        assertNull(ScoreboardZoneResolver.resolveSidebarText("BED WARS\nKills: 0"));
    }

    @Test
    void specialRefinersRequireALocationMarker() {
        assertNull(ScoreboardZoneResolver.resolveSidebarText("""
                The Catacombs (F7)
                Dungeon Cleared: 100%
                """));
        assertNull(ScoreboardZoneResolver.resolveSidebarText("""
                07/15/26 m197CD AQUA_C
                Glacite Mineshafts
                """));
    }

    private static void assertZone(String expectedId, String sidebar) {
        Zone zone = ScoreboardZoneResolver.resolveSidebarText(sidebar);
        assertEquals(expectedId, zone == null ? null : zone.id());
    }
}
