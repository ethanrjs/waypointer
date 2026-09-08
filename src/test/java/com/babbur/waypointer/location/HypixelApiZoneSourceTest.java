package com.babbur.waypointer.location;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypixelApiZoneSourceTest {
    @Test
    void lateFloorMarkerWithoutAreaLineRefinesPacketAndMissingMarkerRetainsFloor() {
        ActiveGroupManager manager = new ActiveGroupManager();
        HypixelApiZoneSource source = new HypixelApiZoneSource(manager::onZoneChanged);
        try {
            source.updateWorld(new Object());
            source.acceptLocation("SKYBLOCK", "dungeon", "dungeon", "mini1A", "");
            assertEquals(Zone.fromId("dungeon"), manager.currentZone());
            source.acceptSidebar("Time Elapsed: 48m 00s\nThe Catacombs (F7)\nCleared: 75%");
            assertEquals(Zone.fromId("dungeon_f7"), manager.currentZone());
            source.acceptSidebar("Time Elapsed: 48m 01s\nCleared: 75%");
            assertEquals(Zone.fromId("dungeon_f7"), manager.currentZone());
            source.acceptSidebar("The Catacombs (M7)");
            assertEquals(Zone.fromId("dungeon_m7"), manager.currentZone());
            source.acceptLocation("SKYBLOCK", "F7", "dungeon", "mini1A", "The Catacombs (M7)");
            assertEquals(Zone.fromId("dungeon_m7"), manager.currentZone());
            source.acceptSidebar("The Catacombs (F7)");
            assertEquals(Zone.fromId("dungeon_f7"), manager.currentZone());
        } finally {
            source.resetLocation();
        }
    }

    @Test
    void lateMineshaftCodeWithoutAreaLineRefinesPacket() {
        ActiveGroupManager manager = new ActiveGroupManager();
        HypixelApiZoneSource source = new HypixelApiZoneSource(manager::onZoneChanged);
        try {
            source.updateWorld(new Object());
            source.acceptLocation("SKYBLOCK", "Glacite Mineshafts", "mineshaft", "mini1A", "");
            assertEquals(Zone.fromId("mineshaft_unknown"), manager.currentZone());
            source.acceptSidebar("TUNG_1");
            assertEquals(Zone.fromId("mineshaft_tungsten"), manager.currentZone());
            source.acceptSidebar("Cold: 10");
            assertEquals(Zone.fromId("mineshaft_tungsten"), manager.currentZone());
        } finally {
            source.resetLocation();
        }
    }

    @Test
    void incompleteSkyblockPacketAllowsSidebarFallbackButOtherGamesDoNot() {
        ActiveGroupManager manager = new ActiveGroupManager();
        HypixelApiZoneSource source = new HypixelApiZoneSource(manager::onZoneChanged);
        try {
            source.updateWorld(new Object());
            source.acceptLocation("SKYBLOCK", null, null, "mini1A", "");
            source.acceptSidebar("⏣ Jungle");
            assertEquals(Zone.fromId("crystal_hollows"), manager.currentZone());
            source.acceptSidebar("");
            assertEquals(Zone.fromId("crystal_hollows"), manager.currentZone());
            assertTrue(source.detectionState().contains("retainingZoneDuringMissingSidebar=true"));
            source.acceptSidebar("⏣ Hub");
            assertEquals(Zone.fromId("hub"), manager.currentZone());
            source.acceptLocation("BEDWARS", null, null, "mini2B", "⏣ Jungle");
            source.acceptSidebar("⏣ Jungle");
            assertNull(manager.currentZone());
        } finally {
            source.resetLocation();
        }
    }

    @Test
    void missingApiPacketUsesSidebarUntilPacketArrivesAndNeverCarriesRoutesToAnotherWorld() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = new WaypointGroup("mining", "Mining", "crystal_hollows");
        route.add(Waypoint.at(400, 100, 400));
        manager.add(route);
        HypixelApiZoneSource source = new HypixelApiZoneSource(manager::onZoneChanged);
        try {
            source.updateWorld(new Object());
            source.acceptSidebar(null);
            assertNull(manager.currentZone());

            // No API packet arrives, but the visible mining area can activate saved routes.
            source.acceptSidebar("\uE067 Mithril Deposits");
            assertEquals(List.of(route), manager.activeGroups());
            source.acceptSidebar("\uE067 Jungle");
            assertEquals(List.of(route), manager.activeGroups());
            source.acceptSidebar(null);
            assertEquals(List.of(route), manager.activeGroups());
            source.acceptSidebar("Purse: 1,234\nEvent: Mining Fiesta");
            assertEquals(List.of(route), manager.activeGroups());
            source.acceptSidebar("\uE067 Mines of Divan");
            assertEquals(List.of(route), manager.activeGroups());

            source.acceptLocation("SKYBLOCK", "Crystal Hollows", "crystal_hollows", "mini1A", "");
            source.acceptSidebar("⏣ Hub");
            assertEquals(List.of(route), manager.activeGroups());
            source.acceptSidebar(null);
            assertEquals(List.of(route), manager.activeGroups());

            source.updateWorld(new Object());
            assertNull(manager.currentZone());
            assertNull(HypixelApiZoneSource.lastServerName());
            assertTrue(manager.activeGroups().isEmpty());
            source.acceptSidebar(null);
            assertTrue(manager.activeGroups().isEmpty());
            source.acceptSidebar("⏣ Hub");
            assertEquals(Zone.fromId("hub"), manager.currentZone());

            // A packet explicitly outside SkyBlock must override even a leftover sidebar.
            source.acceptLocation("BEDWARS", null, null, "mini2B", "⏣ Jungle");
            source.acceptSidebar("⏣ Jungle");
            assertNull(manager.currentZone());
            assertTrue(manager.activeGroups().isEmpty());
        } finally {
            source.resetLocation();
        }
    }

    @Test
    void emptySidebarKeepsConfirmedMineshaftButNewLayoutAndWorldStillReplaceIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        HypixelApiZoneSource source = new HypixelApiZoneSource(manager::onZoneChanged);
        try {
            source.updateWorld(new Object());
            source.acceptLocation("SKYBLOCK", "Glacite Mineshafts", "mineshaft", "mini1A",
                    "⏣ Glacite Mineshafts\nTUNG_1");
            assertEquals(Zone.fromId("mineshaft_tungsten"), manager.currentZone());
            source.acceptSidebar("");
            assertEquals(Zone.fromId("mineshaft_tungsten"), manager.currentZone());
            source.acceptSidebar("⏣ Glacite Mineshafts\nTOPA_2");
            assertEquals(Zone.fromId("mineshaft_topaz_2"), manager.currentZone());
            assertEquals("SKYBLOCK", HypixelApiZoneSource.debugSnapshot().serverType());
            assertEquals("Glacite Mineshafts", HypixelApiZoneSource.debugSnapshot().map());
            assertEquals("mineshaft", HypixelApiZoneSource.debugSnapshot().mode());
            source.acceptLocation("SKYBLOCK", "Glacite Mineshafts", "mineshaft", "mini2B", "");
            assertEquals(Zone.fromId("mineshaft_unknown"), manager.currentZone());
            source.updateWorld(new Object());
            assertNull(manager.currentZone());
        } finally {
            source.resetLocation();
        }
    }
}
