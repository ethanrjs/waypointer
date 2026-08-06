package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DungeonRoomWaypointPlacementTest {

    @Test
    void actualRoomWaypointStoresLocalAndProjectsBackToSameRunPosition() {
        DungeonRoom room = room(Direction.SE, -74, -138);
        Waypoint actual = new Waypoint(-95, 68, -121, "beam", 0x00BFFF,
                Waypoint.FLAG_SKIP_ON_STAND, 0.0);

        Waypoint stored = DungeonRoomWaypointPlacement.toRoomLocal(room, actual);

        assertEquals(21, stored.x());
        assertEquals(68, stored.y());
        assertEquals(-17, stored.z());

        WaypointGroup source = new WaypointGroup("source", "Creeper Beams", "creeper-beams");
        source.add(stored);

        WaypointGroup projected = DungeonRoomRouteSync.transformedRouteGroupForRoom(room, source, null);

        assertEquals(actual.preciseX(), projected.get(0).preciseX());
        assertEquals(actual.preciseY(), projected.get(0).preciseY());
        assertEquals(actual.preciseZ(), projected.get(0).preciseZ());
    }

    @Test
    void preciseActualRoomWaypointPreservesSixteenthOffsetThroughLocalStorage() {
        DungeonRoom room = room(Direction.SE, -74, -138);
        Waypoint actual = new Waypoint(-94, 69, -121, "", 0x00BFFF,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT, 0.0,
                Waypoint.TEMP_NONE, 0L,
                -1489, 1104, -1936);

        Waypoint stored = DungeonRoomWaypointPlacement.toRoomLocal(room, actual);
        WaypointGroup source = new WaypointGroup("source", "Creeper Beams", "creeper-beams");
        source.add(stored);

        WaypointGroup projected = DungeonRoomRouteSync.transformedRouteGroupForRoom(room, source, null);

        assertEquals(actual.preciseX(), projected.get(0).preciseX());
        assertEquals(actual.preciseY(), projected.get(0).preciseY());
        assertEquals(actual.preciseZ(), projected.get(0).preciseZ());
    }

    @Test
    void storedRoomWaypointProjectsToActualBeforeEditingOneCoordinate() {
        DungeonRoom room = room(Direction.SE, -74, -138);
        Waypoint actual = new Waypoint(-95, 68, -121, "beam", 0x00BFFF,
                Waypoint.FLAG_SKIP_ON_STAND, 0.0);
        Waypoint stored = DungeonRoomWaypointPlacement.toStoredWaypoint(room, actual);

        Waypoint editedStored = DungeonRoomWaypointPlacement.toStoredWaypoint(
                room,
                DungeonRoomWaypointPlacement.toActualWaypoint(room, stored).withPos(
                        -96,
                        actual.y(),
                        actual.z()));
        Waypoint editedActual = DungeonRoomWaypointPlacement.toActualWaypoint(room, editedStored);

        assertEquals(-96, editedActual.x());
        assertEquals(actual.y(), editedActual.y());
        assertEquals(actual.z(), editedActual.z());
    }

    @Test
    void durableMirrorEditsAreRejectedForReadOnlyDungeonRoutes() throws Exception {
        DungeonRoomData.clearAllCustom();
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonRoom room = room(Direction.SE, -74, -138);
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        Field trackerField = WaypointerClient.class.getDeclaredField("dungeonTracker");
        trackerField.setAccessible(true);
        Object previousTracker = trackerField.get(null);

        try {
            DungeonRoomData.defineRoom("creeper-beams", "Creeper Beams", room);
            tracker.setCurrentRoom(room);
            trackerField.set(null, tracker);

            WaypointGroup stored = new WaypointGroup("stored", "User Route", "creeper-beams");
            stored.add(Waypoint.at(1, 68, 2).withName("move me"));
            manager.add(stored);

            WaypointGroup mirror = new WaypointGroup(
                    DungeonRoomRouteSync.generatedGroupId("creeper-beams"),
                    "User Route", "creeper-beams");
            mirror.setRuntimeOnly(true);
            mirror.add(DungeonRoomWaypointPlacement.toActualWaypoint(room, stored.get(0)));
            manager.add(mirror);

            assertNull(DungeonRoomRouteSync.durableEditTarget(manager, mirror));
            assertEquals(1, stored.size());
            assertEquals("move me", stored.get(0).name());
        } finally {
            trackerField.set(null, previousTracker);
            DungeonRoomData.clearAllCustom();
        }
    }

    private static DungeonRoom room(Direction direction, int cornerX, int cornerZ) {
        return new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                direction,
                cornerX,
                cornerZ,
                List.of(DungeonRoom.packSegment(-104, -168)),
                "creeper-beams",
                "Creeper Beams",
                DungeonDetectionConfidence.CORE_CONFIRMED);
    }
}
