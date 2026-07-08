package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;

import java.util.ArrayList;
import java.util.List;

public final class DungeonRoomWaypointPlacement {

    private DungeonRoomWaypointPlacement() {}

    public static Waypoint toStoredWaypoint(WaypointGroup group, Waypoint actualWaypoint) {
        DungeonRoom room = currentRoomFor(group);
        return toStoredWaypoint(room, actualWaypoint);
    }

    public static Waypoint toStoredWaypoint(DungeonRoom room, Waypoint actualWaypoint) {
        return room == null ? actualWaypoint : toRoomLocal(room, actualWaypoint);
    }

    public static Waypoint toActualWaypoint(WaypointGroup group, Waypoint storedWaypoint) {
        DungeonRoom room = currentRoomFor(group);
        return toActualWaypoint(room, storedWaypoint);
    }

    public static Waypoint toActualWaypoint(DungeonRoom room, Waypoint storedWaypoint) {
        if (room == null) return storedWaypoint;
        int[] actual = DungeonMapMath.relativePreciseToActual(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                storedWaypoint.preciseX(),
                storedWaypoint.preciseY(),
                storedWaypoint.preciseZ());
        return new Waypoint(
                0,
                0,
                0,
                storedWaypoint.name(),
                storedWaypoint.color(),
                storedWaypoint.flags(),
                storedWaypoint.customRadius(),
                storedWaypoint.tempMode(),
                storedWaypoint.expiresAtMillis(),
                actual[0],
                actual[1],
                actual[2]);
    }

    public static int[] toStoredPrecisePosition(WaypointGroup group,
                                                int actualPreciseX,
                                                int actualPreciseY,
                                                int actualPreciseZ) {
        DungeonRoom room = currentRoomFor(group);
        if (room == null) {
            return new int[] { actualPreciseX, actualPreciseY, actualPreciseZ };
        }
        return DungeonMapMath.actualPreciseToRelative(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                actualPreciseX,
                actualPreciseY,
                actualPreciseZ);
    }

    public static void normalizeActualWaypointsForCurrentRoom(WaypointGroup group) {
        DungeonRoom room = currentRoomFor(group);
        if (room == null || group.isEmpty()) return;

        List<Waypoint> converted = new ArrayList<>(group.size());
        for (Waypoint waypoint : group.waypoints()) {
            converted.add(toStoredWaypoint(room, waypoint));
        }
        group.replaceWaypoints(converted);
    }

    static Waypoint toRoomLocal(DungeonRoom room, Waypoint actualWaypoint) {
        int[] relative = DungeonMapMath.actualPreciseToRelative(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                actualWaypoint.preciseX(),
                actualWaypoint.preciseY(),
                actualWaypoint.preciseZ());
        return new Waypoint(
                0,
                0,
                0,
                actualWaypoint.name(),
                actualWaypoint.color(),
                actualWaypoint.flags(),
                actualWaypoint.customRadius(),
                actualWaypoint.tempMode(),
                actualWaypoint.expiresAtMillis(),
                relative[0],
                relative[1],
                relative[2]);
    }

    private static DungeonRoom currentRoomFor(WaypointGroup group) {
        if (group == null || group.temp() || group.runtimeOnly()) return null;
        if (DungeonRoomData.definition(group.zoneId()) == null) return null;

        DungeonStateTracker tracker = WaypointerClient.dungeonTracker();
        DungeonRoom room = tracker == null ? null : tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return null;
        return group.zoneId().equals(room.roomId()) ? room : null;
    }
}
