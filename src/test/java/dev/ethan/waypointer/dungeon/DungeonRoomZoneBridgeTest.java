package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DungeonRoomZoneBridgeTest {

    @Test
    void namedRoomZoneTemporarilyActivatesRoomRoutesThenRestoresBroadDungeonZone() {
        assertNotNull(DungeonRoomData.definition("spider"));
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRoomZoneBridge bridge = new DungeonRoomZoneBridge(manager, tracker);
        WaypointGroup floorRoute = route("F7 Route", "dungeon_f7", 0);
        WaypointGroup roomRoute = route("Spider Route", "spider", 1);
        manager.add(floorRoute);
        manager.add(roomRoute);
        bridge.install();

        Zone broadFloor = new Zone("dungeon_f7", "Catacombs F7");
        tracker.onZoneChanged(broadFloor);
        manager.onZoneChanged(broadFloor);

        assertEquals(List.of(floorRoute), manager.activeGroups());

        tracker.setCurrentRoom(namedSpiderRoom());

        assertEquals("spider", manager.currentZone().id());
        assertEquals(List.of(roomRoute), manager.activeGroups());

        tracker.setCurrentRoom(null);

        assertEquals("dungeon_f7", manager.currentZone().id());
        assertEquals(List.of(floorRoute), manager.activeGroups());
    }

    @Test
    void debugSnapshotTracksAppliedAndRestoredZoneActions() {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        DungeonRoomZoneBridge bridge = new DungeonRoomZoneBridge(manager, tracker);
        bridge.install();

        Zone broadFloor = new Zone("dungeon_f7", "Catacombs F7");
        tracker.onZoneChanged(broadFloor);
        manager.onZoneChanged(broadFloor);
        tracker.setCurrentRoom(namedSpiderRoom());

        DungeonRoomZoneBridge.DebugSnapshot applied = DungeonRoomZoneBridge.debugSnapshot();

        assertEquals("applied room zone", applied.lastAction);
        assertEquals("Spider (spider)", applied.currentZone);
        assertEquals("Catacombs F7 (dungeon_f7)", applied.lastBroadZone);

        tracker.setCurrentRoom(null);

        DungeonRoomZoneBridge.DebugSnapshot restored = DungeonRoomZoneBridge.debugSnapshot();

        assertEquals("restored broad dungeon", restored.lastAction);
        assertEquals("Catacombs F7 (dungeon_f7)", restored.currentZone);
    }

    private static WaypointGroup route(String name, String zoneId, int x) {
        WaypointGroup group = WaypointGroup.create(name, zoneId);
        group.add(Waypoint.at(x, 70, 0));
        return group;
    }

    private static DungeonRoom namedSpiderRoom() {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -8,
                24,
                List.of(DungeonRoom.packSegment(-8, 24)),
                "spider",
                "Spider",
                DungeonDetectionConfidence.CORE_CONFIRMED);
    }
}
