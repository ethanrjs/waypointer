package com.babbur.waypointer.location;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardZoneResolverTest {
    @Test
    void missingAreaLineDoesNotHideALateMineshaftCode() {
        var manager = new ActiveGroupManager();
        var source = new ScoreboardZoneResolver(manager::onZoneChanged);
        source.updateWorld(new Object());
        source.acceptSidebar("\uE067 Glacite Mineshafts\n09/12/26 m96M UMBE_1");
        assertEquals(Zone.fromId("mineshaft_umber"), manager.currentZone());
        source.updateWorld(new Object());
        for (int poll = 0; poll < ScoreboardZoneResolver.PLAYER_LIST_CONFIRM_POLLS; poll++) {
            source.acceptSidebar("", () -> "Mineshaft");
        }
        assertEquals(Zone.fromId("mineshaft_unknown"), manager.currentZone());
        source.acceptSidebar("09/12/26 m96M UMBE_1");
        assertEquals(Zone.fromId("mineshaft_umber"), manager.currentZone());
        source.acceptSidebar("");
        assertEquals(Zone.fromId("mineshaft_umber"), manager.currentZone());
        source.acceptSidebar("TOPA_2");
        assertEquals(Zone.fromId("mineshaft_topaz_2"), manager.currentZone());
    }

    @Test
    void missingUpdatesKeepRoutesOnlyWithinTheSameWorld() {
        var manager = new ActiveGroupManager();
        var route = new WaypointGroup("mining", "Mining", "crystal_hollows");
        route.add(Waypoint.at(400, 100, 400));
        manager.add(route);
        var source = new ScoreboardZoneResolver(manager::onZoneChanged);
        source.updateWorld(new Object());
        source.acceptSidebar("⏣ Jungle");
        assertEquals(List.of(route), manager.activeGroups());
        for (int i = 0; i < 1_000; i++) source.acceptSidebar(i % 2 == 0 ? "" : "Purse: 10");
        assertEquals(List.of(route), manager.activeGroups());
        source.acceptSidebar("⏣ Hub");
        assertEquals(Zone.fromId("hub"), manager.currentZone());
        source.acceptSidebar("⏣ Jungle");
        source.updateWorld(new Object());
        source.acceptSidebar("");
        assertNull(manager.currentZone());
        assertEquals(List.of(), manager.activeGroups());
        source.acceptSidebar("⏣ Jungle");
        source.updateWorld(null);
        assertNull(manager.currentZone());
    }


    @Test
    void playerListAreaStandsInOnlyWhileNothingElseIsKnown() {
        var manager = new ActiveGroupManager();
        var source = new ScoreboardZoneResolver(manager::onZoneChanged);
        source.updateWorld(new Object());
        for (int poll = 1; poll < ScoreboardZoneResolver.PLAYER_LIST_CONFIRM_POLLS; poll++) {
            source.acceptSidebar("", () -> "Dwarven Mines");
            assertNull(manager.currentZone());
        }
        source.acceptSidebar("", () -> "Dwarven Mines");
        assertEquals(Zone.fromId("dwarven_mines"), manager.currentZone());
        source.acceptSidebar("", () -> "Hub");
        assertEquals(Zone.fromId("dwarven_mines"), manager.currentZone());
        source.acceptSidebar("\u23e3 Hub", () -> "Dwarven Mines");
        assertEquals(Zone.fromId("hub"), manager.currentZone());
        source.updateWorld(new Object());
        source.acceptSidebar(null, () -> null);
        assertNull(manager.currentZone());
        assertEquals(Zone.fromId("crystal_hollows"), ScoreboardZoneResolver.resolveAreaName("Jungle"));
        assertEquals(Zone.fromId("hub"), ScoreboardZoneResolver.resolveAreaName("Hub, "));
        assertEquals(Zone.fromId("hub"), ScoreboardZoneResolver.resolveKnownAreaName("Hub"));
        assertNull(ScoreboardZoneResolver.resolveKnownAreaName("Center"));
        assertNull(ScoreboardZoneResolver.resolveAreaName("  "));
        assertEquals("Dwarven Mines", TabListTexts.areaName("\u00a7bArea: \u00a7aDwarven Mines"));
        assertNull(TabListTexts.areaName("Area:"));
        assertNull(TabListTexts.areaName("Purse: 10"));
    }

    @Test
    void confirmedLayoutsSurviveDroppedCodeLinesUntilRealEvidenceReplacesThem() {
        var manager = new ActiveGroupManager();
        var source = new ScoreboardZoneResolver(manager::onZoneChanged);
        source.updateWorld(new Object());
        source.acceptSidebar("\u23e3 Glacite Mineshafts\nUMBE_1");
        assertEquals(Zone.fromId("mineshaft_umber"), manager.currentZone());
        source.acceptSidebar("\u23e3 Glacite Mineshafts\nCold: 3");
        assertEquals(Zone.fromId("mineshaft_umber"), manager.currentZone());
        source.acceptSidebar("\u23e3 Glacite Mineshafts\nAQUA_C");
        assertEquals(Zone.fromId("mineshaft_crystal"), manager.currentZone());
        source.acceptSidebar("\u23e3 Dwarven Mines");
        assertEquals(Zone.fromId("dwarven_mines"), manager.currentZone());
        source.acceptSidebar("The Catacombs (F7)\n\u23e3 The Catacombs");
        assertEquals(Zone.fromId("dungeon_f7"), manager.currentZone());
        source.acceptSidebar("\u23e3 The Catacombs\nCleared: 40%");
        assertEquals(Zone.fromId("dungeon_f7"), manager.currentZone());
        source.acceptSidebar("The Catacombs (M3)\n\u23e3 The Catacombs");
        assertEquals(Zone.fromId("dungeon_m3"), manager.currentZone());
        assertEquals(Zone.fromId("mineshaft_umber"), ConfirmedLayoutRetention.retain(
                Zone.fromId("mineshaft_umber"), Zone.fromId("mineshaft_unknown"), ""));
        assertEquals(Zone.fromId("hub"), ConfirmedLayoutRetention.retain(
                Zone.fromId("mineshaft_umber"), Zone.fromId("hub"), ""));
        assertNull(ConfirmedLayoutRetention.retain(Zone.fromId("mineshaft_umber"), null, ""));
        assertTrue(Zone.isSpecificMineshaftZone("mineshaft_umber"));
        assertFalse(Zone.isSpecificMineshaftZone("mineshaft_unknown"));
        assertFalse(Zone.isSpecificMineshaftZone(null));
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
    void mineshaftCodesResolveWithoutALocationMarkerButAreaNamesAloneDoNot() {
        assertNull(ScoreboardZoneResolver.resolveSidebarText("""
                The Catacombs (F7)
                Dungeon Cleared: 100%
                """));
        assertZone("mineshaft_crystal", """
                07/15/26 m197CD AQUA_C
                Glacite Mineshafts
                """);
        assertZone("mineshaft_umber", "09/12/26 m96M UMBE_1");
        assertNull(ScoreboardZoneResolver.resolveSidebarText("Glacite Mineshafts"));
        assertNull(ScoreboardZoneResolver.resolveSidebarText("09/12/26 m96M NOTUMBE_1"));
        assertNull(ScoreboardZoneResolver.resolveSidebarText("09/12/26 m96M umbe_1"));
        assertZone("mineshaft_unknown", "\uE067 Glacite Mineshafts");
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
