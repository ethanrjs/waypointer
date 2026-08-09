package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonHighlight;
import com.babbur.waypointer.dungeon.DungeonHighlightStyle;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Native share format for persisted room-local dungeon routes. */
public final class DungeonRoomShareCodec {
    public static final String MAGIC = "WPD:";
    private static final char COMPACT_BODY_PREFIX = '.';
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_TEXT_PAYLOAD_CHARS = 8 * 1024 * 1024;
    private static final int MAX_DECODED_JSON_CHARS = 8 * 1024 * 1024;
    private static final int MAX_ROUTES_PER_IMPORT = 512;
    private static final int MAX_WAYPOINTS_PER_ROUTE = 512;
    private static final int MAX_TOTAL_WAYPOINTS = 50_000;
    private static final Gson GSON = new GsonBuilder().create();

    private DungeonRoomShareCodec() {}

    public static String encode(Collection<WaypointGroup> routes) {
        List<WaypointGroup> safe = routes == null ? List.of() : new ArrayList<>(routes);
        validateRoutes(safe);
        return MAGIC + COMPACT_BODY_PREFIX + AsciiStreamCodec.encode(deflate(toJson(safe)));
    }

    public static boolean isPayload(String payload) {
        return payload != null && stripMarkdownCodeFence(payload.trim()).startsWith(MAGIC);
    }

    public static Decoded decode(String payload) {
        if (payload == null) throw new IllegalArgumentException("null dungeon route payload");
        String trimmed = stripMarkdownCodeFence(payload.trim());
        if (trimmed.length() > MAX_TEXT_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("dungeon route payload is too large (max "
                    + MAX_TEXT_PAYLOAD_CHARS + " chars)");
        }
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("unrecognized dungeon route payload");
        }

        String body = removeWhitespace(trimmed.substring(MAGIC.length()));
        String json = body.startsWith(String.valueOf(COMPACT_BODY_PREFIX))
                ? inflateCompact(body.substring(1)) : gunzip(body);
        List<WaypointGroup> routes;
        try {
            routes = decodeJson(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("dungeon route payload contained malformed JSON", e);
        }
        validateRoutes(routes);
        return new Decoded(List.copyOf(routes), waypointCount(routes));
    }

