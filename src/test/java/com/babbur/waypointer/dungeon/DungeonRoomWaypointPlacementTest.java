package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomWaypointPlacementTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = { "NW", "NE", "SE", "SW" })
    void coordinateTransformsPreservePointAndRouteStyles(Direction direction) {
        DungeonRoom room = room(direction, -74, -138);
        WaypointStyle pointStyle = WaypointStyle.EMPTY.with("labelScale", 1.5);
        Waypoint actual = Waypoint.at(-95, 68, -121).withStyleOverride(pointStyle);
        Waypoint stored = DungeonRoomWaypointPlacement.toStoredWaypoint(room, actual);
        assertEquals(pointStyle, stored.styleOverride());

        WaypointGroup source = WaypointGroup.create("Styled", "creeper-beams");
        source.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        source.setStylePresetId("preferred");
        source.setStyleOverride(WaypointStyle.EMPTY.with("beamWidth", 0.8));
        source.add(stored);
        WaypointGroup projected = DungeonRoomRouteProjection.transformedRouteGroupForRoom(room, source);

        assertEquals(actual, projected.get(0));
        assertEquals(source.stylePresetId(), projected.stylePresetId());
        assertEquals(source.styleOverride(), projected.styleOverride());
    }

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

        WaypointGroup projected = DungeonRoomRouteProjection.transformedRouteGroupForRoom(room, source);

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

        WaypointGroup projected = DungeonRoomRouteProjection.transformedRouteGroupForRoom(room, source);

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
    void rejectsWorldPlacementOutsideTheStoredRoomWithoutChangingLocalWaypoints() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        Field trackerField = WaypointerClient.class.getDeclaredField("dungeonTracker");
        trackerField.setAccessible(true);
        Object previousTracker = trackerField.get(null);
        WaypointGroup route = new WaypointGroup("saved", "Saved route", "creeper-beams");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Waypoint original = Waypoint.at(3, 70, 5).withName("keep me");
        route.add(original);
        Waypoint actual = Waypoint.at(-95, 68, -121);
        try {
            trackerField.set(null, tracker);
            assertFalse(DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(route));
            assertNull(DungeonRoomWaypointPlacement.toStoredWaypoint(route, actual));
            assertNull(DungeonRoomWaypointPlacement.toStoredPrecisePosition(route,
                    actual.preciseX(), actual.preciseY(), actual.preciseZ()));
            assertFalse(DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                    route, 0, actual.x(), actual.y(), actual.z()));
            assertEquals(List.of(original), route.waypoints());

            tracker.setCurrentRoom(room(Direction.NW, -74, -138));
            assertTrue(DungeonRoomWaypointPlacement.canPlaceInCurrentRoom(route));
            WaypointGroup otherRoom = new WaypointGroup("other", "Altar", "altar");
            otherRoom.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            assertNull(DungeonRoomWaypointPlacement.toStoredWaypoint(otherRoom, actual));

            tracker.setCurrentRoom(null);
            assertFalse(DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(
                    route, 0, actual.x(), actual.y(), actual.z()));
            route.set(0, original.withPos(4, 71, 6));
            assertEquals(4, route.get(0).x(), "manual room-local edits remain available");
            WaypointGroup regular = WaypointGroup.create("Hub", "hub");
            assertEquals(actual, DungeonRoomWaypointPlacement.toStoredWaypoint(regular, actual));
        } finally {
            trackerField.set(null, previousTracker);
        }
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void durableMirrorMoveStoresRoomLocalCoordinatesAndSurvivesReload(Direction direction)
            throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        DungeonRoom room = room(direction, -74, -138);
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        Field trackerField = WaypointerClient.class.getDeclaredField("dungeonTracker");
        trackerField.setAccessible(true);
        Object previousTracker = trackerField.get(null);

        try {
            tracker.setCurrentRoom(room);
            trackerField.set(null, tracker);

            WaypointGroup stored = new WaypointGroup("stored", "User Route", "creeper-beams");
            stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            stored.add(Waypoint.at(1, 68, 2).withName("move me"));
            manager.add(stored);

            WaypointGroup mirror = new WaypointGroup(
                    DungeonRoomRouteProjection.generatedGroupId("creeper-beams"),
                    "User Route", "creeper-beams");
            mirror.setRuntimeOnly(true);
            mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            mirror.setRuntimeSourceGroupId(stored.id());
            mirror.add(DungeonRoomWaypointPlacement.toActualWaypoint(room, stored.get(0)));
            manager.add(mirror);

            WaypointGroup editTarget = DungeonRoomRouteLibrary.durableEditTarget(manager, mirror);
            assertEquals(stored, editTarget);

            Waypoint desiredLocal = Waypoint.at(7, 70, -5);
            Waypoint desiredActual = DungeonRoomWaypointPlacement.toActualWaypoint(room, desiredLocal);
            assertNotEquals(desiredActual.x(), desiredLocal.x());
            DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(editTarget, 0,
                    desiredActual.x(), desiredActual.y(), desiredActual.z());
            manager.fireDataChanged();

            Storage storage = new Storage(tempDir.resolve(direction.name() + "-waypoints.json"));
            storage.save(manager);
            ActiveGroupManager loadedManager = new ActiveGroupManager();
            storage.load(loadedManager);

            WaypointGroup loaded = loadedManager.get("stored");
            Waypoint reprojected = DungeonRoomWaypointPlacement.toActualWaypoint(room, loaded.get(0));
            assertEquals(desiredLocal.x(), loaded.get(0).x());
            assertEquals(desiredLocal.y(), loaded.get(0).y());
            assertEquals(desiredLocal.z(), loaded.get(0).z());
            assertEquals(desiredActual.x(), reprojected.x());
            assertEquals(desiredActual.y(), reprojected.y());
            assertEquals(desiredActual.z(), reprojected.z());
            assertEquals("move me", loaded.get(0).name());
        } finally {
            trackerField.set(null, previousTracker);
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
