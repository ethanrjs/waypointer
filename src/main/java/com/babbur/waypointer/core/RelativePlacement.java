package com.babbur.waypointer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Places imported routes relative to the player (/wp import relative): every route moves by the
 * same whole-block offset, so the first waypoint lands on the given block and the routes keep
 * their shape, heights and positions relative to each other.
 */
public final class RelativePlacement {

    private RelativePlacement() {}

    /** Moves {@code groups} so their first waypoint sits at (x, y, z). Returns false when there is no waypoint. */
    public static boolean placeFirstWaypointAt(List<WaypointGroup> groups, int x, int y, int z) {
        Waypoint first = null;
        for (WaypointGroup group : groups) {
            if (!group.isEmpty()) {
                first = group.get(0);
                break;
            }
        }
        if (first == null) return false;
        int dx = x - first.x();
        int dy = y - first.y();
        int dz = z - first.z();
        if (dx == 0 && dy == 0 && dz == 0) return true;
        for (WaypointGroup group : groups) {
            List<Waypoint> moved = new ArrayList<>(group.size());
            for (Waypoint waypoint : group.waypoints()) moved.add(waypoint.translated(dx, dy, dz));
            group.replaceWaypoints(moved);
        }
        return true;
    }
}
