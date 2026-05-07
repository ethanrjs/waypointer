package dev.ethan.waypointer.dungeon;

import java.util.List;

/**
 * Shared trigger matching logic kept free of Minecraft runtime types so route
 * selection stays covered by ordinary unit tests.
 */
final class DungeonTriggerSelection {

    private DungeonTriggerSelection() {}

    static DungeonWaypoint nearestEntityTrigger(
            DungeonRoom room,
            List<DungeonWaypoint> waypoints,
            DungeonWaypointTrigger trigger,
            double entityX,
            double entityY,
            double entityZ,
            double maxDistanceSq) {
        if (room == null || waypoints == null || trigger == null) return null;

        DungeonWaypoint nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.trigger() != trigger) continue;

            double distanceSq = distanceToWaypointSq(room, waypoint, entityX, entityY, entityZ);
            if (distanceSq <= maxDistanceSq && distanceSq < nearestDistanceSq) {
                nearest = waypoint;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private static double distanceToWaypointSq(
            DungeonRoom room,
            DungeonWaypoint waypoint,
            double x,
            double y,
            double z) {
        int[] world = DungeonMapMath.relativeToActual(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                waypoint.x(),
                waypoint.y(),
                waypoint.z());
        double dx = x - (world[0] + 0.5);
        double dy = y - (world[1] + 0.5);
        double dz = z - (world[2] + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
