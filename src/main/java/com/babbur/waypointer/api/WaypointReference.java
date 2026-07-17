package com.babbur.waypointer.api;

import java.util.Objects;

/**
 * Optimistic reference to one waypoint in an immutable group snapshot.
 *
 * <p>The reference is intentionally not a permanent identity. An edit succeeds
 * only while the route still contains the expected snapshot at the captured
 * index, so user edits and reorders fail safely instead of changing the wrong
 * waypoint.
 *
 * <p>ponytail: references are session-only optimistic guards, not persisted
 * identity. Add UUIDs to the core model, save migration, and every codec before
 * offering references that survive edits or restarts.
 */
public record WaypointReference(String groupId, int index, WaypointSnapshot expected) {

    public WaypointReference {
        groupId = Objects.requireNonNull(groupId, "groupId");
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        expected = Objects.requireNonNull(expected, "expected");
    }
}
