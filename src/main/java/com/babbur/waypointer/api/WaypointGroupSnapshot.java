package com.babbur.waypointer.api;

import com.babbur.waypointer.core.WaypointGroup;

import java.util.List;
import java.util.stream.IntStream;

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

    public List<WaypointReference> waypointReferences() {
        return IntStream.range(0, waypoints.size())
                .mapToObj(index -> new WaypointReference(id, index, waypoints.get(index)))
                .toList();
    }
}
