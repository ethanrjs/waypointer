package com.babbur.waypointer.api;

import com.babbur.waypointer.core.WaypointGroup;

public enum RouteLoadMode {
    STATIC,
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
