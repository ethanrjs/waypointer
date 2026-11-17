package com.babbur.waypointer.config;

import com.babbur.waypointer.core.WaypointStyle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Copy a look from one appearance level and paste it on any other (issue #196). What is copied
 * is what the level itself changes: a route's or zone's own settings, or for Global every
 * setting that differs from the built-in default. Pasting sets those settings on the target
 * and leaves the rest of its look alone; a setting the target cannot have (a waypoint has no
 * route line settings) is skipped.
 */
public final class AppearanceClipboard {

    /** A copied look and where it came from, for the Paste button. */
    public record Copied(WaypointStyle style, String source) {
        public int count() {
            return WaypointStyles.setCount(style);
        }
    }

    private static volatile Copied copied;

    private AppearanceClipboard() {}

    public static Copied copied() {
        return copied;
    }

    /** Copies a level's own settings. Returns how many were copied (0 copies nothing). */
    public static int copy(WaypointStyle style, String source) {
        Map<String, String> values = new LinkedHashMap<>();
        if (style != null) {
            style.values().forEach((name, value) -> {
                if (WaypointStyles.supportedProperties().contains(name)) values.put(name, value);
            });
        }
        if (values.isEmpty()) return 0;
        copied = new Copied(new WaypointStyle(values), source == null ? "" : source);
        return values.size();
    }

    public static int copy(AppearanceScope scope, String source) {
        return copy(scope.style(), source);
    }

    /** Copies the Global look: every appearance setting that differs from the built-in default. */
    public static int copyGlobal(WaypointerConfig config, String source) {
        return copy(changedFromDefaults(config), source);
    }

    static WaypointStyle changedFromDefaults(WaypointerConfig config) {
        WaypointStyle current = WaypointStyles.capture(config);
        WaypointStyle defaults = WaypointStyles.capture(new WaypointerConfig());
        Map<String, String> changed = new LinkedHashMap<>();
        current.values().forEach((name, value) -> {
            if (!value.equals(defaults.values().get(name))) changed.put(name, value);
        });
        return changed.isEmpty() ? WaypointStyle.EMPTY : new WaypointStyle(changed);
    }

    /** Pastes onto a Zone, Route or Waypoint level. Returns how many settings it now sets. */
    public static int paste(AppearanceScope scope, Predicate<String> allowed) {
        Copied source = copied;
        return source == null ? 0 : scope.paste(source.style(), allowed);
    }

    /** Pastes onto the Global look. Returns how many settings were applied. */
    public static int pasteGlobal(WaypointerConfig config) {
        Copied source = copied;
        if (source == null) return 0;
        WaypointStyles.applyTo(config, source.style());
        config.invalidateResolvedStyles();
        config.save();
        return source.count();
    }

    public static void clear() {
        copied = null;
    }
}
