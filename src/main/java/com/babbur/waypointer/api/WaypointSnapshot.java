package com.babbur.waypointer.api;

import com.babbur.waypointer.core.Waypoint;

/**
 * Immutable view of a waypoint exposed to API consumers.
 *
 * <p>Snapshots are detached from Waypointer's live state. Mutating waypoint
 * data must go through {@link WaypointerApi} so saves and render caches stay
 * consistent.
 */
public record WaypointSnapshot(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double radius,
        boolean temporary) {

    static WaypointSnapshot from(Waypoint waypoint) {
        return new WaypointSnapshot(
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.name(),
                waypoint.color(),
                waypoint.flags(),
                waypoint.customRadius(),
                waypoint.isTemp());
    }
}
