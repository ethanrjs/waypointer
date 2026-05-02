package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
