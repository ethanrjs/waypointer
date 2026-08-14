package com.babbur.waypointer.catalog;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CatalogPublicationRegistry {
    private static final int SCHEMA = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RECORDS = 4096;
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern PUBLISHER_ID = Pattern.compile("wp_[A-Za-z0-9_-]{43}");
    private static final Pattern ZONE_ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern PAYLOAD_HASH = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Object IO_LOCK = new Object();

    private final Path file;

    public CatalogPublicationRegistry(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static CatalogPublicationRegistry defaultLocation() {
        return new CatalogPublicationRegistry(FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID)
                .resolve("publisher")
                .resolve("publications.json"));
    }

    public static CatalogPublicationRegistry nextToIdentity(
            PublisherIdentityStore identityStore) {
        Path parent = identityStore.file().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Publisher identity path has no parent");
        }
        return new CatalogPublicationRegistry(parent.resolve("publications.json"));
    }

    public Path file() {
        return file;
    }

    public List<CatalogPublication> list() {
        synchronized (IO_LOCK) {
            return readRecords();
        }
    }

    public List<CatalogPublication> listForPublisher(String publisherId) {
        requirePublisherId(publisherId);
        return list().stream()
                .filter(record -> record.publisherId().equals(publisherId))
                .toList();
    }

    public CatalogPublication recordSuccessfulPublish(
            CatalogPublishReceipt receipt,
            String apiRoot,
            Instant recordedAt) {
        CatalogPublication record = recordFromReceipt(receipt, apiRoot, recordedAt);
        synchronized (IO_LOCK) {
            List<CatalogPublication> records = new ArrayList<>(readRecords());
            records.removeIf(existing -> existing.routeId().equals(record.routeId()));
            records.addFirst(record);
            if (records.size() > MAX_RECORDS) {
                records.subList(MAX_RECORDS, records.size()).clear();
            }
            writeRecords(records);
        }
        return record;
    }

    public boolean remove(String routeId, String publisherId) {
        requireRouteId(routeId);
        requirePublisherId(publisherId);
        synchronized (IO_LOCK) {
            List<CatalogPublication> records = new ArrayList<>(readRecords());
            boolean removed = records.removeIf(record -> record.routeId().equals(routeId)
                    && record.publisherId().equals(publisherId));
            if (removed) writeRecords(records);
            return removed;
        }
    }

    private static CatalogPublication recordFromReceipt(
            CatalogPublishReceipt receipt,
            String apiRoot,
            Instant recordedAt) {
        if (receipt == null || recordedAt == null) {
            throw new IllegalArgumentException("Publication provenance is required");
        }
        CatalogRouteSummary route = receipt.result().route();
        requireRouteId(route.id());
        requirePublisherId(route.publisherId());
        PublisherNamePolicy.requireValid(route.authorName());
        String title = requireCodePoints(route.title(), "title", 1, 80);
        String zoneId = requireMatch(route.zoneId(), "zone ID", ZONE_ID);
        CatalogPublishRequest.Visibility visibility = switch (route.visibility()) {
            case "public" -> CatalogPublishRequest.Visibility.PUBLIC;
            case "unlisted" -> CatalogPublishRequest.Visibility.UNLISTED;
            default -> throw new IllegalArgumentException("Invalid route visibility");
        };
        String normalizedApiRoot = requireApiRoot(apiRoot);
        String sharePath = "/r/" + route.id();
        String serverCreatedAt = route.createdAt() == null ? "" : route.createdAt();
        return new CatalogPublication(
                route.id(), route.publisherId(), route.authorName(), title,
                visibility, zoneId, route.version(), route.codecVersion(),
                serverCreatedAt, sharePath, normalizedApiRoot,
                receipt.payloadSha256(), recordedAt);
    }

    private List<CatalogPublication> readRecords() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectUnsafeFile();
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_FILE_BYTES) throw invalidRegistry(null);
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA
                    || !root.has("publications") || !root.get("publications").isJsonArray()) {
                throw invalidRegistry(null);
            }
            JsonArray array = root.getAsJsonArray("publications");
            if (array.size() > MAX_RECORDS) throw invalidRegistry(null);
            Map<String, CatalogPublication> unique = new LinkedHashMap<>();
            for (JsonElement element : array) {
                CatalogPublication record = parseRecord(element);
                if (unique.putIfAbsent(record.routeId(), record) != null) {
                    throw invalidRegistry(null);
                }
            }
            return unique.values().stream()
                    .sorted(Comparator.comparing(CatalogPublication::recordedAt).reversed())
                    .toList();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CatalogStorageException storage) throw storage;
            throw invalidRegistry(failure);
        }
    }

    private static CatalogPublication parseRecord(JsonElement element) {
        if (!element.isJsonObject()) throw invalidRegistry(null);
        JsonObject object = element.getAsJsonObject();
        String routeId = requireRouteId(string(object, "routeId"));
        String publisherId = requirePublisherId(string(object, "publisherId"));
        String publisherName = PublisherNamePolicy.requireValid(string(object, "publisherName"));
        String title = requireCodePoints(string(object, "title"), "title", 1, 80);
        CatalogPublishRequest.Visibility visibility;
        try {
            visibility = CatalogPublishRequest.Visibility.valueOf(string(object, "visibility"));
        } catch (IllegalArgumentException failure) {
            throw invalidRegistry(failure);
        }
        String zoneId = requireMatch(string(object, "zoneId"), "zone ID", ZONE_ID);
        int version = positiveInt(object, "version");
        int codecVersion = positiveInt(object, "codecVersion");
        String serverCreatedAt = optionalString(object, "serverCreatedAt");
        if (!serverCreatedAt.isEmpty()) Instant.parse(serverCreatedAt);
        String sharePath = string(object, "sharePath");
        if (!sharePath.equals("/r/" + routeId)) throw invalidRegistry(null);
        String apiRoot = requireApiRoot(string(object, "apiRoot"));
        String payloadSha256 = requireMatch(
                string(object, "payloadSha256"), "payload hash", PAYLOAD_HASH);
        Instant recordedAt = Instant.parse(string(object, "recordedAt"));
        return new CatalogPublication(
                routeId, publisherId, publisherName, title, visibility, zoneId,
                version, codecVersion, serverCreatedAt, sharePath, apiRoot,
                payloadSha256, recordedAt);
    }

    private void writeRecords(List<CatalogPublication> records) {
        Path parent = file.getParent();
        if (parent == null) throw new CatalogStorageException("Registry path has no parent");
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(file)) throw invalidRegistry(null);
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA);
            JsonArray array = new JsonArray();
            for (CatalogPublication record : records) array.add(serialize(record));
            root.add("publications", array);
            byte[] json = (root + System.lineSeparator()).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            if (json.length > MAX_FILE_BYTES) throw invalidRegistry(null);
            CatalogAtomicFile.replace(file, json, "publications-");
        } catch (IOException failure) {
            throw new CatalogStorageException("Could not save published route records", failure);
        }
    }

    private static JsonObject serialize(CatalogPublication record) {
        JsonObject object = new JsonObject();
        object.addProperty("routeId", record.routeId());
        object.addProperty("publisherId", record.publisherId());
        object.addProperty("publisherName", record.publisherName());
        object.addProperty("title", record.title());
        object.addProperty("visibility", record.visibility().name());
        object.addProperty("zoneId", record.zoneId());
        object.addProperty("version", record.version());
        object.addProperty("codecVersion", record.codecVersion());
        object.addProperty("serverCreatedAt", record.serverCreatedAt());
        object.addProperty("sharePath", record.sharePath());
        object.addProperty("apiRoot", record.apiRoot());
        object.addProperty("payloadSha256", record.payloadSha256());
        object.addProperty("recordedAt", record.recordedAt().toString());
        return object;
    }

    private void rejectUnsafeFile() {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidRegistry(null);
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            throw invalidRegistry(null);
        }
        return object.get(key).getAsString();
    }

    private static String optionalString(JsonObject object, String key) {
        return object.has(key) ? string(object, key) : "";
    }

    private static int positiveInt(JsonObject object, String key) {
        try {
            int value = object.get(key).getAsInt();
            if (value <= 0) throw invalidRegistry(null);
            return value;
        } catch (RuntimeException failure) {
            throw invalidRegistry(failure);
        }
    }

    private static String requireRouteId(String value) {
        return requireMatch(value, "route ID", ROUTE_ID);
    }

    private static String requirePublisherId(String value) {
        return requireMatch(value, "publisher ID", PUBLISHER_ID);
    }

    private static String requireMatch(String value, String name, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static String requireCodePoints(
            String value, String name, int minimum, int maximum) {
        if (value == null) throw new IllegalArgumentException("Invalid " + name);
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static String requireApiRoot(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().endsWith("/")) {
                throw new IllegalArgumentException("Invalid catalog API root");
            }
            return uri.toString();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid catalog API root", failure);
        }
    }

    private static CatalogStorageException invalidRegistry(Throwable cause) {
        String message = "Published route registry is invalid. Move publications.json aside manually.";
        return cause == null ? new CatalogStorageException(message)
                : new CatalogStorageException(message, cause);
    }
}
