package com.babbur.waypointer.api;


import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public interface WaypointerApi {

    ZoneSnapshot currentZone();

    default Optional<ZoneSnapshot> currentZoneOptional() {
        return Optional.ofNullable(currentZone());
    }

    List<WaypointGroupSnapshot> allGroups();

    List<WaypointGroupSnapshot> savedRoutes();

    List<WaypointGroupSnapshot> activeGroups();

    List<WaypointGroupSnapshot> groupsForZone(String zoneId);

    String createRoute(RouteSpec route);

    boolean removeRoute(String groupId);

    boolean addWaypoint(String groupId, WaypointSpec waypoint);

    @Deprecated(forRemoval = false, since = "1.8.2")
    boolean updateWaypoint(String groupId, int waypointIndex, WaypointSpec replacement);

    boolean updateWaypoint(WaypointReference reference, WaypointSpec replacement);

    boolean removeWaypoint(WaypointReference reference);

    @Deprecated(forRemoval = false, since = "1.8.2")
    WaypointGroupSnapshot addTempWaypoint(WaypointSpec waypoint);

    WaypointerHandle showTempWaypoint(WaypointSpec waypoint);

    WaypointerHandle showRouteOverlay(RouteOverlaySpec overlay);

    ImportSummary importRoutes(String payload, ImportOptions options);

    default ImportSummary importRoutes(String payload) {
        return importRoutes(payload, ImportOptions.defaults());
    }

    String exportRoutes(List<String> groupIds, ExportOptions options);

    default String exportRoutes(List<String> groupIds) {
        return exportRoutes(groupIds, ExportOptions.defaults());
    }

    WaypointerHandle onDataChanged(Runnable listener);

    WaypointerHandle onZoneChanged(Consumer<ZoneSnapshot> listener);

    default WaypointerHandle onZoneStateChanged(Consumer<Optional<ZoneSnapshot>> listener) {
        Objects.requireNonNull(listener, "listener");
        return onZoneChanged(zone -> listener.accept(Optional.ofNullable(zone)));
    }
}
