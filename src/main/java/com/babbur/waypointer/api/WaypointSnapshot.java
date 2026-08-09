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
        boolean temporary,
        int preciseX,
        int preciseY,
        int preciseZ) {

    public WaypointSnapshot {
        x = Waypoint.blockCoordinateFromPrecise(preciseX);
        y = Waypoint.blockCoordinateFromPrecise(preciseY);
        z = Waypoint.blockCoordinateFromPrecise(preciseZ);
    }

    public WaypointSnapshot(int x, int y, int z, String name, int color, int flags,
                            double radius, boolean temporary) {
        this(x, y, z, name, color, flags, radius, temporary,
                Waypoint.preciseBlockCenter(x),
                Waypoint.preciseBlockCenter(y),
                Waypoint.preciseBlockCenter(z));
    }

    static WaypointSnapshot from(Waypoint waypoint) {
        return new WaypointSnapshot(
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.name(),
                waypoint.color(),
                waypoint.flags(),
                waypoint.customRadius(),
                waypoint.isTemp(),
                waypoint.preciseX(),
                waypoint.preciseY(),
                waypoint.preciseZ());
    }
}