    static List<WaypointGroup> decodeLegacyJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("rooms") || !root.get("rooms").isJsonArray()) {
            throw new IllegalArgumentException("legacy dungeon route JSON contained no rooms");
        }
        return legacyRoutes(root.getAsJsonArray("rooms"));
    }

    public static int waypointCount(Collection<WaypointGroup> routes) {
        int total = 0;
        if (routes == null) return total;
        for (WaypointGroup route : routes) if (route != null) total += route.size();
        return total;
    }

    private static List<WaypointGroup> decodeJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("routes") && root.get("routes").isJsonArray()) {
            List<WaypointGroup> routes = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("routes")) {
                routes.add(routeFromJson(element.getAsJsonObject()));
            }
            return routes;
        }
        return decodeLegacyJson(json);
    }

    private static String toJson(Collection<WaypointGroup> routes) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        JsonArray encoded = new JsonArray();
        for (WaypointGroup route : routes) encoded.add(routeToJson(route));
        root.add("routes", encoded);
        return GSON.toJson(root);
    }

    private static JsonObject routeToJson(WaypointGroup route) {
        JsonObject json = new JsonObject();
        json.addProperty("room", route.zoneId());
        json.addProperty("name", route.name());
        json.addProperty("loadMode", route.loadMode().name());
        json.addProperty("defaultRadius", route.defaultRadius());
        json.addProperty("skipAhead", route.skipAheadEnabled());
        JsonArray waypoints = new JsonArray();
        for (Waypoint waypoint : route.waypoints()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("x", waypoint.x());
            encoded.addProperty("y", waypoint.y());
            encoded.addProperty("z", waypoint.z());
            encoded.addProperty("px", waypoint.preciseX());
            encoded.addProperty("py", waypoint.preciseY());
            encoded.addProperty("pz", waypoint.preciseZ());
            if (waypoint.hasName()) encoded.addProperty("name", waypoint.name());
            encoded.addProperty("color", waypoint.color());
            encoded.addProperty("flags", waypoint.flags());
            if (waypoint.customRadius() > 0.0) encoded.addProperty("radius", waypoint.customRadius());
            waypoints.add(encoded);
        }
        json.add("waypoints", waypoints);
        return json;
    }

    private static WaypointGroup routeFromJson(JsonObject json) {
        String room = DungeonRoomCatalogEntry.normalizeId(string(json, "room", ""));
        if (room.isBlank()) throw new IllegalArgumentException("dungeon route has no room id");
        WaypointGroup route = WaypointGroup.create(string(json, "name", "Dungeon Route"), room);
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setLoadMode(parseLoadMode(string(json, "loadMode", "SEQUENCE")));
        route.setDefaultRadius(decimal(json, "defaultRadius", Waypoint.DEFAULT_REACH_RADIUS));
        route.setSkipAheadEnabled(bool(json, "skipAhead", false));
        if (json.has("waypoints")) {
            for (JsonElement element : json.getAsJsonArray("waypoints")) {
                JsonObject encoded = element.getAsJsonObject();
                int x = integer(encoded, "x", 0);
                int y = integer(encoded, "y", 70);
                int z = integer(encoded, "z", 0);
                route.add(new Waypoint(
                        x, y, z, string(encoded, "name", ""),
                        integer(encoded, "color", Waypoint.DEFAULT_COLOR),
                        integer(encoded, "flags", 0), decimal(encoded, "radius", 0.0),
                        Waypoint.TEMP_NONE, 0L,
                        integer(encoded, "px", Waypoint.preciseBlockCenter(x)),
                        integer(encoded, "py", Waypoint.preciseBlockCenter(y)),
                        integer(encoded, "pz", Waypoint.preciseBlockCenter(z))));
            }
        }
        return route;
    }

    private static List<WaypointGroup> legacyRoutes(JsonArray rooms) {
        List<WaypointGroup> routes = new ArrayList<>();
        for (JsonElement element : rooms) {
            JsonObject room = element.getAsJsonObject();
            List<DungeonWaypoint> waypoints = new ArrayList<>();
            if (room.has("waypoints")) {
                for (JsonElement waypoint : room.getAsJsonArray("waypoints")) {
                    waypoints.add(legacyWaypoint(waypoint.getAsJsonObject()));
                }
            }
            if (!waypoints.isEmpty()) {
                routes.add(DungeonRouteGroupAdapter.fromWaypoints(
                        string(room, "id", ""), string(room, "name", "Dungeon Route"), waypoints));
            }
        }
        return routes;
    }

    private static DungeonWaypoint legacyWaypoint(JsonObject json) {
        List<DungeonHighlight> highlights = new ArrayList<>();
        if (json.has("highlights")) {
            for (JsonElement element : json.getAsJsonArray("highlights")) {
                JsonObject highlight = element.getAsJsonObject();
                highlights.add(new DungeonHighlight(
                        integer(highlight, "x", 0), integer(highlight, "y", 70),
                        integer(highlight, "z", 0),
                        parseHighlightStyle(string(highlight, "style", "OUTLINE")),
                        integer(highlight, "color", DungeonHighlight.INHERIT_COLOR)));
            }
        }
        int secretIndex = integer(json, "secretIndex", 1);
        return new DungeonWaypoint(
                string(json, "id", "secret-" + secretIndex), secretIndex,
                DungeonSecretCategory.fromId(string(json, "category", "default")),
                DungeonWaypointTrigger.fromId(string(json, "trigger", "")),
                integer(json, "x", 0), integer(json, "y", 70), integer(json, "z", 0),
                string(json, "name", ""), highlights,
                integer(json, "color", DungeonWaypoint.INHERIT_COLOR));
    }

    private static void validateRoutes(Collection<WaypointGroup> routes) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("dungeon route payload contained no routes");
        }
        if (routes.size() > MAX_ROUTES_PER_IMPORT) {
            throw new IllegalArgumentException("dungeon route payload contains too many routes ("
                    + routes.size() + " > " + MAX_ROUTES_PER_IMPORT + ")");
        }
        int total = 0;
        for (WaypointGroup route : routes) {
            if (route == null) throw new IllegalArgumentException("dungeon route payload contained a null route");
            if (route.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
                throw new IllegalArgumentException("dungeon route payload contained a regular route");
            }
            if (route.zoneId().isBlank()) {
                throw new IllegalArgumentException("dungeon route payload contained a route with no room");
            }
            if (route.size() > MAX_WAYPOINTS_PER_ROUTE) {
                throw new IllegalArgumentException("dungeon route \"" + route.name()
                        + "\" has too many waypoints (" + route.size()
                        + " > " + MAX_WAYPOINTS_PER_ROUTE + ")");
            }
            total += route.size();
            if (total > MAX_TOTAL_WAYPOINTS) {
                throw new IllegalArgumentException("dungeon route payload contains too many waypoints ("
                        + total + " > " + MAX_TOTAL_WAYPOINTS + ")");
            }
        }
        if (total == 0) throw new IllegalArgumentException("dungeon route payload contained no waypoints");
    }

    private static byte[] deflate(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater)) {
            compressed.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("dungeon route export failed", e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static String gunzip(String body) {
        byte[] compressed = decodeBase64Bytes(body);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readLimited(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
    }

    private static String inflateCompact(String body) {
        byte[] compressed;
        try {
            compressed = AsciiStreamCodec.decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return readLimited(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
    }

    private static String readLimited(java.io.InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_DECODED_JSON_CHARS) {
                throw new IllegalArgumentException("decoded dungeon route JSON is too large (max "
                        + MAX_DECODED_JSON_CHARS + " bytes)");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] decodeBase64Bytes(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(text);
        }
    }

    private static String removeWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    private static String stripMarkdownCodeFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) return text;
        int bodyStart = 3;
        int newline = text.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;
        String body = text.substring(bodyStart, text.length() - 3).strip();
        return body.isEmpty() ? text : body;
    }

    private static WaypointGroup.LoadMode parseLoadMode(String raw) {
        try {
            return WaypointGroup.LoadMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return WaypointGroup.LoadMode.SEQUENCE;
        }
    }

    private static DungeonHighlightStyle parseHighlightStyle(String raw) {
        try {
            return DungeonHighlightStyle.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return DungeonHighlightStyle.OUTLINE;
        }
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return json.has(name) ? json.get(name).getAsInt() : fallback;
    }

    private static double decimal(JsonObject json, String name, double fallback) {
        return json.has(name) ? json.get(name).getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject json, String name, boolean fallback) {
        return json.has(name) ? json.get(name).getAsBoolean() : fallback;
    }

    private static String string(JsonObject json, String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }

    public record Decoded(List<WaypointGroup> routes, int waypointCount) {
        public Decoded {
            routes = List.copyOf(routes);
        }
    }
}
