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
 * <p>The bundled catalog uses Odin's BSD-3-Clause room-core hashes for room
 * identity, with attribution preserved in the repository's third-party
 * notices. We still do not ship Skyblocker/DungeonRoomsMod Catacombs secret
 * waypoint data because their attribution traces many room skeletons and secret
 * waypoints to GPL-3.0 data, while this project is PolyForm Noncommercial.
 */
public final class DungeonRoomData {

    public interface BlockLookup {
        String blockIdAt(int x, int y, int z);
    }

    public interface CoreHashLookup {
        List<Integer> coreHashesFor(DungeonRoom room);
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
        for (DungeonRoomDefinition custom : CUSTOM.get().values()) {
            all.put(custom.id(), withInheritedCoreHashes(custom, BUNDLED.get(custom.id())));
        }
        return List.copyOf(all.values());
    }

    public static DungeonRoomDefinition definition(String id) {
        String norm = DungeonRoomDefinition.normalizeId(id);
        DungeonRoomDefinition custom = CUSTOM.get().get(norm);
        return custom != null ? custom : BUNDLED.get(norm);
    }

    /*[[AI-FN-DOC
Function:
DungeonRoomData.definitionForCoreHash
Purpose:
Resolve a single observed room-core hash to the unique bundled or custom dungeon room definition that owns it.
Why this exists:
Odin-style instant room detection starts from the current segment's core hash, so the scanner needs a direct core-to-definition lookup without first deriving map shape.
When to use:
Use when a live dungeon segment has been hashed and should identify its room; do not use for route import, normal island zones, or non-core block fingerprints.
Inputs:
coreHash is the integer String.hashCode value produced by the room core scanner for one dungeon segment.
Outputs:
Returns the unique DungeonRoomDefinition containing that core hash, or null when no definition or multiple definitions contain it.
Side effects:
Reads the current merged custom/bundled definition catalog; does not mutate catalog state.
Failure modes:
Returns null for unknown hashes and for ambiguous hashes shared by multiple definitions so callers do not activate the wrong room.
Important invariants:
Custom definitions with the same id as bundled definitions inherit bundled core hashes through allDefinitions, so user overrides still win without duplicating core data.
Internal logic:
Iterate merged definitions in catalog order, test each core hash list, keep the first match, and abort with null if a second different definition also matches.
Pseudocode:
matched = null
for each definition in allDefinitions:
  if definition does not contain coreHash continue
  if matched is already set return null
  matched = definition
return matched
Implementation notes:
The catalog is small, and this lookup is usually hit only when entering or background-scanning a new segment; avoiding a separate mutable index keeps custom overrides simple and safe.
AI self-check:
Verify unknown hashes are null, unique hashes return the expected definition, and duplicate hashes never silently pick an arbitrary room.
]]*/
    public static DungeonRoomDefinition definitionForCoreHash(int coreHash) {
        DungeonRoomDefinition matched = null;
        for (DungeonRoomDefinition definition : allDefinitions()) {
            if (!definition.coreHashes().contains(coreHash)) continue;
            if (matched != null) return null;
            matched = definition;
        }
        return matched;
    }

    public static boolean isCustomDefinition(String id) {
        return CUSTOM.get().containsKey(DungeonRoomDefinition.normalizeId(id));
    }

    public static DungeonRoom withMatchedDefinition(DungeonRoom room, BlockLookup lookup) {
        return withMatchedDefinition(room, lookup, null);
    }

    public static DungeonRoom withMatchedDefinition(DungeonRoom room, BlockLookup lookup,
                                                    CoreHashLookup coreHashLookup) {
        DungeonRoomDefinition definition = match(room, lookup, coreHashLookup);
        return definition == null ? room : room.withDefinition(definition.id(), definition.displayName());
    }

    public static DungeonRoomDefinition match(DungeonRoom room, BlockLookup lookup) {
        return match(room, lookup, null);
    }

