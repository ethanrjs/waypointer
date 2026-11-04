package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrystalHollowsStructureRouteSyncTest {

    @AfterEach
    void restorePivotResolver() {
        CrystalHollowsStructureRoutes.overridePivotResolver(null);
    }

    @Test
    void editorCanCreateAndEditOffsetsBeforeDiscoveryWithoutChangingWorldPlacementRules() {
        var route = new WaypointGroup("offline", "Temple",
                CrystalHollowsStructureZones.zoneId(CrystalHollowsStructure.JUNGLE_TEMPLE));
        Waypoint player = new Waypoint(310, 90, 390, "Named", 0x123456, 0, 2.0);
        CrystalHollowsStructureRoutes.overridePivotResolver(structure -> null);

        Waypoint initial = DungeonRoomWaypointPlacement.editorDefaultWaypoint(route, player);
        assertEquals(0, initial.x());
        assertEquals(0, initial.y());
        assertEquals(0, initial.z());
        assertEquals("Named", initial.name());
        assertEquals(0x123456, initial.color());
        route.add(initial);
        route.set(0, route.get(0).withPos(2, -3, 4));
        assertNull(DungeonRoomWaypointPlacement.toStoredWaypoint(route, player));
        assertFalse(DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(route, 0, 310, 90, 390));

        CrystalHollowsPosition pivot = new CrystalHollowsPosition(300, 100, 400);
        CrystalHollowsStructureRoutes.overridePivotResolver(structure -> pivot);
        Waypoint located = DungeonRoomWaypointPlacement.editorDefaultWaypoint(route, player);
        assertEquals(10, located.x());
        assertEquals(-10, located.y());
        assertEquals(-10, located.z());
        Waypoint projected = DungeonRoomWaypointPlacement.toActualWaypoint(route, route.get(0));
        assertEquals(302, projected.x());
        assertEquals(97, projected.y());
        assertEquals(404, projected.z());
        assertEquals(2, route.get(0).x(), "discovery must not offset saved coordinates twice");
    }

    @Test
    void exactSightingProjectsSavedStructureRoutesIntoTheLobbyAndKeepsProgressAcrossEdits() throws Exception {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        var tracker = new CrystalHollowsTracker(manager, config, null);
        var lobby = new CrystalHollowsLobbyState("test", 0, 0);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));
        CrystalHollowsStructureRoutes.overridePivotResolver(structure ->
                CrystalHollowsStructureZones.pivot(lobby.sightings(), structure, null));
        new CrystalHollowsStructureRouteSync(manager, tracker).install();

        var stored = new WaypointGroup("route-1", "Temple loop",
                CrystalHollowsStructureZones.zoneId(CrystalHollowsStructure.JUNGLE_TEMPLE));
        stored.add(Waypoint.at(2, 3, -4));
        stored.add(Waypoint.at(0, 0, 0).withPreciseSixteenths(8, 16, 24));
        manager.add(stored);
        String mirrorId = CrystalHollowsStructureRouteSync.generatedId("route-1");
        assertNull(manager.get(mirrorId), "an unlocated structure must not project");
        assertEquals(List.of(), manager.activeGroups());

        tracker.merge(new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 300, 100, 400,
                SightingConfidence.NPC_CHAT, "chat", 1L), false);
        assertNull(manager.get(mirrorId), "a rough sighting must not anchor a route");

        tracker.merge(new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 300, 100, 400,
                SightingConfidence.MANUAL, "manual", 2L), false);
        WaypointGroup mirror = manager.get(mirrorId);
        assertNotNull(mirror);
        assertTrue(mirror.runtimeOnly());
        assertEquals("crystal_hollows", mirror.zoneId());
        assertEquals("route-1", mirror.runtimeSourceGroupId());
        assertEquals("Temple loop", mirror.name());
        assertEquals(302, mirror.get(0).x());
        assertEquals(103, mirror.get(0).y());
        assertEquals(396, mirror.get(0).z());
        assertEquals(300 * 16 + 8, mirror.get(1).preciseX());
        assertEquals(100 * 16 + 16, mirror.get(1).preciseY());
        assertEquals(400 * 16 + 24, mirror.get(1).preciseZ());
        assertTrue(manager.activeGroups().contains(mirror));
        assertFalse(manager.activeGroups().contains(stored));

        mirror.setCurrentIndex(1);
        manager.fireDataChanged();
        assertSame(mirror, manager.get(mirrorId));
        assertEquals(1, manager.get(mirrorId).currentIndex());

        stored.add(Waypoint.at(5, 5, 5));
        manager.fireDataChangedFor(stored);
        WaypointGroup rebuilt = manager.get(mirrorId);
        assertNotSame(mirror, rebuilt);
        assertEquals(3, rebuilt.size());
        assertEquals(0, rebuilt.currentIndex(), "shape changed, so progress restarts from the saved route");
        rebuilt.setCurrentIndex(2);
        stored.setCurrentIndex(0);
        manager.fireDataChangedFor(stored);
        assertSame(rebuilt, manager.get(mirrorId));

        Waypoint placed = DungeonRoomWaypointPlacement.toStoredWaypoint(stored, Waypoint.at(310, 90, 390));
        assertEquals(10, placed.x());
        assertEquals(-10, placed.y());
        assertEquals(-10, placed.z());
        assertTrue(DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(stored));
        assertEquals(310, DungeonRoomWaypointPlacement.toActualWaypoint(stored, placed).x());

        var island = new WaypointGroup("island", "Island", "crystal_hollows");
        tracker.clearSightings();
        assertNull(manager.get(mirrorId));
        Waypoint unanchored = DungeonRoomWaypointPlacement.toStoredWaypoint(stored, Waypoint.at(310, 90, 390));
        assertNull(unanchored);
        assertFalse(DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(stored));
        assertNull(DungeonRoomWaypointPlacement.toStoredPrecisePosition(stored, 4960, 1440, 6240));
        Waypoint original = stored.get(0);
        assertFalse(DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(stored, 0, 310, 90, 390));
        assertSame(original, stored.get(0), "a rejected move must preserve the saved coordinate");
        assertTrue(DungeonRoomWaypointPlacement.unavailableMessage(stored).getString().contains("Crystal Hollows"));
        assertFalse(DungeonRoomWaypointPlacement.unavailableMessage(island).getString().contains("Crystal Hollows"));
        assertSame(placed, DungeonRoomWaypointPlacement.toActualWaypoint(stored, placed));

        tracker.merge(new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 600, 80, 700,
                SightingConfidence.ENTITY, "entity:Kalhuiki Door Guardian", 3L), false);
        WaypointGroup relocated = manager.get(mirrorId);
        assertNotNull(relocated);
        assertEquals(602, relocated.get(0).x());
        assertEquals(83, relocated.get(0).y());
        assertEquals(696, relocated.get(0).z());
        assertSame(original, stored.get(0), "new lobby coordinates must never overwrite saved offsets");
    }

    @Test
    void lobbyIdentityNotifiesRouteSyncEvenWhenStructureMarkersAreHidden(@TempDir Path directory) throws Exception {
        var manager = new ActiveGroupManager();
        var config = new WaypointerConfig();
        config.setCrystalHollowsHideStructuresFolder(true);
        var store = new CrystalHollowsStore(directory.resolve("lobbies.json"));
        var tracker = new CrystalHollowsTracker(manager, config, store);
        set(tracker, "active", true);
        var notifications = new AtomicInteger();
        tracker.addDetectionListener(notifications::incrementAndGet);
        new CrystalHollowsStructureRouteSync(manager, tracker).install();
        tracker.resolveIdentity("mini123", 2);
        assertEquals(1, notifications.get());
        assertEquals("mini123", tracker.lobby().serverId());
        assertTrue(manager.allGroups().isEmpty());
        tracker.resolveIdentity("mini123", 2);
        assertEquals(1, notifications.get(), "unchanged identity must not repeat notifications");
        tracker.resolveIdentity("mini456", 2);
        assertEquals(2, notifications.get());
        assertEquals("mini456", tracker.lobby().serverId());
        store.flush();
    }

    @Test
    void disabledOrEmptyRoutesAndOtherZonesNeverProject() throws Exception {
        var manager = new ActiveGroupManager();
        var tracker = new CrystalHollowsTracker(manager, new WaypointerConfig(), null);
        var lobby = new CrystalHollowsLobbyState("test", 0, 0);
        set(tracker, "active", true);
        set(tracker, "lobby", lobby);
        CrystalHollowsStructureRoutes.overridePivotResolver(structure -> new CrystalHollowsPosition(1, 2, 3));
        var sync = new CrystalHollowsStructureRouteSync(manager, tracker);
        sync.install();

        var disabled = new WaypointGroup("off", "Off",
                CrystalHollowsStructureZones.zoneId(CrystalHollowsStructure.ODAWA));
        disabled.add(Waypoint.at(1, 1, 1));
        disabled.setEnabled(false);
        var empty = new WaypointGroup("empty", "Empty",
                CrystalHollowsStructureZones.zoneId(CrystalHollowsStructure.ODAWA));
        var island = new WaypointGroup("island", "Island", "crystal_hollows");
        island.add(Waypoint.at(1, 1, 1));
        manager.addAll(List.of(disabled, empty, island));
        assertNull(manager.get(CrystalHollowsStructureRouteSync.generatedId("off")));
        assertNull(manager.get(CrystalHollowsStructureRouteSync.generatedId("empty")));
        assertNull(manager.get(CrystalHollowsStructureRouteSync.generatedId("island")));
        assertNull(DungeonRoomWaypointPlacement.toStoredWaypoint((WaypointGroup) null, Waypoint.at(1, 1, 1)));
        assertEquals(Waypoint.at(4, 4, 4).x(),
                DungeonRoomWaypointPlacement.toStoredWaypoint(island, Waypoint.at(4, 4, 4)).x());

        disabled.setEnabled(true);
        manager.fireDataChangedFor(disabled);
        WaypointGroup mirror = manager.get(CrystalHollowsStructureRouteSync.generatedId("off"));
        assertNotNull(mirror);
        assertEquals(2, mirror.get(0).x());
        assertEquals(3, mirror.get(0).y());
        assertEquals(4, mirror.get(0).z());

        set(tracker, "active", false);
        sync.sync();
        assertNull(manager.get(CrystalHollowsStructureRouteSync.generatedId("off")));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        var declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
