package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Skysoft schema 2; matches Akinsoft/Skysoft's WaypointJson and ShareableConfigCodec. */
final class SkysoftWaypointExporter {
    private SkysoftWaypointExporter() {}

    static String encode(List<WaypointGroup> groups, WaypointerConfig config) {
        return encode(groups, config, WaypointCodec.Options.builder().build());
    }

    static String encode(List<WaypointGroup> groups, WaypointerConfig config, WaypointCodec.Options options) {
        if (groups == null || groups.isEmpty() || groups.size() > 256)
            throw new IllegalArgumentException("Skysoft exports require between 1 and 256 routes.");
        long count = groups.stream().mapToLong(WaypointGroup::size).sum();
        if (count == 0 || count > 20_000)
            throw new IllegalArgumentException("Skysoft exports require between 1 and 20,000 waypoints.");
        JsonArray routes = new JsonArray();
        for (WaypointGroup group : groups) routes.add(group(group, config, options));
        JsonObject library = new JsonObject();
        library.addProperty("version", 2);
        library.add("groups", routes);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("formatVersion", 1);
        envelope.addProperty("type", "waypoints");
        envelope.addProperty("schemaVersion", 2);
        envelope.addProperty("payload", library.toString());
        byte[] json = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > 2_000_000) throw new IllegalArgumentException("Skysoft share is too large.");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(json); }
            String share = SkysoftWaypointImporter.PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (share.length() > 1_000_000) throw new IllegalArgumentException("Skysoft share is too large.");
            return share;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not compress Skysoft share.", failure);
        }
    }

    private static JsonObject group(WaypointGroup group, WaypointerConfig base, WaypointCodec.Options options) {
        WaypointerConfig appearance = WaypointStyles.resolve(base, group, null);
        boolean sequence = group.loadMode() == WaypointGroup.LoadMode.SEQUENCE;
        JsonObject json = new JsonObject();
        // Skysoft share import assigns fresh identities; stored-library IDs are unnecessary on the wire.
        json.addProperty("name", name(group.name(), true));
        json.addProperty("island", island(group.zoneId()));
        json.addProperty("enabled", !options.includeGroupMeta || group.enabled());
        json.addProperty("route", sequence);
        json.addProperty("loop", options.includeGroupMeta && sequence && base.restartRouteWhenComplete());
        json.addProperty("skipAhead", options.includeGroupMeta && group.skipAheadEnabled());
        if (options.includeRadii) json.addProperty("radius", radius(group.defaultRadius()));
        if (options.includeColors) json.addProperty("color", group.staticColor() & 0xFFFFFF);
        JsonArray groupStyles = styles(appearance, null);
        json.add("styles", groupStyles);
        // Skysoft fill and line visibility are group-wide; use the route setting.
        json.addProperty("filled", filled(appearance));
        json.addProperty("lines", sequence && appearance.showRouteLines() && appearance.routeLineOpacity() > 0);
        json.addProperty("scope", "ISLAND");
        JsonArray points = new JsonArray();
        for (Waypoint point : group.waypoints()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", options.includeNames ? name(point.name(), false) : "");
            value.addProperty("x", coordinate(point.preciseX()));
            value.addProperty("y", coordinate(point.preciseY()));
            value.addProperty("z", coordinate(point.preciseZ()));
            value.addProperty("enabled", !point.isDisabled());
            if (options.includeColors) value.addProperty("color", WaypointStyles.waypointColor(base, group, point) & 0xFFFFFF);
            JsonArray pointStyles = styles(WaypointStyles.resolve(base, group, point), point);
            if (!pointStyles.equals(groupStyles)) value.add("styles", pointStyles);
            if (options.includeRadii && point.customRadius() > 0) value.addProperty("radius", radius(point.customRadius()));
            points.add(value);
        }
        json.add("points", points);
        return json;
    }

    private static JsonArray styles(WaypointerConfig config, Waypoint point) {
        JsonArray styles = new JsonArray();
        boolean outline = (config.effectiveBoxStyle() == WaypointerConfig.BoxStyle.OUTLINED
                || config.effectiveBoxStyle() == WaypointerConfig.BoxStyle.FILLED_OUTLINED)
                && config.waypointOutlineOpacity() > 0;
        boolean fill = filled(config) || point != null && point.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT)
                && config.beaconOpacity() > 0;
        if (outline || fill) {
            boolean small = config.waypointMarkerScale() < 0.5
                    || point != null && point.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
            styles.add(small ? "MARKER" : "BLOCK");
        }
        // Skysoft has no CURRENT-only beam mode; preserve beams on eligible points.
        if (config.beaconBeamMode() != WaypointerConfig.BeaconBeamMode.OFF && config.beaconOpacity() > 0
                && (point == null || !point.hasFlag(Waypoint.FLAG_HIDE_BEACON))) styles.add("BEACON");
        return styles;
    }

    private static boolean filled(WaypointerConfig config) {
        return config.effectiveBoxStyle() != WaypointerConfig.BoxStyle.OUTLINED && config.beaconOpacity() > 0;
    }

    private static double coordinate(int precise) {
        double coordinate = precise / 16.0 - 0.5;
        if (Math.abs(coordinate) > 30_000_000)
            throw new IllegalArgumentException("Skysoft coordinates must be within 30,000,000 blocks.");
        return coordinate;
    }

    private static double radius(double radius) {
        if (!Double.isFinite(radius) || radius < 0.25 || radius > 32)
            throw new IllegalArgumentException("Skysoft arrival distance must be between 0.25 and 32 blocks.");
        return radius;
    }

    private static String name(String value, boolean route) {
        if (value.length() > 80 || route && value.isBlank()
                || value.chars().anyMatch(c -> c < ' ' || c == 127 || c == '\u00a7'))
            throw new IllegalArgumentException("Skysoft names must be at most 80 characters without formatting; route names cannot be blank.");
        return value;
    }

    private static String island(String zone) {
        String island = switch (zone) {
            case "the_farming_isles" -> "THE_FARMING_ISLANDS";
            case "rift" -> "THE_RIFT";
            case "winter" -> "JERRYS_WORKSHOP";
            case "mineshaft", "mineshaft_unknown" -> "GLACITE_MINESHAFTS";
            case "dungeon" -> "DUNGEONS";
            default -> zone.toUpperCase(Locale.ROOT);
        };
        if (!Set.of("PRIVATE_ISLAND", "THE_END", "KUUDRA", "DWARVEN_MINES", "GLACITE_TUNNELS",
                "DUNGEON_HUB", "DUNGEONS", "HUB", "THE_FARMING_ISLANDS", "CRYSTAL_HOLLOWS",
                "THE_PARK", "DEEP_CAVERNS", "GOLD_MINE", "GARDEN", "SPIDERS_DEN", "JERRYS_WORKSHOP",
                "THE_RIFT", "GLACITE_MINESHAFTS", "CRIMSON_ISLE", "BACKWATER_BAYOU", "GALATEA",
                "TORRHUS_CANYON", "SAFARI", "LOTUS_ATOLL").contains(island))
            throw new IllegalArgumentException("Skysoft cannot represent the route area: " + zone);
        return island;
    }
}
