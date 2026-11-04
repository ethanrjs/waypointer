package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.core.Waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public record RouteSpec(
        String name,
        String zoneId,
        RouteLoadMode loadMode,
        boolean enabled,
        double defaultRadius,
        List<WaypointSpec> waypoints) {

    public RouteSpec {
        name = name == null ? "" : name;
        WaypointCodec.validateRouteDisplayName(name, "route name");
        zoneId = zoneId == null || zoneId.isBlank() ? Zone.UNKNOWN.id() : zoneId;
        loadMode = loadMode == null ? RouteLoadMode.STATIC : loadMode;
        defaultRadius = Waypoint.normalizeDefaultRadius(defaultRadius);
        waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
        waypoints.forEach(WaypointSpec::validatePersistentName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "";
        private String zoneId = Zone.UNKNOWN.id();
        private RouteLoadMode loadMode = RouteLoadMode.STATIC;
        private boolean enabled = true;
        private double defaultRadius = 3.0;
        private final List<WaypointSpec> waypoints = new ArrayList<>();

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder zoneId(String zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        public Builder loadMode(RouteLoadMode loadMode) {
            this.loadMode = loadMode;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder defaultRadius(double defaultRadius) {
            this.defaultRadius = defaultRadius;
            return this;
        }

        public Builder waypoint(WaypointSpec waypoint) {
            waypoints.add(Objects.requireNonNull(waypoint, "waypoint"));
            return this;
        }

        public Builder waypoints(Collection<WaypointSpec> waypoints) {
            for (WaypointSpec waypoint : waypoints) waypoint(waypoint);
            return this;
        }

        public RouteSpec build() {
            return new RouteSpec(name, zoneId, loadMode, enabled, defaultRadius, waypoints);
        }
    }
}
