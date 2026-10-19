package com.babbur.waypointer.catalog;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PublishedSources {
    private static final int SCHEMA = 2;

    private static final int SCHEMA_1_GROUP_CAP = 64;
    private static final int MAX_ROUTES = 500;
    private static final int MAX_GROUPS = CatalogProtocol.MAX_DUNGEON_GROUPS;

    private static PublishedSources active;

    private final Path file;
    private Map<String, List<String>> links;

    PublishedSources(Path file) {
        this.file = file;
    }

    public static void install(Path file) {
        active = new PublishedSources(file);
    }

    public static PublishedSources active() {
        return active;
    }

    public synchronized List<String> groups(String routeId) {
        return links().getOrDefault(routeId, List.of());
    }

    public synchronized void remember(String routeId, List<String> groupIds) {
        if (routeId == null || groupIds == null || groupIds.isEmpty()) return;
        Map<String, List<String>> map = links();
        map.remove(routeId);

        if (groupIds.size() <= MAX_GROUPS) map.put(routeId, List.copyOf(groupIds));
        while (map.size() > MAX_ROUTES) map.remove(map.keySet().iterator().next());
        save();
    }

    public synchronized void forget(String routeId) {
        if (links().remove(routeId) != null) save();
    }

    private Map<String, List<String>> links() {
        if (links != null) return links;
        links = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return links;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int schema = root.get("schema").getAsInt();
            if (schema != 1 && schema != SCHEMA) return links;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("routes").entrySet()) {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : entry.getValue().getAsJsonArray()) ids.add(id.getAsString());
                if (ids.isEmpty() || ids.size() > MAX_GROUPS
                        || schema == 1 && ids.size() >= SCHEMA_1_GROUP_CAP) continue;
                links.put(entry.getKey(), List.copyOf(ids));
            }
        } catch (IOException | RuntimeException failure) {
            Waypointer.LOGGER.warn("Ignoring unreadable published route links in {}", file, failure);
        }
        return links;
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonObject routes = new JsonObject();
        links.forEach((routeId, ids) -> {
            JsonArray array = new JsonArray();
            ids.forEach(array::add);
            routes.add(routeId, array);
        });
        root.add("routes", routes);
        try {
            Files.createDirectories(file.getParent());
            CatalogAtomicFile.replace(file, (root + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    "published-sources-");
        } catch (IOException | RuntimeException failure) {
            Waypointer.LOGGER.warn("Could not save published route links to {}", file, failure);
        }
    }
}
