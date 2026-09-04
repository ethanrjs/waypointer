package com.babbur.waypointer.api;

import java.util.Objects;

/**
 * Optimistic reference to one waypoint in an immutable group snapshot.
 *
 * <p>The reference is intentionally not a permanent identity. An edit succeeds
 * only while the route still contains the expected snapshot at the captured
 * index, so user edits and reorders fail safely instead of changing the wrong
 * waypoint.
 */
public record WaypointReference(String groupId, int index, WaypointSnapshot expected) {

    public WaypointReference {
        groupId = Objects.requireNonNull(groupId, "groupId");
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        expected = Objects.requireNonNull(expected, "expected");
    }
}
