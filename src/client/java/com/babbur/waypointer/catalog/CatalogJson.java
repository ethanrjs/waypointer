package com.babbur.waypointer.catalog;

import com.babbur.waypointer.core.RouteIcon;
import com.babbur.waypointer.core.SafeText;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CatalogJson {
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern PUBLISHER_ID = Pattern.compile("wp_[A-Za-z0-9_-]{43}");
    private static final Pattern CATALOG_CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,683}");
    private static final int MAX_ROUTES = 50;
    private static final int MAX_TEXT = 512;
    private static final int MAX_PAYLOAD = CatalogProtocol.MAX_PAYLOAD_CHARS;

    private CatalogJson() {
    }

    static CatalogPage parsePage(String json) {
        JsonObject root = object(JsonParser.parseString(json), "catalog response");
        JsonArray values = array(root.get("routes"), "routes");
        if (values.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("Catalog response has too many routes");
        }

        List<CatalogRouteSummary> routes = new ArrayList<>(values.size());
        Set<String> ids = new HashSet<>();
        int rejectedRoutes = 0;
        for (JsonElement value : values) {
            try {
                CatalogRouteSummary route = parseSummary(object(value, "route"));
                if (!ids.add(route.id())) {
                    rejectedRoutes++;
                    continue;
                }
                routes.add(route);
            } catch (IllegalArgumentException ignored) {
                rejectedRoutes++;
            }
        }
        if (rejectedRoutes > 0 && routes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Catalog response has no valid routes; rejected " + rejectedRoutes);
        }
        String nextCursor = optionalNullableString(root, "nextCursor", 683);
        if (nextCursor != null && !CATALOG_CURSOR.matcher(nextCursor).matches()) {
            throw new IllegalArgumentException("nextCursor has an invalid format");
        }
        return new CatalogPage(
                routes, optionalBoolean(root, "hasMore", false), nextCursor);
    }

    static CatalogRouteDetails parseDetails(String json) {
        JsonObject root = object(JsonParser.parseString(json), "route response");
        JsonObject route = object(root.get("route"), "route");
        CatalogRouteSummary summary = parseSummary(route);
        String payload = requiredUtf8String(route, "payload", MAX_PAYLOAD);
        if (!payload.startsWith("WP:")
                && !payload.startsWith(DungeonRoomShareCodec.MAGIC)) {
            throw new IllegalArgumentException("Catalog route is not a Waypointer code");
        }
        return new CatalogRouteDetails(summary, payload);
    }

    private static final int MAX_PREVIEW_GROUPS = 256;
    private static final int MAX_PREVIEW_POINTS = 5_000;
    private static final int MAX_COORDINATE = 30_000_000;

    static CatalogRoutePreviewData parsePreview(String json) {
        JsonObject root = object(JsonParser.parseString(json), "preview response");
        JsonArray groupValues = array(root.get("groups"), "groups");
        if (groupValues.size() > MAX_PREVIEW_GROUPS) {
            throw new IllegalArgumentException("Preview has too many groups");
        }
        List<CatalogRoutePreviewData.Group> groups = new ArrayList<>();
        int points = 0;
        for (JsonElement groupValue : groupValues) {
            JsonObject group = object(groupValue, "group");
            boolean dungeon = "dungeon".equals(optionalString(group, "kind", 16, "regular"));
            JsonArray pointValues = array(group.get("points"), "points");
            List<CatalogRoutePreviewData.Point> parsed = new ArrayList<>(pointValues.size());
            for (JsonElement pointValue : pointValues) {
                if (++points > MAX_PREVIEW_POINTS) {
                    throw new IllegalArgumentException("Preview has too many points");
                }
                JsonArray point = array(pointValue, "point");
                if (point.size() != 5) throw new IllegalArgumentException("Preview point needs 5 numbers");
                parsed.add(new CatalogRoutePreviewData.Point(
                        boundedInt(point.get(0), MAX_COORDINATE), boundedInt(point.get(1), 4096),
                        boundedInt(point.get(2), MAX_COORDINATE),
                        boundedInt(point.get(3), Integer.MAX_VALUE) & 0xFFFFFF,
                        boundedInt(point.get(4), 1) == 1));
            }
            groups.add(new CatalogRoutePreviewData.Group(dungeon, parsed));
        }
        return new CatalogRoutePreviewData(
                optionalPositiveInt(root, "version", 1),
                Math.max(points, optionalPositiveInt(root, "totalPoints", Math.max(1, points))),
                optionalBoolean(root, "truncated", false),
                groups);
    }

    private static int boundedInt(JsonElement value, int limit) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Preview point must be numbers");
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || Math.abs(number) > limit) {
            throw new IllegalArgumentException("Preview point is out of range");
        }
        return (int) number;
    }

    static CatalogPublishResult parsePublishResult(String json) {
        JsonObject root = object(JsonParser.parseString(json), "publish response");
        CatalogRouteSummary route = parseSummary(object(root.get("route"), "route"));
        String manageToken = optionalString(root, "manageToken", 128, "");
        return new CatalogPublishResult(route, manageToken);
    }

    static CatalogRouteSummary parseRouteResponse(String json) {
        JsonObject root = object(JsonParser.parseString(json), "route response");
        return parseSummary(object(root.get("route"), "route"));
    }

    static String updateBody(CatalogRouteUpdate update) {
        if (update == null || update.isEmpty()) {
            throw new IllegalArgumentException("Route update has no changes");
        }
        JsonObject root = new JsonObject();
        if (update.title() != null) {
            root.addProperty("title", requireLength(update.title(), "title", 80));
        }
        if (update.description() != null) {
            root.addProperty("description",
                    requireTrimmedLength(update.description(), "description", 10, 500));
        }
        if (update.visibility() != null) {
            root.addProperty("visibility", update.visibility().wireName());
        }
        if (update.payload() != null) {
            root.addProperty("payload", requireUtf8Length(update.payload(), "payload", MAX_PAYLOAD));
            if (update.zoneId() != null && !update.zoneId().isBlank()) {
                root.addProperty("zoneId", requireLength(update.zoneId(), "zoneId", 64));
            }
        }
        return root.toString();
    }

    static CatalogApiException parseError(int status, String json) {
        try {
            JsonObject root = object(JsonParser.parseString(json), "error response");
            JsonObject error = object(root.get("error"), "error");
            String code = optionalString(error, "code", 80, "unknown");
            String message = optionalString(error, "message", 300,
                    "The catalog request failed.");
            return new CatalogApiException(status, code, message);
        } catch (RuntimeException ignored) {
            return new CatalogApiException(status, "invalid_response",
                    "The catalog returned an invalid error response.");
        }
    }

    static String publishBody(CatalogPublishRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("payload",
                requireUtf8Length(request.payload(), "payload", MAX_PAYLOAD));
        root.addProperty("title", requireLength(request.title(), "title", 80));
        root.addProperty("description",
                requireTrimmedLength(request.description(), "description", 10, 500));
        root.addProperty("visibility", request.visibility().wireName());
        if (request.zoneId() != null && !request.zoneId().isBlank()) {
            root.addProperty("zoneId", requireLength(request.zoneId(), "zoneId", 64));
        }
        if (request.publisherName() != null) {
            root.addProperty("publisherName",
                    PublisherNamePolicy.requireValid(request.publisherName()));
        }
        return root.toString();
    }

    private static CatalogRouteSummary parseSummary(JsonObject route) {
        String id = requiredString(route, "id", 22);
        if (!ROUTE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Catalog route has an invalid ID");
        }
        String publisherId = optionalString(route, "publisherId", 46, "");
        if (!publisherId.isEmpty() && !PUBLISHER_ID.matcher(publisherId).matches()) {
            throw new IllegalArgumentException("Catalog route has an invalid publisher ID");
        }
        int waypointCount = nonNegativeInt(route, "waypointCount");
        int groupCount = nonNegativeInt(route, "groupCount");
        if (waypointCount == 0 || groupCount == 0) {
            throw new IllegalArgumentException("Catalog route is empty");
        }

        String title = SafeText.line(requiredString(route, "title", 80));
        if (title.isEmpty()) throw new IllegalArgumentException("title has an invalid length");
        String zoneId = requiredString(route, "zoneId", 64);
        String zoneLabel = SafeText.line(requiredString(route, "zoneLabel", 80));

        CrystalHollowsStructure structure = CrystalHollowsStructureZones.structureFor(zoneId);
        if (structure != null) zoneLabel = structure.displayName();
        return new CatalogRouteSummary(
                id,
                title,
                SafeText.paragraph(optionalString(route, "description", 500, "")),
                SafeText.line(optionalString(route, "authorName", 40, "")),
                publisherId,
                optionalBoolean(route, "publisherVerified", false),
                optionalString(route, "visibility", 16, "public"),
                zoneId,
                zoneLabel.isEmpty() ? zoneId : zoneLabel,
                waypointCount,
                groupCount,
                positiveInt(route, "codecVersion"),
                optionalPositiveInt(route, "version", 1),
                optionalNonNegativeLong(route, "downloads", 0L),
                optionalString(route, "createdAt", MAX_TEXT, ""),
                optionalString(route, "updatedAt", MAX_TEXT, ""),
                optionalString(route, "sharePath", 128, "/r/" + id),
                optionalTags(route),
                optionalIcon(route));
    }

    private static String optionalIcon(JsonObject route) {
        JsonElement value = route.get("icon");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        return RouteIcon.normalize(value.getAsString());
    }

    private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private static List<String> optionalTags(JsonObject route) {
        JsonElement value = route.get("tags");
        if (value == null || !value.isJsonArray()) return List.of();
        List<String> tags = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (tags.size() >= 8) break;
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
            String tag = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (TAG.matcher(tag).matches() && !tags.contains(tag)) tags.add(tag);
        }
        return List.copyOf(tags);
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String key, int maximum) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = object.get(key).getAsString();
        if (value.isBlank() || codePointLength(value) > maximum) {
            throw new IllegalArgumentException(key + " has an invalid length");
        }
        return value;
    }

    private static String requiredUtf8String(
            JsonObject object, String key, int maximumBytes) {
        String value = requiredString(object, key, maximumBytes);
        if (value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(key + " has an invalid length");
        }
        return value;
    }

    private static String optionalString(
            JsonObject object, String key, int maximum, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        if (!object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String value = object.get(key).getAsString();
        if (codePointLength(value) > maximum) {
            throw new IllegalArgumentException(key + " is too long");
        }
        return value;
    }

    private static String optionalNullableString(
            JsonObject object, String key, int maximum) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        if (!object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        String value = object.get(key).getAsString();
        if (value.isEmpty() || codePointLength(value) > maximum) {
            throw new IllegalArgumentException(key + " has an invalid length");
        }
        return value;
    }

    private static int nonNegativeInt(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        int value;
        try {
            value = object.get(key).getAsBigDecimal().intValueExact();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
        if (value < 0) throw new IllegalArgumentException(key + " must not be negative");
        return value;
    }

    private static int optionalPositiveInt(JsonObject object, String key, int fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        int value = nonNegativeInt(object, key);
        if (value == 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static int positiveInt(JsonObject object, String key) {
        int value = nonNegativeInt(object, key);
        if (value == 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static long optionalNonNegativeLong(
            JsonObject object, String key, long fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        if (!object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        long value;
        try {
            value = object.get(key).getAsBigDecimal().longValueExact();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
        if (value < 0L) throw new IllegalArgumentException(key + " must not be negative");
        return value;
    }

    private static boolean optionalBoolean(
            JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        if (!object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return object.get(key).getAsBoolean();
    }

    private static String requireLength(String value, String name, int maximum) {
        if (value == null || value.isBlank() || codePointLength(value) > maximum) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        return value;
    }

    private static String requireUtf8Length(
            String value, String name, int maximumBytes) {
        String bounded = requireLength(value, name, maximumBytes);
        if (bounded.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        return bounded;
    }

    private static String requireTrimmedLength(
            String value, String name, int minimum, int maximum) {
        if (value == null) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        String trimmed = value.strip();
        int length = codePointLength(trimmed);
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        return trimmed;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
