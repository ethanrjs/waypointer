package com.babbur.waypointer.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record WaypointStyle(Map<String, String> values) {
    public static final WaypointStyle EMPTY = new WaypointStyle(Map.of());

    public static final String PAINT_KEY = "waypointPaint";
    private static final int MAX_VALUE_LENGTH = 128;
    private static final int MAX_PAINT_LENGTH = 2048;
    public WaypointStyle {
        Map<String, String> clean = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> {
            int limit = PAINT_KEY.equals(key) ? MAX_PAINT_LENGTH : MAX_VALUE_LENGTH;
            if (key != null && value != null && key.length() <= 80 && value.length() <= limit
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
