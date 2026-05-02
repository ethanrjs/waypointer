package dev.ethan.waypointer.dungeon.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.AsyncSaver;
import dev.ethan.waypointer.dungeon.Direction;
import dev.ethan.waypointer.dungeon.DungeonHighlight;
import dev.ethan.waypointer.dungeon.DungeonHighlightStyle;
import dev.ethan.waypointer.dungeon.DungeonMapMath;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import dev.ethan.waypointer.dungeon.DungeonWaypointTrigger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Catalog of dungeon room definitions and user-authored secret routes.
 *
 * <p>The bundled catalog is intentionally Waypointer-authored seed/demo data
 * only. We do not ship Skyblocker/DungeonRoomsMod Catacombs data because their
 * attribution traces many room skeletons and secret waypoints to GPL-3.0 data,
 * while this project is PolyForm Noncommercial.
 */
public final class DungeonRoomData {

    public interface BlockLookup {
        String blockIdAt(int x, int y, int z);
    }

    public static final int SCHEMA_VERSION = 1;

    private static final String CUSTOM_FILE_NAME = "dungeon_rooms.json";
    private static final String BUNDLED_RESOURCE =
            "/assets/" + Waypointer.MOD_ID + "/dungeons/catacombs/rooms.json";
    private static final long SAVE_DEBOUNCE_MS = 400L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, DungeonRoomDefinition> BUNDLED =
            loadBundledDefinitions();
    private static final AtomicReference<Map<String, DungeonRoomDefinition>> CUSTOM =
            new AtomicReference<>(Collections.emptyMap());
    private static final Map<DungeonRoomShape, List<DungeonWaypoint>> DEMO = buildDemo();

    private static Path customFile;
    private static AsyncSaver saver;

    private DungeonRoomData() {}

