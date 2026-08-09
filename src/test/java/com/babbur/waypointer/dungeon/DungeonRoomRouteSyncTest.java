package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomRouteSyncTest {

    private DungeonRoomRouteSync sync;

    @AfterEach
    void uninstall() {
        if (sync != null) sync.uninstall();
    }

    @Test
    void projectsOnlyStoredDungeonRoutesIntoTheCurrentRoom() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        WaypointGroup stored = WaypointGroup.create("Route", "sync-room");
        stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        stored.add(Waypoint.at(4, 70, 7).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));
        manager.add(stored);

        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        tracker.setCurrentRoom(room("sync-room"));

        WaypointGroup mirror = manager.get(DungeonRoomRouteProjection.generatedGroupId("sync-room"));
        assertNotNull(mirror);
        assertTrue(mirror.runtimeOnly());
        assertEquals(WaypointGroup.RouteKind.DUNGEON, mirror.routeKind());
        assertEquals(stored.id(), mirror.runtimeSourceGroupId());
        assertEquals(93, mirror.get(0).x());
        assertEquals(204, mirror.get(0).z());
    }

    @Test
    void regularRouteWithTheSameZoneIsNotProjected() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        WaypointGroup regular = WaypointGroup.create("Regular", "sync-room");
        regular.add(Waypoint.at(4, 70, 7));
        manager.add(regular);

        sync = new DungeonRoomRouteSync(manager, tracker);
        sync.install();
        tracker.setCurrentRoom(room("sync-room"));

        assertNull(manager.get(DungeonRoomRouteProjection.generatedGroupId("sync-room")));
    }

    @Test
    void legacyMigrationMarksExistingRouteDungeonAndDoesNotDuplicateIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup existing = WaypointGroup.create("Existing", "legacy-room");
        existing.add(Waypoint.at(1, 70, 1));
        manager.add(existing);
        WaypointGroup legacy = WaypointGroup.create("Legacy", "legacy-room");
        legacy.add(Waypoint.at(2, 70, 2));

        assertEquals(1, DungeonRoomRouteLibrary.installMissingLegacyRoutes(
                manager, List.of(legacy)));

        assertEquals(WaypointGroup.RouteKind.DUNGEON, existing.routeKind());
        assertEquals(1, manager.groupsForZone("legacy-room").size());
    }

    private static DungeonRoom room(String id) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NE,
                100,
                200,
                List.of(DungeonRoom.packSegment(100, 200)),
                id,
                "Sync Room",
                DungeonDetectionConfidence.CORE_CONFIRMED);
    }
}
