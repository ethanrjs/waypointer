package com.babbur.waypointer.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record WaypointStyle(Map<String, String> values) {
    public static final WaypointStyle EMPTY = new WaypointStyle(Map.of());
    public WaypointStyle {
        Map<String, String> clean = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> {
            if (key != null && value != null && key.length() <= 80 && value.length() <= 128
                    && clean.size() < 128) clean.put(key, value);
        });
        values = Map.copyOf(clean);
    }
    public boolean isEmpty() { return values.isEmpty(); }
    public WaypointStyle with(String property, Object value) {
        if (value == null) return without(property);
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put(property, String.valueOf(value));
        return new WaypointStyle(next);
    }
    public WaypointStyle without(String property) {
        if (!values.containsKey(property)) return this;
        Map<String, String> next = new LinkedHashMap<>(values);
        next.remove(property);
        return next.isEmpty() ? EMPTY : new WaypointStyle(next);
    }
}
