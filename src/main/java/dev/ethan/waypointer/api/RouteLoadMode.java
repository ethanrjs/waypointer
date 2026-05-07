package dev.ethan.waypointer.api;

import dev.ethan.waypointer.core.WaypointGroup;

/**
 * Controls how many waypoints in a route are visible at once.
 *
 * <p>The API keeps this enum separate from Waypointer's mutable route model so
 * third-party integrations can depend on a small, stable surface instead of the
 * full internal group type.
 */
public enum RouteLoadMode {
    /** Every waypoint in the route may render at the same time. */
    STATIC,
    /** Waypointer renders a small window around the current route step. */
    SEQUENCE;

    WaypointGroup.LoadMode toCore() {
        return switch (this) {
            case STATIC -> WaypointGroup.LoadMode.STATIC;
            case SEQUENCE -> WaypointGroup.LoadMode.SEQUENCE;
        };
    }

    static RouteLoadMode fromCore(WaypointGroup.LoadMode mode) {
        return switch (mode) {
            case STATIC -> STATIC;
            case SEQUENCE -> SEQUENCE;
        };
    }
}