    /*[[AI-FN-DOC
Function:
DungeonRoomData.match(DungeonRoom room, BlockLookup lookup, CoreHashLookup coreHashLookup)
Purpose:
Resolve a live dungeon room footprint into the best known authored room definition using room ids, core hashes, fingerprints, and finally safe generic fallbacks.
Why this exists:
The renderer and zone bridge need a stable catalog id for the current physical dungeon room, and the matching rules need to live in one place instead of being split across scanners and UI code.
When to use:
Call this when a DungeonRoom has been detected from the map or world and should be associated with a bundled or custom room definition; do not use it for normal island zones or non-dungeon route groups.
Inputs:
room is the detected DungeonRoom and may be null; lookup reads world block ids for fingerprint matching and may be null; coreHashLookup reads one or more room-core hashes and may be null.
Outputs:
Returns the matched DungeonRoomDefinition, or null when the room is unknown, ambiguous, or cannot be safely fingerprinted.
Side effects:
May call the supplied lookup/coreHashLookup, which can read client world state; this method does not mutate catalog state.
Failure modes:
Null rooms, unknown ids, missing lookups for fingerprinted rooms, ambiguous core/fingerprint matches, and unmatched known-core rooms all return null instead of guessing.
Important invariants:
An existing room id wins immediately; core hashes are authoritative across same-type rooms even when the map-derived shape is wrong; ambiguous matches never fall back to a generic shape-only definition.
Internal logic:
First honor explicit room ids, then gather the merged definition catalog once. If core hashes are available, try the first/current-segment hash before all observed hashes so an over-merged map footprint still names the segment the player occupies. Only after core matching fails safely should shape-filtered fingerprints and generic fallbacks run.
Pseudocode:
if room is null return null
if room already has an id return definition(id)
load all definitions and observed core hashes when available
if observed core hashes exist:
  match same-type definitions containing the first observed hash
  if exactly one primary match return it
  if multiple primary matches return null
  match same-type definitions containing any observed hash
  if exactly one match return it
  if multiple matches return null
  if the catalog has any same-type core definitions return null
shape-filter custom and full candidates
if no candidates return null
try fingerprinted candidates when present and lookup is available
if fingerprints matched exactly one candidate return it, otherwise return null
try custom no-fingerprint fallback, then bundled no-fingerprint fallback, each only when unique
Implementation notes:
The current-segment-first rule is a deliberate Devonian-style migration point: maps can merge adjacent same-colored segments, but the first segment comes from the player-facing flood-fill seed and is the most specific identity signal available here.
AI self-check:
Verify the implementation follows the pseudocode, preserves previous ambiguity handling, does not copy GPL code or data, and keeps fallback behavior conservative.
]]*/
    public static DungeonRoomDefinition match(DungeonRoom room, BlockLookup lookup,
                                              CoreHashLookup coreHashLookup) {
        if (room == null) return null;
        if (room.hasRoomId()) return definition(room.roomId());

        Collection<DungeonRoomDefinition> allDefinitions = allDefinitions();
        if (coreHashLookup != null) {
            List<Integer> observed = coreHashLookup.coreHashesFor(room);
            if (observed != null && !observed.isEmpty()) {
                List<DungeonRoomDefinition> primaryCoreMatches =
                        matchingCoreDefinitions(room, allDefinitions, List.of(observed.get(0)));
                if (primaryCoreMatches.size() == 1) return primaryCoreMatches.get(0);
                if (primaryCoreMatches.size() > 1) return null;

                List<DungeonRoomDefinition> coreMatches =
                        matchingCoreDefinitions(room, allDefinitions, observed);
                if (coreMatches.size() == 1) return coreMatches.get(0);
                if (coreMatches.size() > 1) return null;
                if (hasCoreDefinitionsForType(room, allDefinitions)) return null;
            }
        }

        List<DungeonRoomDefinition> customCandidates = matchingDefinitions(room, CUSTOM.get().values());
        List<DungeonRoomDefinition> candidates = matchingDefinitions(room, allDefinitions);
        if (candidates.isEmpty()) return null;

        List<DungeonRoomDefinition> fingerprintedCandidates = withFingerprints(candidates);
        if (!fingerprintedCandidates.isEmpty()) {
            if (lookup == null) return null;

            List<DungeonRoomDefinition> fingerprintMatches = new ArrayList<>();
            for (DungeonRoomDefinition definition : fingerprintedCandidates) {
                if (fingerprintsMatch(room, definition, lookup)) {
                    fingerprintMatches.add(definition);
                }
            }
            if (fingerprintMatches.size() == 1) return fingerprintMatches.get(0);

            return null;
        }

        List<DungeonRoomDefinition> customFallback = withoutFingerprints(customCandidates);
        if (customFallback.size() == 1) return customFallback.get(0);

        List<DungeonRoomDefinition> fallback = withoutFingerprints(candidates);
        return fallback.size() == 1 ? fallback.get(0) : null;
    }

    private static DungeonRoomDefinition withInheritedCoreHashes(
            DungeonRoomDefinition custom,
            DungeonRoomDefinition bundled) {
        if (custom == null || bundled == null || custom.hasCoreHashes()) return custom;
        if (!bundled.hasCoreHashes()) return custom;
        return custom.withCoreHashes(bundled.coreHashes());
    }

