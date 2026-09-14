package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CrystalHollowsPosition;
import com.babbur.waypointer.crystal.CrystalHollowsStructureRoutes;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class DungeonRoomWaypointPlacement {

    private DungeonRoomWaypointPlacement() {}

    /**
     * Returns null when a world position cannot be converted to the route's local coordinates.
     */
    public static Waypoint toStoredWaypoint(WaypointGroup group, Waypoint actualWaypoint) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            CrystalHollowsPosition pivot = CrystalHollowsStructureRoutes.pivotFor(group);
            return pivot == null ? null : CrystalHollowsStructureZones.toStructureLocal(actualWaypoint, pivot);
        }
        DungeonRoom room = currentRoomFor(group);
        if (!canPlaceInRoom(group, room)) return null;
        return toStoredWaypoint(room, actualWaypoint);
    }

    public static boolean canPlaceInCurrentRoom(WaypointGroup group) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            return CrystalHollowsStructureRoutes.pivotFor(group) != null;
        }
        return canPlaceInRoom(group, currentRoomFor(group));
    }

    /** The editor starts unlocated structure waypoints at their NPC-relative origin. */
    public static Waypoint editorDefaultWaypoint(WaypointGroup group, Waypoint playerWaypoint) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            CrystalHollowsPosition pivot = CrystalHollowsStructureRoutes.pivotFor(group);
            return pivot == null ? playerWaypoint.withPos(0, 0, 0)
                    : CrystalHollowsStructureZones.toStructureLocal(playerWaypoint, pivot);
        }
        return toStoredWaypoint(group, playerWaypoint);
    }

    private static boolean canPlaceInRoom(WaypointGroup group, DungeonRoom room) {
        return group != null && (group.routeKind() != WaypointGroup.RouteKind.DUNGEON || room != null);
    }

    /** Why a placement on this route is unavailable; the dungeon-room reason when the route is unknown. */
    public static Component unavailableMessage(WaypointGroup group) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            return CrystalHollowsStructureRoutes.unavailableMessage();
        }
        return unavailableMessage();
    }

    public static Component unavailableMessage() {
        return Component.translatableWithFallback("waypointer.dungeon.edit.requires_room",
                "Enter this route's dungeon room to add or move waypoints.");
    }

    public static Waypoint toStoredWaypoint(DungeonRoom room, Waypoint actualWaypoint) {
        return room == null ? actualWaypoint : toRoomLocal(room, actualWaypoint);
    }

    public static Waypoint toActualWaypoint(WaypointGroup group, Waypoint storedWaypoint) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            CrystalHollowsPosition pivot = CrystalHollowsStructureRoutes.pivotFor(group);
            return pivot == null ? storedWaypoint : CrystalHollowsStructureZones.toWorld(storedWaypoint, pivot);
        }
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
                actual[2]).withStyleOverride(storedWaypoint.styleOverride());
    }

    /** Returns null when the route's room or structure anchor is unavailable. */
    public static int[] toStoredPrecisePosition(WaypointGroup group,
                                                int actualPreciseX,
                                                int actualPreciseY,
                                                int actualPreciseZ) {
        if (CrystalHollowsStructureRoutes.isStructureRoute(group)) {
            CrystalHollowsPosition pivot = CrystalHollowsStructureRoutes.pivotFor(group);
            return pivot == null ? null
                    : CrystalHollowsStructureZones.toStructureLocalPrecise(
                            actualPreciseX, actualPreciseY, actualPreciseZ, pivot);
        }
        DungeonRoom room = currentRoomFor(group);
        if (!canPlaceInRoom(group, room)) return null;
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

    public static boolean moveWaypointToStoredPosition(WaypointGroup group, int index,
                                                     int actualX, int actualY, int actualZ) {
        Waypoint actualWaypoint = group.get(index).withPos(actualX, actualY, actualZ);
        Waypoint storedWaypoint = toStoredWaypoint(group, actualWaypoint);
        if (storedWaypoint == null) return false;
        group.moveWaypointToPrecise(index,
                storedWaypoint.preciseX(),
                storedWaypoint.preciseY(),
                storedWaypoint.preciseZ());
        return true;
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
                relative[2]).withStyleOverride(actualWaypoint.styleOverride());
    }

    private static DungeonRoom currentRoomFor(WaypointGroup group) {
        if (group == null || group.temp() || group.runtimeOnly()) return null;
        if (group.routeKind() != WaypointGroup.RouteKind.DUNGEON) return null;

        DungeonStateTracker tracker = WaypointerClient.dungeonTracker();
        DungeonRoom room = tracker == null ? null : tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return null;
        return group.zoneId().equals(room.roomId()) ? room : null;
    }
}
