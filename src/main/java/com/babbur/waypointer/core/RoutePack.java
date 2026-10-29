package com.babbur.waypointer.core;

import java.util.UUID;

public record RoutePack(String id, String name) {

    public static final int MAX_NAME_LENGTH = 64;

    public RoutePack {
        id = id == null ? "" : id.strip();
        if (id.isEmpty() || id.length() > 80) throw new IllegalArgumentException("Invalid route pack id");
        name = SafeText.line(name);
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).strip();
    }

    public static RoutePack fresh(String name) {
        return new RoutePack(UUID.randomUUID().toString(), name);
    }

    public static RoutePack catalog(String routeId, String title) {
        return new RoutePack("catalog:" + routeId, title);
    }
}