    /*[[AI-FN-DOC
Function:
DungeonRoomData.matchingCoreDefinitions(DungeonRoom room, Collection<DungeonRoomDefinition> definitions, List<Integer> observedCoreHashes)
Purpose:
Find every same-type room definition whose authored core hash appears in the observed hashes from the detected physical room.
Why this exists:
Core hashes identify named rooms more reliably than map-derived shape, so the core matching logic needs a shape-independent helper that can be reused for primary-segment and full-footprint checks.
When to use:
Use inside room matching whenever observed room-core hashes should be compared against bundled or custom definitions; do not use for fingerprint matching or shape-only fallback.
Inputs:
room is the detected DungeonRoom and must be non-null; definitions is the catalog slice to scan and must be non-null; observedCoreHashes is a non-null list of integer hashes ordered by segment priority.
Outputs:
Returns a mutable list containing definitions with the same DungeonRoomType as the room and at least one matching core hash.
Side effects:
None.
Failure modes:
Definitions without core hashes, different room types, and non-matching hashes are ignored; malformed null collection contents are not expected from the catalog and would throw naturally.
Important invariants:
The helper must not compare room shape, because the map can over-merge adjacent same-color components; type remains required so puzzle/trap/normal rooms do not collide.
Internal logic:
Iterate definitions in catalog order, skip entries whose type differs from the detected room, then reuse coreHashesMatch to test whether any authored hash appears in the observed hash list.
Pseudocode:
create empty matches list
for each definition in definitions:
  if definition type differs from room type, continue
  if definition core hashes do not intersect observed hashes, continue
  add definition to matches
return matches
Implementation notes:
The input sizes are tiny, so list intersection via coreHashesMatch stays clearer than adding temporary sets.
AI self-check:
Verify shape is intentionally absent from the predicate and the helper preserves deterministic catalog order for ambiguity handling.
]]*/
    private static List<DungeonRoomDefinition> matchingCoreDefinitions(
            DungeonRoom room,
            Collection<DungeonRoomDefinition> definitions,
            List<Integer> observedCoreHashes) {
        List<DungeonRoomDefinition> matches = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition.type() != room.type()) continue;
            if (coreHashesMatch(definition, observedCoreHashes)) {
                matches.add(definition);
            }
        }
        return matches;
    }

    /*[[AI-FN-DOC
Function:
DungeonRoomData.hasCoreDefinitionsForType(DungeonRoom room, Collection<DungeonRoomDefinition> definitions)
Purpose:
Report whether the catalog has any core-hashed definition for the detected room's broad type.
Why this exists:
When an observed core hash fails to match a catalog that does contain core data for that room type, falling back to a generic shape-only room would produce misleading route activation.
When to use:
Use after a core lookup produced observed hashes but found no unique match; do not use as a general room-existence check.
Inputs:
room is the detected DungeonRoom and must be non-null; definitions is the merged catalog collection and must be non-null.
Outputs:
Returns true when at least one definition has the same DungeonRoomType and one or more authored core hashes.
Side effects:
None.
Failure modes:
Returns false for empty catalogs or catalogs without same-type core hashes.
Important invariants:
Only room type is considered here; shape is intentionally ignored for the same reason core matching ignores shape.
Internal logic:
Scan definitions until finding an entry with matching type and hasCoreHashes true, then return true; otherwise return false.
Pseudocode:
for each definition:
  if definition type equals room type and definition has core hashes:
    return true
return false
Implementation notes:
This keeps the conservative no-generic-fallback behavior from the previous matcher while widening core search beyond shape-gated candidates.
AI self-check:
Verify the function is pure, handles an empty catalog, and does not accidentally allow shape-only fallback for known-core normal rooms.
]]*/
    private static boolean hasCoreDefinitionsForType(
            DungeonRoom room,
            Collection<DungeonRoomDefinition> definitions) {
        for (DungeonRoomDefinition definition : definitions) {
            if (definition.type() == room.type() && definition.hasCoreHashes()) {
                return true;
            }
        }
        return false;
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

    private static List<DungeonRoomDefinition> withCoreHashes(
            List<DungeonRoomDefinition> candidates) {
        List<DungeonRoomDefinition> hashed = new ArrayList<>(candidates.size());
        for (DungeonRoomDefinition definition : candidates) {
            if (definition.hasCoreHashes()) hashed.add(definition);
        }
        return hashed;
    }

    private static List<DungeonRoomDefinition> withFingerprints(
            List<DungeonRoomDefinition> candidates) {
        List<DungeonRoomDefinition> fingerprinted = new ArrayList<>(candidates.size());
        for (DungeonRoomDefinition definition : candidates) {
            if (definition.hasFingerprints()) fingerprinted.add(definition);
        }
        return fingerprinted;
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

    public static DungeonRoomDefinition addCoreHash(String roomId, int coreHash) {
        DungeonRoomDefinition definition = requireCustom(roomId);
        List<Integer> next = new ArrayList<>(definition.coreHashes());
        next.add(coreHash);
        DungeonRoomDefinition updated = definition.withCoreHashes(next);
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

    private static boolean coreHashesMatch(DungeonRoomDefinition definition, List<Integer> observed) {
        for (Integer coreHash : observed) {
            if (definition.coreHashes().contains(coreHash)) return true;
        }
        return false;
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

        JsonArray coreHashes = new JsonArray();
        for (Integer coreHash : definition.coreHashes()) {
            coreHashes.add(coreHash);
        }
        json.add("coreHashes", coreHashes);

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

        List<Integer> coreHashes = new ArrayList<>();
        if (json.has("coreHashes")) {
            for (JsonElement element : json.getAsJsonArray("coreHashes")) {
                coreHashes.add(element.getAsInt());
            }
        }

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

        return new DungeonRoomDefinition(id, name, type, shape, coreHashes, fingerprints, waypoints);
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
