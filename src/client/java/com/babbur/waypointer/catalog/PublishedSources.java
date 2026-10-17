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

/**
 * Which local routes each of the player's published routes came from, so My routes can tell
 * when a route changed since it was published and send the changes in one step. Kept apart from
 * the publication records so older versions read those unchanged.
 */
public final class PublishedSources {
    private static final int SCHEMA = 1;
    private static final int MAX_ROUTES = 500;
    private static final int MAX_GROUPS = 64;

    private static PublishedSources active;

    private final Path file;
    private Map<String, List<String>> links;

    PublishedSources(Path file) {
        this.file = file;
    }

    public static void install(Path file) {
        active = new PublishedSources(file);
    }

    /** The installed store, or null before the client starts (tests). */
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
        map.put(routeId, List.copyOf(groupIds.subList(0, Math.min(MAX_GROUPS, groupIds.size()))));
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
            if (root.get("schema").getAsInt() != SCHEMA) return links;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("routes").entrySet()) {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : entry.getValue().getAsJsonArray()) ids.add(id.getAsString());
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
