package com.babbur.waypointer.api;

import com.babbur.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable input for a route owned by another mod for the current session.
 *
 * <p>Overlays are not user data. Waypointer renders them through the same route
 * pipeline, but they are removed when the returned handle is closed and are not
 * written to the user's waypoint file.
 */
public record RouteOverlaySpec(
        String name,
        String zoneId,
        RouteLoadMode loadMode,
        List<WaypointSpec> waypoints) {

    public RouteOverlaySpec {
        name = name == null ? "" : name;
        zoneId = zoneId == null || zoneId.isBlank() ? Zone.UNKNOWN.id() : zoneId;
        loadMode = loadMode == null ? RouteLoadMode.STATIC : loadMode;
        waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "";
        private String zoneId = Zone.UNKNOWN.id();
        private RouteLoadMode loadMode = RouteLoadMode.STATIC;
        private final List<WaypointSpec> waypoints = new ArrayList<>();

        private Builder() {
        }

        /** Overlay name shown anywhere Waypointer surfaces the temporary group. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Waypointer zone id where the overlay should render. */
        public Builder zoneId(String zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        /** Whether Waypointer should render all overlay points or sequence them. */
        public Builder loadMode(RouteLoadMode loadMode) {
            this.loadMode = loadMode;
            return this;
        }

        /** Add one waypoint to the overlay, preserving insertion order. */
        public Builder waypoint(WaypointSpec waypoint) {
            waypoints.add(Objects.requireNonNull(waypoint, "waypoint"));
            return this;
        }

        /** Add several waypoints to the overlay, preserving iteration order. */
        public Builder waypoints(Collection<WaypointSpec> waypoints) {
            for (WaypointSpec waypoint : waypoints) waypoint(waypoint);
            return this;
        }

        public RouteOverlaySpec build() {
            return new RouteOverlaySpec(name, zoneId, loadMode, waypoints);
        }
    }
}
