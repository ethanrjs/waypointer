package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.core.Zone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Skysoft waypoint libraries, independently decoded from its published format:
 * https://github.com/Akinsoft/Skysoft/blob/a979f8e63d3ced952e02bd1e260321b89f5cc8e0/src/main/kotlin/com/skysoft/features/waypoints/WaypointJson.kt
 */
final class SkysoftWaypointImporter {
    static final String PREFIX = "SKYSOFT:";
    private static final int DEFAULT_COLOR = 0x45A3FF;

    private SkysoftWaypointImporter() {}

    static WaypointImporter.ImportResult decodeShare(String text) {
        String json;
        try {
            json = WaypointImporter.decodeBase64Gzip(text.substring(PREFIX.length()));
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid Skysoft compressed share", failure);
        }
        JsonObject envelope = object(WaypointImporter.parseBoundedJson(json));
        if (integer(envelope, "formatVersion") != 1) {
            throw new IllegalArgumentException("unsupported Skysoft share format version");
        }
        if (!"waypoints".equals(string(envelope, "type", ""))) {
            throw new IllegalArgumentException("Skysoft share does not contain waypoints");
        }
        checkVersion(integer(envelope, "schemaVersion"));
        return decodeJson(object(WaypointImporter.parseBoundedJson(string(envelope, "payload", ""))));
    }

    static boolean looksLikeLibrary(JsonElement root) {
        if (!root.isJsonObject()) return false;
        JsonObject value = root.getAsJsonObject();
        if (!value.has("version") || !value.has("groups") || !value.get("groups").isJsonArray()) return false;
        JsonArray groups = value.getAsJsonArray("groups");
        return groups.isEmpty() || groups.asList().stream()
                .anyMatch(group -> group.isJsonObject() && group.getAsJsonObject().has("points"));
    }

    static WaypointImporter.ImportResult decodeJson(JsonObject root) {
        int version = integer(root, "version");
        checkVersion(version);
        JsonArray values = array(root, "groups");
        if (values.size() > WaypointImporter.MAX_GROUPS_PER_IMPORT) {
            throw new IllegalArgumentException("Skysoft library has too many groups");
        }
        List<WaypointGroup> groups = new ArrayList<>();
        int total = 0;
        for (JsonElement value : values) {
            JsonObject group = object(value);
            JsonArray points = array(group, "points");
            total += points.size();
            if (points.size() > WaypointImporter.MAX_WAYPOINTS_PER_GROUP
                    || total > WaypointImporter.MAX_TOTAL_WAYPOINTS_PER_IMPORT) {
                throw new IllegalArgumentException("Skysoft library has too many waypoints");
            }
            groups.add(readGroup(group, points, version));
        }
        if (total == 0) throw new IllegalArgumentException("Skysoft library contains no waypoints");
        return new WaypointImporter.ImportResult(WaypointImporter.Source.SKYSOFT, groups, "");
    }

    private static WaypointGroup readGroup(JsonObject json, JsonArray points, int version) {
        WaypointGroup group = WaypointGroup.create(
                WaypointCodec.Options.sanitizeLabel(string(json, "name", "Imported Skysoft route")),
                zone(string(json, "island", "unknown")));
        group.setLoadMode(bool(json, "route", false)
                ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        group.setEnabled(bool(json, "enabled", true));
        group.setSkipAheadEnabled(bool(json, "skipAhead", false));
        group.setDefaultRadius(radius(json, 3.0));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        int groupColor = color(json, DEFAULT_COLOR);
        group.setStaticColor(groupColor);
        boolean filled = bool(json, "filled", false);
        group.setStyleOverride(displayStyle(styles(json, version, Set.of("MARKER")), filled)
                .with("showRouteLines", group.loadMode() == WaypointGroup.LoadMode.SEQUENCE && bool(json, "lines", true))
                .with("colorSequenceWaypointsByRole", false));
        // Loop, profile/visit scope and chroma have no existing local equivalents.
        List<Waypoint> waypoints = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            JsonObject point = object(points.get(i));
            int fallbackColor = groupColor == DEFAULT_COLOR ? defaultColor(i, points.size()) : groupColor;
            int flags = bool(point, "enabled", true) ? 0 : Waypoint.FLAG_DISABLED;
            Waypoint waypoint = new Waypoint(0, 0, 0,
                    WaypointCodec.Options.sanitizeLabel(string(point, "name", "")),
                    color(point, fallbackColor), flags, radius(point, 0.0), Waypoint.TEMP_NONE, 0L,
                    precise(point, "x"), precise(point, "y"), precise(point, "z"));
            Set<String> pointStyles = styles(point, version, null);
            if (pointStyles != null) waypoint = waypoint.withStyleOverride(displayStyle(pointStyles, filled));
            waypoints.add(waypoint);
        }
        group.addAll(waypoints);
        return group;
    }

