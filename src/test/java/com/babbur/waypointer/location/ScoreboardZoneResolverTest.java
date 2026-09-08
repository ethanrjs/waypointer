package com.babbur.waypointer.location;

import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoreboardZoneResolverTest {
    @Test
    void missingUpdatesKeepRoutesOnlyWithinTheSameWorld() {
        var manager = new com.babbur.waypointer.core.ActiveGroupManager();
        var route = new com.babbur.waypointer.core.WaypointGroup("mining", "Mining", "crystal_hollows");
        route.add(com.babbur.waypointer.core.Waypoint.at(400, 100, 400));
        manager.add(route);
        var source = new ScoreboardZoneResolver(manager::onZoneChanged);
        source.updateWorld(new Object());
        source.acceptSidebar("⏣ Jungle");
        assertEquals(java.util.List.of(route), manager.activeGroups());
        for (int i = 0; i < 1_000; i++) source.acceptSidebar(i % 2 == 0 ? "" : "Purse: 10");
        assertEquals(java.util.List.of(route), manager.activeGroups());
        source.acceptSidebar("⏣ Hub");
        assertEquals(Zone.fromId("hub"), manager.currentZone());
        source.acceptSidebar("⏣ Jungle");
        source.updateWorld(new Object());
        source.acceptSidebar("");
        assertNull(manager.currentZone());
        assertEquals(java.util.List.of(), manager.activeGroups());
        source.acceptSidebar("⏣ Jungle");
        source.updateWorld(null);
        assertNull(manager.currentZone());
    }


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

    @Test
    void miningAreasAndResourcePackGlyphResolveToTheSavedIsland() {
        for (String area : new String[]{"Crystal Nucleus", "Jungle", "Mithril Deposits",
                "Goblin Holdout", "Precursor Remnants", "Magma Fields", "Mines of Divan",
                "Jungle Temple", "Khazad-dûm", "Fairy Grotto"}) {
            assertZone("crystal_hollows", "\uE067 " + area + " (400, 100, 500)");
        }
        assertZone("mineshaft_tungsten", "\uE067 Glacite Mineshafts\nTUNG_1");
        assertZone("dwarven_mines", "\uE067 Glacite Tunnels");
    }

    private static void assertZone(String expectedId, String sidebar) {
        Zone zone = ScoreboardZoneResolver.resolveSidebarText(sidebar);
        assertEquals(expectedId, zone == null ? null : zone.id());
    }
}
