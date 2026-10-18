package com.babbur.waypointer.api;

import java.util.Objects;

public record WaypointReference(String groupId, int index, WaypointSnapshot expected) {

    public WaypointReference {
        groupId = Objects.requireNonNull(groupId, "groupId");
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        expected = Objects.requireNonNull(expected, "expected");
    }
}