    private static Set<String> styles(JsonObject json, int version, Set<String> fallback) {
        if (version == 1) {
            if (json.has("styles")) throw new IllegalArgumentException("combined Skysoft styles require version 2");
            if (!json.has("style")) return fallback;
            String style = checkedStyle(string(json, "style", ""));
            return style.equals("BEACON") ? Set.of("MARKER", "BEACON") : Set.of(style);
        }
        if (!json.has("styles")) return fallback;
        Set<String> result = new HashSet<>();
        for (JsonElement value : array(json, "styles")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Skysoft styles must be text");
            if (!result.add(checkedStyle(value.getAsString())))
                throw new IllegalArgumentException("Skysoft styles must be unique");
        }
        return result;
    }

    private static String checkedStyle(String style) {
        if (!Set.of("MARKER", "BLOCK", "BEACON").contains(style))
            throw new IllegalArgumentException("unsupported Skysoft style " + style);
        return style;
    }

    private static WaypointStyle displayStyle(Set<String> styles, boolean filled) {
        boolean block = styles.contains("BLOCK");
        boolean marker = block || styles.contains("MARKER");
        boolean beacon = styles.contains("BEACON");
        // Waypointer renders one box per waypoint; BLOCK takes precedence over MARKER.
        return WaypointStyle.EMPTY
                .with("boxStyle", block && filled ? "FILLED_OUTLINED" : "OUTLINED")
                .with("waypointMarkerScale", block ? 1.0 : 0.25)
                .with("waypointOutlineOpacity", marker ? 1.0 : 0.0)
                .with("matchWaypointOutlineToWaypointColor", true)
                .with("beaconOpacity", block && filled ? 48.0 / 255.0 : 1.0)
                .with("beaconBeamMode", beacon ? "ALL_VISIBLE" : "OFF")
                .with("useBeaconBeamTextures", false)
                .with("beaconBeamExtendsBelowWaypoint", false)
                .with("beaconWidthScale", 0.1);
    }

    private static int defaultColor(int index, int count) {
        // Skysoft uses an RGB gradient for its default group color, including disabled points.
        float progress = (float) index / Math.max(1, count - 1);
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int start = (0x1A87C4 >> shift) & 255;
            int end = (0x2BB1FB >> shift) & 255;
            result |= (int) (start + (end - start) * progress) << shift;
        }
        return result;
    }

    private static String zone(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "private_islands" -> "private_island";
            case "the_farming_islands" -> "the_farming_isles";
            case "dungeons", "catacombs" -> Zone.UNKNOWN.id();
            case "the_rift" -> "rift";
            case "glacite_mineshafts" -> "mineshaft_unknown";
            case "jerrys_workshop" -> "winter";
            default -> WaypointImporter.normalizeZone(raw);
        };
    }

    private static int precise(JsonObject json, String key) {
        double value = number(json, key, Double.NaN);
        if (!Double.isFinite(value) || Math.abs(value) > 30_000_000) {
            throw new IllegalArgumentException("Skysoft coordinate is missing or out of range");
        }
        // Skysoft stores block origins; Waypointer stores centers in sixteenths.
        return Waypoint.snapToPreciseSixteenths(value + 0.5);
    }

    private static double radius(JsonObject json, double fallback) {
        if (!json.has("radius")) return fallback;
        double value = number(json, "radius", fallback);
        if (value < 0.25 || value > 32) throw new IllegalArgumentException("invalid Skysoft radius");
        return value;
    }

    private static int color(JsonObject json, int fallback) {
        return json.has("color") ? integer(json, "color") & 0xFFFFFF : fallback;
    }

    private static void checkVersion(int version) {
        if (version < 1 || version > 2) throw new IllegalArgumentException("unsupported Skysoft waypoint version " + version);
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Skysoft entry must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) throw new IllegalArgumentException("Skysoft " + key + " must be a list");
        return json.getAsJsonArray(key);
    }

    private static String string(JsonObject json, String key, String fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Skysoft " + key + " must be text");
        return value.getAsString();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Skysoft " + key + " must be boolean");
        return value.getAsBoolean();
    }

    private static double number(JsonObject json, String key, double fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Skysoft " + key + " must be numeric");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Skysoft " + key + " must be finite");
        return number;
    }

    private static int integer(JsonObject json, String key) {
        number(json, key, Double.NaN);
        try {
            if (!json.has(key)) throw new ArithmeticException();
            return json.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Skysoft " + key + " must be a 32-bit integer", failure);
        }
    }
}
