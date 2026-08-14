package com.babbur.waypointer.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class CatalogJson {
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern PUBLISHER_ID = Pattern.compile("wp_[A-Za-z0-9_-]{43}");
    private static final Pattern CATALOG_CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,683}");
    private static final int MAX_ROUTES = 50;
    private static final int MAX_TEXT = 512;
    private static final int MAX_PAYLOAD = 256 * 1024;

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
        // Isolate bad rows. Keep wire order, keep the first valid copy of an ID,
        // and fail closed if a non-empty page contains no usable route.
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
        if (!payload.startsWith("WP:")) {
            throw new IllegalArgumentException("Catalog route is not a Waypointer code");
        }
        return new CatalogRouteDetails(summary, payload);
    }

    static CatalogPublishResult parsePublishResult(String json) {
        JsonObject root = object(JsonParser.parseString(json), "publish response");
        CatalogRouteSummary route = parseSummary(object(root.get("route"), "route"));
        String manageToken = optionalString(root, "manageToken", 128, "");
        return new CatalogPublishResult(route, manageToken);
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

        return new CatalogRouteSummary(
                id,
                requiredString(route, "title", 80),
                optionalString(route, "description", 500, ""),
                optionalString(route, "authorName", 40, ""),
                publisherId,
                optionalBoolean(route, "publisherVerified", false),
                optionalString(route, "visibility", 16, "public"),
                requiredString(route, "zoneId", 64),
                requiredString(route, "zoneLabel", 80),
                waypointCount,
                groupCount,
                nonNegativeInt(route, "codecVersion"),
                optionalPositiveInt(route, "version", 1),
                optionalNonNegativeLong(route, "downloads", 0L),
                optionalString(route, "createdAt", MAX_TEXT, ""),
                optionalString(route, "updatedAt", MAX_TEXT, ""),
                optionalString(route, "sharePath", 128, "/r/" + id));
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
