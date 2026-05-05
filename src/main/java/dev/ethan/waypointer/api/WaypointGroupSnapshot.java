package dev.ethan.waypointer.api;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;

import java.util.List;

/**
 * Immutable view of a Waypointer route or overlay group.
 *
 * <p>The waypoint list is copied at snapshot time so other mods can iterate it
 * without racing Waypointer's UI or tick handlers.
 */
public record WaypointGroupSnapshot(
        String id,
        String name,
        String zoneId,
        int currentIndex,
        boolean enabled,
        boolean temporary,
        RouteLoadMode loadMode,
        double defaultRadius,
        List<WaypointSnapshot> waypoints) {

    static WaypointGroupSnapshot from(WaypointGroup group) {
        List<WaypointSnapshot> snapshots = group.waypoints().stream()
                .map(WaypointSnapshot::from)
                .toList();
        return new WaypointGroupSnapshot(
                group.id(),
                group.name(),
                group.zoneId(),
                group.currentIndex(),
                group.enabled(),
                group.temp(),
                RouteLoadMode.fromCore(group.loadMode()),
                group.defaultRadius(),
                snapshots);
    }

    public WaypointSnapshot currentWaypoint() {
        if (currentIndex < 0 || currentIndex >= waypoints.size()) return null;
        return waypoints.get(currentIndex);
    }
}
