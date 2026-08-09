package com.babbur.waypointer.api;

import com.babbur.waypointer.core.WaypointGroup;

/** Controls how many waypoints in a route are visible at once. */
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
