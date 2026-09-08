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
            assertTrue(manager.activeGroups().isEmpty());
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
            source.acceptLocation("SKYBLOCK", "Glacite Mineshafts", "mineshaft", "mini2B", "");
            assertEquals(Zone.fromId("mineshaft_unknown"), manager.currentZone());
            source.updateWorld(new Object());
            assertNull(manager.currentZone());
        } finally {
            source.resetLocation();
        }
    }
}
