package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.dungeon.DungeonMapMath;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bundled, read-only Catacombs room identity catalog. */
public final class DungeonRoomData {

    public interface BlockLookup {
        String blockIdAt(int x, int y, int z);
    }

    public interface CoreHashLookup {
        List<Integer> coreHashesFor(DungeonRoom room);
    }

    private static final String BUNDLED_RESOURCE =
            "/assets/" + Waypointer.MOD_ID + "/dungeons/catacombs/rooms.json";
    private static final Map<String, DungeonRoomCatalogEntry> ENTRIES = loadBundledEntries();

    private DungeonRoomData() {}

    public static Collection<DungeonRoomCatalogEntry> allEntries() {
        return ENTRIES.values();
    }

    public static DungeonRoomCatalogEntry entry(String id) {
        return ENTRIES.get(DungeonRoomCatalogEntry.normalizeId(id));
    }

    public static DungeonRoomCatalogEntry entryForCoreHash(int coreHash) {
        DungeonRoomCatalogEntry matched = null;
        for (DungeonRoomCatalogEntry entry : ENTRIES.values()) {
            if (!entry.coreHashes().contains(coreHash)) continue;
            if (matched != null) return null;
            matched = entry;
        }
        return matched;
    }

    public static DungeonRoom withMatchedEntry(DungeonRoom room, BlockLookup lookup) {
        return withMatchedEntry(room, lookup, null);
    }

    public static DungeonRoom withMatchedEntry(DungeonRoom room, BlockLookup lookup,
                                               CoreHashLookup coreHashLookup) {
        if (room == null) return null;
        DungeonRoomCatalogEntry entry = match(room, lookup, coreHashLookup);
        return entry == null ? room : room.withDefinition(entry.id(), entry.displayName());
    }

    public static DungeonRoomCatalogEntry match(DungeonRoom room, BlockLookup lookup) {
        return match(room, lookup, null);
    }

    public static DungeonRoomCatalogEntry match(DungeonRoom room, BlockLookup lookup,
                                                CoreHashLookup coreHashLookup) {
        if (room == null) return null;
        if (room.hasRoomId()) return entry(room.roomId());

        Collection<DungeonRoomCatalogEntry> entries = ENTRIES.values();
        if (coreHashLookup != null) {
            List<Integer> observed = coreHashLookup.coreHashesFor(room);
            if (observed != null && !observed.isEmpty()) {
                List<DungeonRoomCatalogEntry> primaryCoreMatches =
                        matchingCoreEntries(room, entries, List.of(observed.getFirst()));
                List<DungeonRoomCatalogEntry> coreMatches =
                        matchingCoreEntries(room, entries, observed);
                if (primaryCoreMatches.size() == 1) {
                    DungeonRoomCatalogEntry primary = primaryCoreMatches.getFirst();
                    DungeonRoomCatalogEntry shapeMatch = uniqueShapeMatch(room, coreMatches);
                    if (shapeMatch != null && primary.shape() != room.shape()) return shapeMatch;
                    return primary;
                }
                if (primaryCoreMatches.size() > 1) return null;
                if (coreMatches.size() == 1) return coreMatches.getFirst();
                if (coreMatches.size() > 1) return uniqueShapeMatch(room, coreMatches);
                if (hasCoreEntriesForType(room, entries)) return null;
            }
        }

        List<DungeonRoomCatalogEntry> candidates = matchingEntries(room, entries);
        if (candidates.isEmpty()) return null;
        List<DungeonRoomCatalogEntry> fingerprinted = withFingerprints(candidates);
        if (!fingerprinted.isEmpty()) {
            if (lookup == null) return null;
            List<DungeonRoomCatalogEntry> matches = new ArrayList<>();
            for (DungeonRoomCatalogEntry entry : fingerprinted) {
                if (fingerprintsMatch(room, entry, lookup)) matches.add(entry);
            }
            return matches.size() == 1 ? matches.getFirst() : null;
        }
        List<DungeonRoomCatalogEntry> fallback = withoutFingerprints(candidates);
        return fallback.size() == 1 ? fallback.getFirst() : null;
    }

