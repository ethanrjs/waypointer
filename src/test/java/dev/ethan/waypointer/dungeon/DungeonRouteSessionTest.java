package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DungeonRouteSessionTest {

    @BeforeEach
    @AfterEach
    void clearCustomRooms() {
        DungeonRoomData.clearAllCustom();
    }

    @Test
    void routeStartsAtLowestSecretIndexAndAdvancesPastFoundSecrets() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("route-room", "Route Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("second", 2));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();

        assertEquals(1, session.currentSecretIndex(room));
        assertEquals(DungeonRouteSession.Status.CURRENT,
                session.status(room, DungeonRoomData.waypointsFor(room).get(1)));

        session.markFound(room, 1);

        assertEquals(2, session.currentSecretIndex(room));
        assertEquals(DungeonRouteSession.Status.FOUND,
                session.status(room, DungeonRoomData.waypointsFor(room).get(1)));
        assertEquals(DungeonRouteSession.Status.CURRENT,
                session.status(room, DungeonRoomData.waypointsFor(room).get(0)));
    }

    @Test
    void resetRoomClearsFoundState() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("reset-room", "Reset Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        session.markFound(room, 1);
        session.resetRoom(room);

        assertEquals(DungeonRouteSession.Status.CURRENT,
                session.status(room, DungeonRoomData.waypointsFor(room).get(0)));
    }

    @Test
    void completedRouteHasNoCurrentSecret() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("complete-room", "Complete Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        session.markFound(room, 1);

        assertEquals(0, session.currentSecretIndex(room));
        assertEquals(DungeonRouteSession.Status.FOUND,
                session.status(room, DungeonRoomData.waypointsFor(room).get(0)));

        session.advance(room);

        assertEquals(0, session.currentSecretIndex(room));
    }

    @Test
    void routeAdvancesAcrossNonContiguousSecretIndices() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("sparse-room", "Sparse Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("second", 5));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 2));
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();

        assertEquals(2, session.currentSecretIndex(room));

        session.markFound(room, 2);

        assertEquals(5, session.currentSecretIndex(room));

        session.markFound(room, 5);

        assertEquals(0, session.currentSecretIndex(room));
    }

    @Test
    void secretIndexZeroIsNotPartOfRouteProgress() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("zero-room", "Zero Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("ignored", 0));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);

        assertEquals(1, session.currentSecretIndex(room));
        assertEquals(DungeonRouteSession.Status.NON_PROGRESS, session.status(room, waypoints.get(0)));

        session.markFound(room, 0);

        assertEquals(1, session.currentSecretIndex(room));
        assertEquals(DungeonRouteSession.Status.NON_PROGRESS, session.status(room, waypoints.get(0)));
    }

    @Test
    void debugSnapshotDoesNotCreateProgressForUnseenRoom() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("debug-room", "Debug Room", base);
        DungeonWaypoint waypoint = waypoint("first", 1);
        DungeonRoomData.addWaypoint(definition.id(), waypoint);
        DungeonRoom room = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        DungeonRouteSession.DebugSnapshot snapshot = session.debugSnapshot(room);

        assertFalse(snapshot.progressInitialized);
        assertEquals(1, snapshot.currentSecretIndex);
        assertEquals(1, snapshot.currentCount);
        assertEquals(0, snapshot.progressEntryCount);
        assertEquals(DungeonRouteSession.Status.CURRENT, session.peekStatus(room, waypoint));

        DungeonRouteSession.DebugSnapshot snapshotAgain = session.debugSnapshot(room);
        assertFalse(snapshotAgain.progressInitialized);
        assertEquals(0, snapshotAgain.progressEntryCount);
    }

    @Test
    void progressMigratesFromUnmatchedIdentityToMatchedRoomId() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("matched-room", "Matched Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("second", 2));
        DungeonRoom matched = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        session.markFound(base, 1);

        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(matched);

        assertEquals(2, session.currentSecretIndex(matched));
        assertEquals(DungeonRouteSession.Status.FOUND,
                session.status(matched, waypoints.get(0)));
        assertEquals(DungeonRouteSession.Status.CURRENT,
                session.status(matched, waypoints.get(1)));
    }

    @Test
    void progressSurvivesMatchedRoomReturningAsPhysicalRoom() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("return-room", "Return Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("second", 2));
        DungeonRoom matched = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        session.markFound(matched, 1);

        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(matched);

        assertEquals(DungeonRouteSession.Status.FOUND, session.status(base, waypoints.get(0)));
        assertEquals(DungeonRouteSession.Status.CURRENT, session.status(base, waypoints.get(1)));
        assertEquals(2, session.currentSecretIndex(base));
    }

    @Test
    void resetAllClearsPhysicalAndNamedAliases() {
        DungeonRoom base = room();
        var definition = DungeonRoomData.defineRoom("reset-all-room", "Reset All Room", base);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first", 1));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("second", 2));
        DungeonRoom matched = base.withDefinition(definition.id(), definition.displayName());

        DungeonRouteSession session = new DungeonRouteSession();
        session.markFound(matched, 1);
        assertEquals(2, session.currentSecretIndex(base));

        session.resetAll();

        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(matched);
        assertEquals(DungeonRouteSession.Status.CURRENT, session.status(matched, waypoints.get(0)));
        assertEquals(DungeonRouteSession.Status.CURRENT, session.status(base, waypoints.get(0)));
    }

    @Test
    void dungeonHubIsNotBroadDungeonRunZone() {
        assertEquals(false, DungeonRoomZoneBridge.isBroadDungeonZone(new Zone("dungeon_hub", "Dungeon Hub")));
        assertEquals(true, DungeonRoomZoneBridge.isBroadDungeonZone(new Zone("dungeon", "Catacombs")));
        assertEquals(true, DungeonRoomZoneBridge.isBroadDungeonZone(new Zone("dungeon_f7", "Catacombs F7")));
        assertEquals(true, DungeonRoomZoneBridge.isBroadDungeonZone(new Zone("dungeon_m7", "Master Mode M7")));
    }

    private static DungeonRoom room() {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -8,
                24,
                List.of(DungeonRoom.packSegment(-8, 24)));
    }

    private static DungeonWaypoint waypoint(String id, int secretIndex) {
        return new DungeonWaypoint(
                id,
                secretIndex,
                DungeonSecretCategory.CHEST,
                16,
                70,
                16,
                id,
                List.of());
    }
}
