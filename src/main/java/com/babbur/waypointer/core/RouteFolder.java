package com.babbur.waypointer.core;

import java.util.Objects;

/** A single-level folder for saved or session-only routes in one zone. */
public record RouteFolder(
        String id, String name, String zoneId, boolean collapsed, int color, boolean runtimeOnly) {

    public static final int DEFAULT_COLOR = 0x4FB3C4;

    public RouteFolder(String id, String name, String zoneId, boolean collapsed) {
        this(id, name, zoneId, collapsed, DEFAULT_COLOR, false);
    }

    public RouteFolder(String id, String name, String zoneId, boolean collapsed, int color) {
        this(id, name, zoneId, collapsed, color, false);
    }

    public RouteFolder {
        id = requireText(id, "id");
        name = requireText(name, "name");
        zoneId = Zone.canonicalId(requireText(zoneId, "zoneId"));
        color &= 0xFFFFFF;
    }

    public RouteFolder withName(String value) {
        return new RouteFolder(id, value, zoneId, collapsed, color, runtimeOnly);
    }

    public RouteFolder withCollapsed(boolean value) {
        return new RouteFolder(id, name, zoneId, value, color, runtimeOnly);
    }

    public RouteFolder withColor(int value) {
        return new RouteFolder(id, name, zoneId, collapsed, value, runtimeOnly);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