    public static void loadDefaultCustomStore() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        loadCustomStore(dir.resolve(CUSTOM_FILE_NAME));
    }

    public static void loadCustomStore(Path file) {
        customFile = file;
        saver = new AsyncSaver("dungeon-rooms", DungeonRoomData::writeCustomStore, SAVE_DEBOUNCE_MS);
        CUSTOM.set(readDefinitions(file));
    }

    public static void flush() {
        if (saver != null) saver.flush();
    }

    public static Collection<DungeonRoomDefinition> customDefinitions() {
        return CUSTOM.get().values();
    }

    public static Collection<DungeonRoomDefinition> allDefinitions() {
        Map<String, DungeonRoomDefinition> all = new LinkedHashMap<>(BUNDLED);
        all.putAll(CUSTOM.get());
        return List.copyOf(all.values());
    }

    public static DungeonRoomDefinition definition(String id) {
        String norm = DungeonRoomDefinition.normalizeId(id);
        DungeonRoomDefinition custom = CUSTOM.get().get(norm);
        return custom != null ? custom : BUNDLED.get(norm);
    }

    public static boolean isCustomDefinition(String id) {
        return CUSTOM.get().containsKey(DungeonRoomDefinition.normalizeId(id));
    }

    public static DungeonRoom withMatchedDefinition(DungeonRoom room, BlockLookup lookup) {
        DungeonRoomDefinition definition = match(room, lookup);
        return definition == null ? room : room.withDefinition(definition.id(), definition.displayName());
    }

    public static DungeonRoomDefinition match(DungeonRoom room, BlockLookup lookup) {
        if (room == null) return null;
        if (room.hasRoomId()) return definition(room.roomId());

        List<DungeonRoomDefinition> customCandidates = matchingDefinitions(room, CUSTOM.get().values());
        List<DungeonRoomDefinition> candidates = matchingDefinitions(room, allDefinitions());
        if (candidates.isEmpty()) return null;

        if (lookup != null) {
            List<DungeonRoomDefinition> fingerprintMatches = new ArrayList<>();
            for (DungeonRoomDefinition definition : candidates) {
                if (definition.hasFingerprints() && fingerprintsMatch(room, definition, lookup)) {
                    fingerprintMatches.add(definition);
                }
            }
            if (fingerprintMatches.size() == 1) return fingerprintMatches.get(0);
        }

        List<DungeonRoomDefinition> customFallback = withoutFingerprints(customCandidates);
        if (customFallback.size() == 1) return customFallback.get(0);

        List<DungeonRoomDefinition> fallback = withoutFingerprints(candidates);
        return fallback.size() == 1 ? fallback.get(0) : null;
    }

    private static List<DungeonRoomDefinition> matchingDefinitions(
            DungeonRoom room, Collection<DungeonRoomDefinition> definitions) {
        List<DungeonRoomDefinition> out = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition.type() == room.type() && definition.shape() == room.shape()) {
                out.add(definition);
            }
        }
        return out;
    }

    private static List<DungeonRoomDefinition> withoutFingerprints(
            List<DungeonRoomDefinition> candidates) {
        List<DungeonRoomDefinition> fallback = new ArrayList<>(candidates.size());
        for (DungeonRoomDefinition definition : candidates) {
            if (!definition.hasFingerprints()) fallback.add(definition);
        }
        return fallback;
    }

    public static List<DungeonWaypoint> waypointsFor(DungeonRoom room) {
        DungeonRoomDefinition definition = room == null ? null : definitionForRoom(room);
        return definition == null ? List.of() : definition.waypoints();
    }

    public static DungeonRoomDefinition defineRoom(String id, String displayName, DungeonRoom room) {
        if (room == null) throw new IllegalArgumentException("room is required");
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                id,
                displayName,
                room.type(),
                room.shape(),
                List.of(),
                List.of());
        putCustom(definition);
        return definition;
    }

    public static DungeonRoomDefinition renameRoom(String roomId, String displayName) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        DungeonRoomDefinition renamed = definition.withDisplayName(displayName);
        putCustom(renamed);
        return renamed;
    }

    public static DungeonRoomDefinition addFingerprint(String roomId, DungeonRoomFingerprint fingerprint) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonRoomFingerprint> next = new ArrayList<>(definition.fingerprints());
        next.add(fingerprint);
        DungeonRoomDefinition updated = definition.withFingerprints(next);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition addWaypoint(String roomId, DungeonWaypoint waypoint) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> next = new ArrayList<>(definition.waypoints());
        next.add(waypoint);
        DungeonRoomDefinition updated = definition.withWaypoints(next);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition removeWaypoint(String roomId, int index) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> next = new ArrayList<>(definition.waypoints());
        next.remove(index);
        DungeonRoomDefinition updated = definition.withWaypoints(next);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition setWaypointTrigger(String roomId, int waypointIndex,
                                                          DungeonWaypointTrigger trigger) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> waypoints = new ArrayList<>(definition.waypoints());
        waypoints.set(waypointIndex, waypoints.get(waypointIndex).withTrigger(trigger));
        DungeonRoomDefinition updated = definition.withWaypoints(waypoints);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition moveWaypoint(String roomId, int waypointIndex,
                                                     int x, int y, int z) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> waypoints = new ArrayList<>(definition.waypoints());
        waypoints.set(waypointIndex, waypoints.get(waypointIndex).withPosition(x, y, z));
        DungeonRoomDefinition updated = definition.withWaypoints(waypoints);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition addHighlight(String roomId, int waypointIndex,
                                                    DungeonHighlight highlight) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> waypoints = new ArrayList<>(definition.waypoints());
        DungeonWaypoint waypoint = waypoints.get(waypointIndex);
        List<DungeonHighlight> highlights = new ArrayList<>(waypoint.highlights());
        highlights.add(highlight);
        waypoints.set(waypointIndex, waypoint.withHighlights(highlights));
        DungeonRoomDefinition updated = definition.withWaypoints(waypoints);
        putCustom(updated);
        return updated;
    }

    public static DungeonRoomDefinition removeHighlight(String roomId, int waypointIndex,
                                                       int highlightIndex) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<DungeonWaypoint> waypoints = new ArrayList<>(definition.waypoints());
        DungeonWaypoint waypoint = waypoints.get(waypointIndex);
        List<DungeonHighlight> highlights = new ArrayList<>(waypoint.highlights());
        highlights.remove(highlightIndex);
        waypoints.set(waypointIndex, waypoint.withHighlights(highlights));
        DungeonRoomDefinition updated = definition.withWaypoints(waypoints);
        putCustom(updated);
        return updated;
    }

    public static void clearCustom(String roomId) {
        String norm = DungeonRoomDefinition.normalizeId(roomId);
        CUSTOM.updateAndGet(prev -> {
            if (!prev.containsKey(norm)) return prev;
            Map<String, DungeonRoomDefinition> next = new LinkedHashMap<>(prev);
            next.remove(norm);
            return Map.copyOf(next);
        });
        markDirty();
    }

    public static void clearAllCustom() {
        CUSTOM.set(Collections.emptyMap());
        markDirty();
    }

    /**
     * Compatibility helper for older tests and the `/wpd test` command.
     * Runtime-only demo entries are converted into a persistent room definition
     * keyed by {@code roomKey}.
     */
    public static void addCustom(String roomKey, DungeonWaypoint waypoint) {
        String id = DungeonRoomDefinition.normalizeId(roomKey);
        DungeonRoomDefinition existing = CUSTOM.get().get(id);
        DungeonRoomType type = typeFromIdentityKey(roomKey);
        DungeonRoomShape shape = shapeFromIdentityKey(roomKey);
        DungeonRoomDefinition base = existing == null
                ? new DungeonRoomDefinition(id, roomKey, type, shape, List.of(), List.of())
                : existing;
        List<DungeonWaypoint> next = new ArrayList<>(base.waypoints());
        next.add(waypoint);
        putCustom(base.withWaypoints(next));
    }

    public static List<DungeonWaypoint> demoFor(DungeonRoomShape shape) {
        return DEMO.getOrDefault(shape, DEMO.get(DungeonRoomShape.ONE_BY_ONE));
    }

    public static Map<DungeonRoomShape, List<DungeonWaypoint>> demoWaypoints() {
        return DEMO;
    }

    static Map<String, DungeonRoomDefinition> parseDefinitions(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray rooms = root.has("rooms") ? root.getAsJsonArray("rooms") : new JsonArray();
        Map<String, DungeonRoomDefinition> out = new LinkedHashMap<>();
        for (JsonElement element : rooms) {
            DungeonRoomDefinition definition = definitionFromJson(element.getAsJsonObject());
            out.put(definition.id(), definition);
        }
        return Map.copyOf(out);
    }

    static String toJson(Collection<DungeonRoomDefinition> definitions) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        JsonArray rooms = new JsonArray();
        for (DungeonRoomDefinition definition : definitions) {
            rooms.add(definitionToJson(definition));
        }
        root.add("rooms", rooms);
        return GSON.toJson(root);
    }

    private static DungeonRoomDefinition definitionForRoom(DungeonRoom room) {
        if (room.hasRoomId()) return definition(room.roomId());
        return match(room, null);
    }

    private static boolean fingerprintsMatch(DungeonRoom room, DungeonRoomDefinition definition,
                                             BlockLookup lookup) {
        for (DungeonRoomFingerprint fingerprint : definition.fingerprints()) {
            int[] world = DungeonMapMath.relativeToActual(
                    room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                    fingerprint.x(), fingerprint.y(), fingerprint.z());
            String actual = DungeonRoomFingerprint.normalizeBlockId(
                    lookup.blockIdAt(world[0], world[1], world[2]));
            if (!fingerprint.blockId().equals(actual)) return false;
        }
        return true;
    }

    private static DungeonRoomDefinition requireCustom(String id) {
        String norm = DungeonRoomDefinition.normalizeId(id);
        DungeonRoomDefinition definition = CUSTOM.get().get(norm);
        if (definition == null) throw new IllegalArgumentException("Unknown custom room: " + id);
        return definition;
    }

    private static void putCustom(DungeonRoomDefinition definition) {
        CUSTOM.updateAndGet(prev -> {
            Map<String, DungeonRoomDefinition> next = new LinkedHashMap<>(prev);
            next.put(definition.id(), definition);
            return Map.copyOf(next);
        });
        markDirty();
    }

    private static void markDirty() {
        if (saver != null) saver.markDirty();
    }

    private static void writeCustomStore() {
        if (customFile == null) return;
        try {
            Files.createDirectories(customFile.getParent());
            Path tmp = customFile.resolveSibling(customFile.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(CUSTOM.get().values()));
            Files.move(tmp, customFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Waypointer.LOGGER.error("Failed to save dungeon room data to {}", customFile, e);
        }
    }

    private static Map<String, DungeonRoomDefinition> readDefinitions(Path file) {
        try {
            if (file == null || !Files.exists(file)) return Collections.emptyMap();
            return parseDefinitions(Files.readString(file));
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to load dungeon room data from {}", file, e);
            return Collections.emptyMap();
        }
    }

    private static Map<String, DungeonRoomDefinition> loadBundledDefinitions() {
        try (InputStream stream = DungeonRoomData.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream == null) return Collections.emptyMap();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return parseDefinitions(GSON.toJson(root));
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to load bundled dungeon room data", e);
            return Collections.emptyMap();
        }
    }

    private static JsonObject definitionToJson(DungeonRoomDefinition definition) {
        JsonObject json = new JsonObject();
        json.addProperty("id", definition.id());
        json.addProperty("name", definition.displayName());
        json.addProperty("type", definition.type().name());
        json.addProperty("shape", definition.shape().name());

        JsonArray fingerprints = new JsonArray();
        for (DungeonRoomFingerprint fingerprint : definition.fingerprints()) {
            JsonObject fp = new JsonObject();
            fp.addProperty("x", fingerprint.x());
            fp.addProperty("y", fingerprint.y());
            fp.addProperty("z", fingerprint.z());
            fp.addProperty("block", fingerprint.blockId());
            fingerprints.add(fp);
        }
        json.add("fingerprints", fingerprints);

        JsonArray waypoints = new JsonArray();
        for (DungeonWaypoint waypoint : definition.waypoints()) {
            waypoints.add(waypointToJson(waypoint));
        }
        json.add("waypoints", waypoints);
        return json;
    }

    private static DungeonRoomDefinition definitionFromJson(JsonObject json) {
        String id = string(json, "id", "");
        String name = string(json, "name", id);
        DungeonRoomType type = parseType(string(json, "type", "ROOM"));
        DungeonRoomShape shape = parseShape(string(json, "shape", "UNKNOWN"));

        List<DungeonRoomFingerprint> fingerprints = new ArrayList<>();
        if (json.has("fingerprints")) {
            for (JsonElement element : json.getAsJsonArray("fingerprints")) {
                JsonObject fp = element.getAsJsonObject();
                fingerprints.add(new DungeonRoomFingerprint(
                        integer(fp, "x", 0),
                        integer(fp, "y", 70),
                        integer(fp, "z", 0),
                        string(fp, "block", "minecraft:air")));
            }
        }

        List<DungeonWaypoint> waypoints = new ArrayList<>();
        if (json.has("waypoints")) {
            for (JsonElement element : json.getAsJsonArray("waypoints")) {
                waypoints.add(waypointFromJson(element.getAsJsonObject()));
            }
        }

        return new DungeonRoomDefinition(id, name, type, shape, fingerprints, waypoints);
    }

    private static JsonObject waypointToJson(DungeonWaypoint waypoint) {
        JsonObject json = new JsonObject();
        json.addProperty("id", waypoint.id());
        json.addProperty("secretIndex", waypoint.secretIndex());
        json.addProperty("category", waypoint.category().id);
        json.addProperty("trigger", waypoint.trigger().name());
        json.addProperty("x", waypoint.x());
        json.addProperty("y", waypoint.y());
        json.addProperty("z", waypoint.z());
        if (waypoint.hasName()) json.addProperty("name", waypoint.name());

        JsonArray highlights = new JsonArray();
        for (DungeonHighlight highlight : waypoint.highlights()) {
            JsonObject h = new JsonObject();
            h.addProperty("x", highlight.x());
            h.addProperty("y", highlight.y());
            h.addProperty("z", highlight.z());
            h.addProperty("style", highlight.style().name());
            if (highlight.hasOwnColor()) h.addProperty("color", highlight.color());
            highlights.add(h);
        }
        json.add("highlights", highlights);
        return json;
    }

    private static DungeonWaypoint waypointFromJson(JsonObject json) {
        List<DungeonHighlight> highlights = new ArrayList<>();
        if (json.has("highlights")) {
            for (JsonElement element : json.getAsJsonArray("highlights")) {
                JsonObject h = element.getAsJsonObject();
                highlights.add(new DungeonHighlight(
                        integer(h, "x", 0),
                        integer(h, "y", 70),
                        integer(h, "z", 0),
                        parseHighlightStyle(string(h, "style", "OUTLINE")),
                        h.has("color") ? h.get("color").getAsInt() : DungeonHighlight.INHERIT_COLOR));
            }
        }
        return new DungeonWaypoint(
                string(json, "id", "secret-" + integer(json, "secretIndex", 1)),
                integer(json, "secretIndex", 1),
                DungeonSecretCategory.fromId(string(json, "category", "default")),
                DungeonWaypointTrigger.fromId(string(json, "trigger", "")),
                integer(json, "x", 0),
                integer(json, "y", 70),
                integer(json, "z", 0),
                string(json, "name", ""),
                highlights);
    }

    private static Map<DungeonRoomShape, List<DungeonWaypoint>> buildDemo() {
        Map<DungeonRoomShape, List<DungeonWaypoint>> map = new EnumMap<>(DungeonRoomShape.class);
        for (DungeonRoomShape shape : DungeonRoomShape.values()) {
            DungeonWaypoint demo = new DungeonWaypoint(
                    "demo:" + shape.name(),
                    1,
                    DungeonSecretCategory.CHEST,
                    16, 70, 16,
                    "Waypointer demo",
                    List.of(
                            DungeonHighlight.outline(15, 70, 15),
                            DungeonHighlight.outline(17, 70, 17)
                    )
            );
            map.put(shape, List.of(demo));
        }
        return Collections.unmodifiableMap(map);
    }

    private static DungeonRoomType parseType(String raw) {
        try {
            return DungeonRoomType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return DungeonRoomType.ROOM;
        }
    }

    private static DungeonRoomShape parseShape(String raw) {
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "1X1", "ONE_BY_ONE" -> DungeonRoomShape.ONE_BY_ONE;
            case "1X2", "ONE_BY_TWO" -> DungeonRoomShape.ONE_BY_TWO;
            case "1X3", "ONE_BY_THREE" -> DungeonRoomShape.ONE_BY_THREE;
            case "1X4", "ONE_BY_FOUR" -> DungeonRoomShape.ONE_BY_FOUR;
            case "2X2", "TWO_BY_TWO" -> DungeonRoomShape.TWO_BY_TWO;
            case "L_SHAPE", "L" -> DungeonRoomShape.L_SHAPE;
            default -> DungeonRoomShape.UNKNOWN;
        };
    }

    private static DungeonHighlightStyle parseHighlightStyle(String raw) {
        try {
            return DungeonHighlightStyle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return DungeonHighlightStyle.OUTLINE;
        }
    }

    private static DungeonRoomType typeFromIdentityKey(String key) {
        if (key == null) return DungeonRoomType.ROOM;
        int sep = key.indexOf(':');
        if (sep < 0) return DungeonRoomType.ROOM;
        return parseType(key.substring(0, sep));
    }

    private static DungeonRoomShape shapeFromIdentityKey(String key) {
        if (key == null) return DungeonRoomShape.UNKNOWN;
        String[] parts = key.split(":");
        if (parts.length < 2) return DungeonRoomShape.UNKNOWN;
        return parseShape(parts[1]);
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return json.has(name) ? json.get(name).getAsInt() : fallback;
    }

    private static String string(JsonObject json, String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }
}