    private static List<DungeonRoomCatalogEntry> matchingCoreEntries(
            DungeonRoom room, Collection<DungeonRoomCatalogEntry> entries,
            List<Integer> observedCoreHashes) {
        List<DungeonRoomCatalogEntry> matches = new ArrayList<>();
        for (DungeonRoomCatalogEntry entry : entries) {
            if (entry.type() == room.type() && coreHashesMatch(entry, observedCoreHashes)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private static DungeonRoomCatalogEntry uniqueShapeMatch(
            DungeonRoom room, List<DungeonRoomCatalogEntry> entries) {
        DungeonRoomCatalogEntry matched = null;
        for (DungeonRoomCatalogEntry entry : entries) {
            if (entry.type() != room.type() || entry.shape() != room.shape()) continue;
            if (matched != null) return null;
            matched = entry;
        }
        return matched;
    }

    private static boolean hasCoreEntriesForType(
            DungeonRoom room, Collection<DungeonRoomCatalogEntry> entries) {
        for (DungeonRoomCatalogEntry entry : entries) {
            if (entry.type() == room.type() && entry.hasCoreHashes()) return true;
        }
        return false;
    }

    private static List<DungeonRoomCatalogEntry> matchingEntries(
            DungeonRoom room, Collection<DungeonRoomCatalogEntry> entries) {
        List<DungeonRoomCatalogEntry> matches = new ArrayList<>();
        for (DungeonRoomCatalogEntry entry : entries) {
            if (entry.type() == room.type() && entry.shape() == room.shape()) matches.add(entry);
        }
        return matches;
    }

    private static List<DungeonRoomCatalogEntry> withoutFingerprints(
            List<DungeonRoomCatalogEntry> entries) {
        List<DungeonRoomCatalogEntry> matches = new ArrayList<>();
        for (DungeonRoomCatalogEntry entry : entries) {
            if (!entry.hasFingerprints()) matches.add(entry);
        }
        return matches;
    }

    private static List<DungeonRoomCatalogEntry> withFingerprints(
            List<DungeonRoomCatalogEntry> entries) {
        List<DungeonRoomCatalogEntry> matches = new ArrayList<>();
        for (DungeonRoomCatalogEntry entry : entries) {
            if (entry.hasFingerprints()) matches.add(entry);
        }
        return matches;
    }

    private static boolean fingerprintsMatch(DungeonRoom room, DungeonRoomCatalogEntry entry,
                                             BlockLookup lookup) {
        for (DungeonRoomFingerprint fingerprint : entry.fingerprints()) {
            int[] world = DungeonMapMath.relativeToActual(
                    room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                    fingerprint.x(), fingerprint.y(), fingerprint.z());
            String actual = DungeonRoomFingerprint.normalizeBlockId(
                    lookup.blockIdAt(world[0], world[1], world[2]));
            if (!fingerprint.blockId().equals(actual)) return false;
        }
        return true;
    }

    private static boolean coreHashesMatch(
            DungeonRoomCatalogEntry entry, List<Integer> observed) {
        for (Integer coreHash : observed) {
            if (entry.coreHashes().contains(coreHash)) return true;
        }
        return false;
    }

    static Map<String, DungeonRoomCatalogEntry> parseEntries(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray rooms = root.has("rooms") ? root.getAsJsonArray("rooms") : new JsonArray();
        Map<String, DungeonRoomCatalogEntry> entries = new LinkedHashMap<>();
        for (JsonElement element : rooms) {
            DungeonRoomCatalogEntry entry = entryFromJson(element.getAsJsonObject());
            if (entries.putIfAbsent(entry.id(), entry) != null) {
                throw new IllegalArgumentException(
                        "Duplicate dungeon room id after normalization: " + entry.id());
            }
        }
        return Map.copyOf(entries);
    }

    private static Map<String, DungeonRoomCatalogEntry> loadBundledEntries() {
        try (InputStream stream = DungeonRoomData.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream == null) return Collections.emptyMap();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parseEntries(JsonParser.parseReader(reader).toString());
            }
        } catch (Exception e) {
            Waypointer.LOGGER.error("Failed to load bundled dungeon room catalog", e);
            return Collections.emptyMap();
        }
    }

    private static DungeonRoomCatalogEntry entryFromJson(JsonObject json) {
        String id = string(json, "id", "");
        String name = string(json, "name", id);
        DungeonRoomType type = parseType(string(json, "type", "ROOM"));
        DungeonRoomShape shape = parseShape(string(json, "shape", "UNKNOWN"));

        List<Integer> coreHashes = new ArrayList<>();
        if (json.has("coreHashes")) {
            for (JsonElement element : json.getAsJsonArray("coreHashes")) {
                coreHashes.add(element.getAsInt());
            }
        }

        List<DungeonRoomFingerprint> fingerprints = new ArrayList<>();
        if (json.has("fingerprints")) {
            for (JsonElement element : json.getAsJsonArray("fingerprints")) {
                JsonObject fingerprint = element.getAsJsonObject();
                fingerprints.add(new DungeonRoomFingerprint(
                        integer(fingerprint, "x", 0),
                        integer(fingerprint, "y", 70),
                        integer(fingerprint, "z", 0),
                        string(fingerprint, "block", "minecraft:air")));
            }
        }

        return new DungeonRoomCatalogEntry(
                id, name, type, shape, coreHashes, fingerprints,
                integer(json, "secrets", DungeonRoomCatalogEntry.UNKNOWN_COUNT),
                integer(json, "crypts", DungeonRoomCatalogEntry.UNKNOWN_COUNT),
                integer(json, "trappedChests", DungeonRoomCatalogEntry.UNKNOWN_COUNT));
    }

    private static DungeonRoomType parseType(String raw) {
        try {
            return DungeonRoomType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return DungeonRoomType.ROOM;
        }
    }

    private static DungeonRoomShape parseShape(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "1X1", "ONE_BY_ONE" -> DungeonRoomShape.ONE_BY_ONE;
            case "1X2", "ONE_BY_TWO" -> DungeonRoomShape.ONE_BY_TWO;
            case "1X3", "ONE_BY_THREE" -> DungeonRoomShape.ONE_BY_THREE;
            case "1X4", "ONE_BY_FOUR" -> DungeonRoomShape.ONE_BY_FOUR;
            case "2X2", "TWO_BY_TWO" -> DungeonRoomShape.TWO_BY_TWO;
            case "L_SHAPE", "L" -> DungeonRoomShape.L_SHAPE;
            default -> DungeonRoomShape.UNKNOWN;
        };
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return json.has(name) ? json.get(name).getAsInt() : fallback;
    }

    private static String string(JsonObject json, String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }
}
