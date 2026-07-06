package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static dev.ethan.waypointer.util.MathUtil.clampByte;

/**
 * Accepts waypoint payloads from several known-good sources and converts them into
 * Waypointer's internal model. The importer tries decoders in order until one
 * produces at least one group; if none do, we report the best error we saw.
 *
 * Supported:
 *
 *   - Native {@code WP:} codec payloads (delegated to {@link WaypointCodec}).
 *   - Skyblocker-style: Base64(Gzip(JSON)) where JSON is an array of groups or a
 *     map of {@code {"island": [points...]}}.
 *   - Skytils / Soopy style: raw JSON or base64(JSON) with either a top-level
 *     array of groups, a {@code categories} array, or a single object with a
 *     {@code waypoints} array.
 *   - SkyHanni / Coleweight-style: a flat JSON array where each entry carries
 *     {@code x/y/z}, float {@code r/g/b} in [0,1], and an {@code options}
 *     object holding {@code name} (string or sequence number).
 *
 * Unknown fields are ignored. Missing fields fall back to defaults so a partially
 * malformed payload from a third-party tool still imports the coordinates cleanly.
 */
public final class WaypointImporter {

    public enum Source { WAYPOINTER, SKYBLOCKER, SKYTILS, SKYHANNI, SOOPY, COLEWEIGHT, ODIN, JSON }

    /** Skyblocker's current (V1) share-string prefix. Payload after it is base64(gzip(json)). */
    static final String SKYBLOCKER_V1_PREFIX = "[Skyblocker-Waypoint-Data-V1]";
    static final int MAX_TEXT_PAYLOAD_CHARS = 8 * 1024 * 1024;
    static final int MAX_DECODED_JSON_CHARS = 8 * 1024 * 1024;
    static final int MAX_GROUPS_PER_IMPORT = 256;
    static final int MAX_WAYPOINTS_PER_GROUP = 20_000;
    static final int MAX_TOTAL_WAYPOINTS_PER_IMPORT = 50_000;

    /**
     * Skyblocker's deprecated ordered-waypoints prefix. Payload is the same base64(gzip(json))
     * shape but the inner JSON is a {@code {groupName: {name, enabled, waypoints}}} map
     * rather than a group array. Still in the wild on older shared routes.
     */
    static final String SKYBLOCKER_LEGACY_ORDERED_PREFIX = "[Skyblocker::OrderedWaypoints::v1]";

    /**
     * Skyblocker's island ids come from Hypixel's internal map names and don't line
     * up with Waypointer's display-driven ids. Aliases here let imports land in the
     * right zone without the user having to manually retarget.
     *
     * Any id not in this table falls through to {@link #normalizeZone}'s generic
     * lowercase/dash handling and will either match a Waypointer zone verbatim
     * (e.g. {@code crystal_hollows}, {@code garden}) or end up as a prettified new
     * zone id the user can rebind.
     *
     * {@code "dungeon"} maps to {@link Zone#UNKNOWN} on purpose: Skyblocker's
     * generic "dungeon" payloads omit the specific floor, and Waypointer's zones
     * are per-floor, so the retarget step in the import command
     * ({@code retargetUnknownGroups}) will snap the group to whichever floor the
     * player is currently on -- the only honest inference we can make.
     */
    private static final Map<String, String> SKYBLOCKER_ISLAND_ALIASES = Map.ofEntries(
            Map.entry("dynamic",         "private_island"),
            Map.entry("hub",             "hub"),
            Map.entry("farming_1",       "the_farming_isles"),
            Map.entry("foraging_1",      "the_park"),
            Map.entry("foraging_2",      "galatea"),
            Map.entry("combat_1",        "spiders_den"),
            Map.entry("combat_3",        "the_end"),
            Map.entry("mining_1",        "gold_mine"),
            Map.entry("mining_2",        "deep_caverns"),
            Map.entry("mining_3",        "dwarven_mines"),
            Map.entry("fishing_1",       "backwater_bayou"),
            Map.entry("dungeon_hub",     "dungeon_hub"),
            Map.entry("dungeon",         Zone.UNKNOWN.id()),
            Map.entry("rift",            "rift"),
            Map.entry("crystal_hollows", "crystal_hollows"),
            Map.entry("kuudra",          "kuudra"),
            Map.entry("mineshaft",       "mineshaft"),
            Map.entry("garden",          "garden"),
            Map.entry("winter",          "winter"),
            Map.entry("crimson_isle",    "crimson_isle"),
            Map.entry("dark_auction",    "dark_auction"),
            Map.entry("unknown",         Zone.UNKNOWN.id())
    );

    public record ImportResult(Source source, List<WaypointGroup> groups, String label) {}

    private WaypointImporter() {}

    public static ImportResult importAny(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        String trimmed = stripMarkdownCodeFence(payload.trim());
        enforceTextPayloadLimit(trimmed);

        IllegalArgumentException originalFailure;
        try {
            return importAnyCore(trimmed);
        } catch (IllegalArgumentException e) {
            originalFailure = e;
        }

        IllegalArgumentException repairFailure = null;
        for (String candidate : repairCandidates(trimmed)) {
            if (candidate.equals(trimmed)) continue;
            try {
                return importAnyCore(candidate);
            } catch (IllegalArgumentException e) {
                repairFailure = e;
            }
        }

        if (repairFailure == null) throw originalFailure;
        throw new IllegalArgumentException(originalFailure.getMessage()
                + " (repair also failed: " + repairFailure.getMessage() + ")", originalFailure);
    }

    private static ImportResult importAnyCore(String trimmed) {
        if (WaypointCodec.isCodecString(trimmed)) {
            WaypointCodec.Decoded d = WaypointCodec.decodeFull(trimmed);
            return checkedImport(new ImportResult(Source.WAYPOINTER, d.groups(), d.label()));
        }

        // Skyblocker's prefixed exports must be handled before the raw-base64 path
        // below: base64 decoding a "[Skyblocker-...]XXX" string would fail on the
        // bracketed prefix, losing us the useful error context ("this was clearly
        // meant as a Skyblocker payload, but the body was malformed").
        if (trimmed.startsWith(SKYBLOCKER_V1_PREFIX)) {
            return decodeSkyblockerPrefixed(trimmed, SKYBLOCKER_V1_PREFIX);
        }
        if (trimmed.startsWith(SKYBLOCKER_LEGACY_ORDERED_PREFIX)) {
            return decodeSkyblockerPrefixed(trimmed, SKYBLOCKER_LEGACY_ORDERED_PREFIX);
        }

        // Prefer JSON if it looks like JSON -- saves us from trying a base64 decode that
        // would succeed by coincidence on short JSON payloads.
        if (looksLikeJson(trimmed)) {
            return checkedImport(importJson(trimmed));
        }

        // Skyblocker exports (and some Skytils strings) are base64(gzip(json)).
        try {
            String decoded = decodeBase64Gzip(trimmed);
            if (looksLikeJson(decoded)) {
                return checkedImport(importJson(decoded));
            }
        } catch (Exception ignore) {
            // Fall through to error below; preserve the original so the user sees useful feedback.
        }

        // Skytils category exports are base64(JSON) with no gzip wrapper.
        try {
            String decoded = decodeBase64Utf8(trimmed);
            if (looksLikeJson(decoded)) return checkedImport(importJson(decoded));
        } catch (Exception ignore) {
            // Fall through to error below; preserve the original so the user sees useful feedback.
        }

        throw new IllegalArgumentException(
                "unrecognized waypoint payload (tried Waypointer, Skyblocker, Skytils, SkyHanni, JSON)");
    }

    private static void enforceTextPayloadLimit(String text) {
        if (text.length() > MAX_TEXT_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("waypoint payload is too large (max "
                    + MAX_TEXT_PAYLOAD_CHARS + " chars)");
        }
    }

    private static ImportResult checkedImport(ImportResult result) {
        if (result == null || result.groups() == null) {
            throw new IllegalArgumentException("import decoder returned no groups");
        }
        List<WaypointGroup> groups = result.groups();
        if (groups.size() > MAX_GROUPS_PER_IMPORT) {
            throw new IllegalArgumentException("import contains too many groups ("
                    + groups.size() + " > " + MAX_GROUPS_PER_IMPORT + ")");
        }

        int totalWaypoints = 0;
        for (WaypointGroup group : groups) {
            if (group == null) throw new IllegalArgumentException("import contained a null group");
            if (group.size() > MAX_WAYPOINTS_PER_GROUP) {
                throw new IllegalArgumentException("group \"" + group.name()
                        + "\" has too many waypoints (" + group.size()
                        + " > " + MAX_WAYPOINTS_PER_GROUP + ")");
            }
            totalWaypoints += group.size();
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS_PER_IMPORT) {
                throw new IllegalArgumentException("import contains too many waypoints ("
                        + totalWaypoints + " > " + MAX_TOTAL_WAYPOINTS_PER_IMPORT + ")");
            }
        }
        if (totalWaypoints == 0) {
            throw new IllegalArgumentException("import contained no waypoints");
        }
        return new ImportResult(result.source(), List.copyOf(groups),
                result.label() == null ? "" : result.label());
    }

    private static List<String> repairCandidates(String trimmed) {
        List<String> candidates = new ArrayList<>();
        addRepairCandidate(candidates, stripMinecraftFormattingAndInvisibleChars(trimmed).trim());
        addRepairCandidate(candidates, compactLikelyEncodedWhitespace(trimmed));
        addRepairCandidate(candidates, extractNativeCodecCandidate(trimmed));
        addRepairCandidate(candidates, extractJsonCandidate(trimmed));
        return candidates;
    }

    private static void addRepairCandidate(List<String> candidates, String candidate) {
        if (candidate == null || candidate.isBlank()) return;
        if (candidate.length() > MAX_TEXT_PAYLOAD_CHARS) return;
        if (candidates.contains(candidate)) return;
        candidates.add(candidate);
    }

    private static String stripMinecraftFormattingAndInvisibleChars(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7') {
                if (i + 1 < text.length()) i++;
                continue;
            }
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String compactLikelyEncodedWhitespace(String text) {
        if (text.startsWith(WaypointCodec.MAGIC)) {
            return WaypointCodec.MAGIC
                    + removeWhitespace(text.substring(WaypointCodec.MAGIC.length()));
        }
        if (text.startsWith(SKYBLOCKER_V1_PREFIX)) {
            return SKYBLOCKER_V1_PREFIX
                    + removeWhitespace(text.substring(SKYBLOCKER_V1_PREFIX.length()));
        }
        if (text.startsWith(SKYBLOCKER_LEGACY_ORDERED_PREFIX)) {
            return SKYBLOCKER_LEGACY_ORDERED_PREFIX
                    + removeWhitespace(text.substring(SKYBLOCKER_LEGACY_ORDERED_PREFIX.length()));
        }
        if (looksLikeJson(text)) return text;
        return removeWhitespace(text);
    }

    private static String removeWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    private static String extractNativeCodecCandidate(String text) {
        int start = text.indexOf(WaypointCodec.MAGIC);
        if (start < 0) return null;

        int end = start + WaypointCodec.MAGIC.length();
        while (end < text.length()) {
            char c = text.charAt(end);
            if (!AsciiStreamCodec.isAlphabetChar(c) && !Character.isWhitespace(c)) break;
            end++;
        }

        String compact = WaypointCodec.MAGIC
                + removeWhitespace(text.substring(start + WaypointCodec.MAGIC.length(), end));
        if (WaypointCodec.isValidCodec(compact)) return compact;

        int minLength = WaypointCodec.MAGIC.length() + 3;
        for (int candidateEnd = compact.length() - 1; candidateEnd >= minLength; candidateEnd--) {
            String shorter = compact.substring(0, candidateEnd);
            if (WaypointCodec.isValidCodec(shorter)) return shorter;
        }
        return compact.length() >= minLength ? compact : null;
    }

    private static String extractJsonCandidate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') continue;
            int end = findBalancedJsonEnd(text, i);
            if (end > i) return text.substring(i, end);
        }
        return null;
    }

    private static int findBalancedJsonEnd(String text, int start) {
        if (start < 0 || start >= text.length()) return -1;
        char opener = text.charAt(start);
        if (opener != '{' && opener != '[') return -1;

        char[] stack = new char[text.length() - start];
        int depth = 0;
        stack[depth++] = opener;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                stack[depth++] = c;
                continue;
            }
            if (c == '}' || c == ']') {
                if (depth == 0 || !jsonCloserMatches(stack[depth - 1], c)) return -1;
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static boolean jsonCloserMatches(char opener, char closer) {
        return (opener == '{' && closer == '}') || (opener == '[' && closer == ']');
    }

    private static String stripMarkdownCodeFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }

        int bodyStart = 3;
        int newline = text.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;

        String body = text.substring(bodyStart, text.length() - 3).strip();
        return body.isEmpty() ? text : body;
    }

    private static ImportResult decodeSkyblockerPrefixed(String trimmed, String prefix) {
        String body = trimmed.substring(prefix.length()).trim();
        String json;
        try {
            json = decodeBase64Gzip(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Skyblocker payload header matched (" + prefix + ") but body failed to decode: "
                            + e.getMessage());
        }
        if (!looksLikeJson(json)) {
            throw new IllegalArgumentException(
                    "Skyblocker payload decoded but didn't contain JSON");
        }
        ImportResult r = importJson(json);
        return checkedImport(new ImportResult(Source.SKYBLOCKER, r.groups(), ""));
    }

    // --- JSON path ---------------------------------------------------------------------------

    private static ImportResult importJson(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<WaypointGroup> groups = new ArrayList<>();
        Source source = Source.JSON;

        if (root.isJsonArray()) {
            // Either a list of groups, a SkyHanni/Coleweight flat route, or a list of loose
            // waypoints. Distinguish by content shape: groups have nested point arrays,
            // SkyHanni/Coleweight has an `options` object per point, loose waypoints are
            // plain {x,y,z,...} dicts.
            JsonArray arr = root.getAsJsonArray();
            if (looksLikeGroupArray(arr)) {
                for (JsonElement el : arr) groups.add(parseGroup(el.getAsJsonObject()));
            } else if (looksLikeColeweightArray(arr)) {
                WaypointGroup g = parseColeweightRoute(arr);
                if (!g.isEmpty()) groups.add(g);
                source = hasNullCoordinatePlaceholder(arr) ? Source.SOOPY : Source.SKYHANNI;
            } else {
                WaypointGroup g = WaypointGroup.create("Imported", Zone.UNKNOWN.id());
                g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                List<Waypoint> waypoints = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    Waypoint waypoint = waypointFromLoose(el.getAsJsonObject());
                    if (waypoint != null) waypoints.add(waypoint);
                }
                g.addAll(waypoints);
                groups.add(g);
            }
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            // Soopy/Skytils-esque single-group object.
            if (obj.has("categories") && obj.get("categories").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("categories")) {
                    if (el.isJsonObject()) groups.add(parseGroup(el.getAsJsonObject()));
                }
                source = Source.SKYTILS;
            } else if (obj.has("waypoints") && obj.get("waypoints").isJsonArray()) {
                groups.add(parseGroup(obj));
                source = Source.SKYTILS;
            } else if (obj.has("groups") && obj.get("groups").isJsonArray()) {
                // Could be a Waypointer JSON export (verbatim storage dump).
                for (JsonElement el : obj.getAsJsonArray("groups")) groups.add(parseGroup(el.getAsJsonObject()));
            } else if (looksLikeOdinDungeonWaypointConfig(obj)) {
                groups.addAll(parseOdinDungeonWaypointConfig(obj));
                source = Source.ODIN;
            } else {
                // Legacy Skyblocker shapes, two variants:
                //  1. {"island_id": [<waypoints>], ...}
                //     Each island maps to a flat list of loose points.
                //  2. {"route_name": {"name":..,"enabled":..,"waypoints":[..]}, ...}
                //     Skyblocker's deprecated ordered-waypoints format -- each value
                //     is a full group object, and the map key carries the route name.
                // Both come through with Source.SKYBLOCKER; the parseGroup path handles
                // the ordered flag and we stamp AUTO gradient so route direction is
                // visible even though the sender likely painted every point the same.
                source = Source.SKYBLOCKER;
                for (var entry : obj.entrySet()) {
                    JsonElement val = entry.getValue();
                    if (val.isJsonArray()) {
                        WaypointGroup g = WaypointGroup.create(entry.getKey(), normalizeZone(entry.getKey()));
                        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                        List<Waypoint> waypoints = new ArrayList<>(val.getAsJsonArray().size());
                        for (JsonElement el : val.getAsJsonArray()) {
                            if (!el.isJsonObject()) continue;
                            Waypoint waypoint = waypointFromLoose(el.getAsJsonObject());
                            if (waypoint != null) waypoints.add(waypoint);
                        }
                        g.addAll(waypoints);
                        if (!g.isEmpty()) groups.add(g);
                    } else if (val.isJsonObject() && val.getAsJsonObject().has("waypoints")) {
                        WaypointGroup g = parseGroup(val.getAsJsonObject());
                        // parseGroup picks up the "name" field from the value object
                        // when present; when it isn't, the map key is the only name
                        // we have. Don't clobber a real name that parseGroup already
                        // resolved -- the sender's explicit group name wins.
                        if (g.name().isBlank()) g.setName(entry.getKey());
                        g.setGradientMode(WaypointGroup.GradientMode.AUTO);
                        if (!g.isEmpty()) groups.add(g);
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            throw new IllegalArgumentException("JSON parsed but contained no waypoints");
        }
        return new ImportResult(source, groups, "");
    }

    private static boolean looksLikeOdinDungeonWaypointConfig(JsonObject obj) {
        for (var entry : obj.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonArray()) continue;
            for (JsonElement element : value.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject waypoint = element.getAsJsonObject();
                if (waypoint.has("blockPos") && waypoint.get("blockPos").isJsonObject()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<WaypointGroup> parseOdinDungeonWaypointConfig(JsonObject obj) {
        List<WaypointGroup> groups = new ArrayList<>();
        for (var entry : obj.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonArray()) continue;
            WaypointGroup group = parseOdinRoomGroup(entry.getKey(), value.getAsJsonArray());
            if (!group.isEmpty()) groups.add(group);
        }
        return groups;
    }

    private static WaypointGroup parseOdinRoomGroup(String roomName, JsonArray points) {
        DungeonRoomDefinition definition = roomDefinitionForOdinKey(roomName);
        String zoneId = odinRoomZoneId(roomName, definition);
        String fallbackName = roomName == null || roomName.isBlank()
                ? "Odin Dungeon Waypoints"
                : roomName.trim();
        String groupName = definition == null ? fallbackName : definition.displayName();
        WaypointGroup group = WaypointGroup.create(groupName, zoneId);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);

        List<Waypoint> waypoints = new ArrayList<>(points.size());
        for (JsonElement element : points) {
            if (!element.isJsonObject()) continue;
            Waypoint waypoint = waypointFromOdin(element.getAsJsonObject());
            if (waypoint != null) waypoints.add(waypoint);
        }
        group.addAll(waypoints);
        return group;
    }

    private static DungeonRoomDefinition roomDefinitionForOdinKey(String roomName) {
        if (roomName == null || roomName.isBlank()) return null;
        DungeonRoomDefinition direct = DungeonRoomData.definition(roomName);
        if (direct != null) return direct;
        String trimmed = roomName.trim();
        for (DungeonRoomDefinition definition : DungeonRoomData.allDefinitions()) {
            if (definition.displayName().equalsIgnoreCase(trimmed)) return definition;
        }
        return null;
    }

    private static String odinRoomZoneId(String roomName, DungeonRoomDefinition definition) {
        if (definition != null) return definition.id();
        String normalized = DungeonRoomDefinition.normalizeId(roomName);
        return normalized.isBlank() ? Zone.UNKNOWN.id() : normalized;
    }

    private static Waypoint waypointFromOdin(JsonObject json) {
        int[] pos = extractOdinCoordinates(json);
        if (pos == null) return null;
        return new Waypoint(pos[0], pos[1], pos[2],
                odinWaypointName(json), parseOdinColor(json), 0, 0.0);
    }

    private static int[] extractOdinCoordinates(JsonObject json) {
        if (!json.has("blockPos") || !json.get("blockPos").isJsonObject()) return null;
        JsonObject blockPos = json.getAsJsonObject("blockPos");
        try {
            return new int[]{
                    blockPos.get("field_11175").getAsInt(),
                    blockPos.get("field_11174").getAsInt(),
                    blockPos.get("field_11173").getAsInt()
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseOdinColor(JsonObject json) {
        if (!json.has("color") || !json.get("color").isJsonPrimitive()) {
            return Waypoint.DEFAULT_COLOR;
        }
        String hex = json.get("color").getAsString().trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 8) hex = hex.substring(0, 6);
        if (hex.length() != 6) return Waypoint.DEFAULT_COLOR;
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return Waypoint.DEFAULT_COLOR;
        }
    }

    private static String odinWaypointName(JsonObject json) {
        if (!json.has("title") || !json.get("title").isJsonPrimitive()) return "";
        String name = json.get("title").getAsString().trim();
        if (name.isEmpty() || "Enter text".equalsIgnoreCase(name)) return "";
        return name;
    }

    private static boolean looksLikeGroupArray(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("waypoints") || o.has("points")) return true;
        }
        return false;
    }

    private static boolean looksLikeColeweightArray(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!(o.has("x") && o.has("y") && o.has("z"))) continue;
            if (o.has("options") && o.get("options").isJsonObject()) return true;
        }
        return false;
    }

    private static boolean hasNullCoordinatePlaceholder(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!(o.has("x") && o.has("y") && o.has("z"))) continue;
            if (!o.has("options") || !o.get("options").isJsonObject()) continue;
            if (o.get("x").isJsonNull() || o.get("y").isJsonNull() || o.get("z").isJsonNull()) return true;
        }
        return false;
    }

    private static WaypointGroup parseColeweightRoute(JsonArray arr) {
        WaypointGroup g = WaypointGroup.create("Imported Route", Zone.UNKNOWN.id());
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);

        List<JsonObject> points = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (extractCoordinates(o) != null) points.add(o);
        }

        if (allOptionNamesAreIntegers(points)) {
            points.sort(Comparator.comparingInt(WaypointImporter::coleweightOptionNameAsInt));
        }

        List<Waypoint> waypoints = new ArrayList<>(points.size());
        for (JsonObject p : points) {
            Waypoint waypoint = waypointFromColeweight(p);
            if (waypoint != null) waypoints.add(waypoint);
        }
        g.addAll(waypoints);
        g.setGradientMode(WaypointGroup.GradientMode.AUTO);
        return g;
    }

    private static Waypoint waypointFromColeweight(JsonObject o) {
        int[] pos = extractCoordinates(o);
        if (pos == null) return null;

        String name = coleweightName(o);
        int color = parseColeweightColor(o);
        return new Waypoint(pos[0], pos[1], pos[2], name, color, 0, 0.0);
    }

    private static int parseColeweightColor(JsonObject o) {
        if (!(o.has("r") && o.has("g") && o.has("b"))) return Waypoint.DEFAULT_COLOR;
        return coleweightRgb(
                o.get("r").getAsDouble(),
                o.get("g").getAsDouble(),
                o.get("b").getAsDouble());
    }

    static int coleweightRgb(double r, double g, double b) {
        int ri = clampByte((int) Math.round(r * 255.0));
        int gi = clampByte((int) Math.round(g * 255.0));
        int bi = clampByte((int) Math.round(b * 255.0));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static String coleweightName(JsonObject o) {
        if (!o.has("options") || !o.get("options").isJsonObject()) return "";
        JsonObject opts = o.getAsJsonObject("options");
        if (!opts.has("name")) return "";
        JsonElement n = opts.get("name");
        if (!n.isJsonPrimitive()) return "";
        JsonPrimitive prim = n.getAsJsonPrimitive();
        // Integer step numbers arrive as JSON numbers; stringify so the UI can render them.
        return prim.isNumber() ? prim.getAsNumber().toString() : prim.getAsString();
    }

    private static boolean allOptionNamesAreIntegers(List<JsonObject> points) {
        if (points.isEmpty()) return false;
        for (JsonObject p : points) {
            if (!p.has("options") || !p.get("options").isJsonObject()) return false;
            JsonObject opts = p.getAsJsonObject("options");
            if (!opts.has("name") || !opts.get("name").isJsonPrimitive()) return false;
            JsonPrimitive n = opts.get("name").getAsJsonPrimitive();
            if (!n.isNumber()) return false;
            double d = n.getAsDouble();
            if (d != Math.floor(d) || Double.isInfinite(d)) return false;
        }
        return true;
    }

    private static int coleweightOptionNameAsInt(JsonObject p) {
        return p.getAsJsonObject("options").get("name").getAsInt();
    }

    private static WaypointGroup parseGroup(JsonObject o) {
        String name = firstString(o, "", "name", "label");
        String zone = firstString(o, Zone.UNKNOWN.id(), "island", "zone", "world", "category");
        WaypointGroup g = WaypointGroup.create(name.isEmpty() ? zone : name, normalizeZone(zone));

        // Imported groups represent routes by default. Preserve explicit
        // per-waypoint colors unless the source marks the route as ordered; ordered
        // routes usually ship with repeated colors, so AUTO makes direction visible.
        boolean ordered = o.has("ordered")
                && o.get("ordered").isJsonPrimitive()
                && o.get("ordered").getAsJsonPrimitive().isBoolean()
                && o.get("ordered").getAsBoolean();
        JsonArray pts = firstArray(o, "waypoints", "points");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);

        if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
            g.setEnabled(o.get("enabled").getAsBoolean());
        }

        if (pts != null) {
            List<Waypoint> waypoints = new ArrayList<>(pts.size());
            for (JsonElement el : pts) {
                if (!el.isJsonObject()) continue;
                Waypoint waypoint = waypointFromLoose(el.getAsJsonObject());
                if (waypoint != null) waypoints.add(waypoint);
            }
            g.addAll(waypoints);
        }
        if (ordered) g.setGradientMode(WaypointGroup.GradientMode.AUTO);
        return g;
    }

    private static Waypoint waypointFromLoose(JsonObject o) {
        int[] pos = extractCoordinates(o);
        if (pos == null) return null;

        String name = firstString(o, "", "name", "label", "title");
        int color = parseColor(o);
        // "r" doubles as a radius in legacy Skytils exports but as the red channel in
        // Skyblocker/Coleweight exports. Only treat it as a radius when it's a numeric
        // primitive AND isByteColor is false (no sibling g/b channels) AND we didn't
        // find a coordinates array (pos/coords) that would mean this is the V1 shape.
        double radius = (o.has("r")
                && o.get("r").isJsonPrimitive()
                && !isByteColor(o)
                && !hasCoordinateArray(o))
                ? o.get("r").getAsDouble()
                : 0.0;
        return new Waypoint(pos[0], pos[1], pos[2], name, color, 0, radius);
    }

    private static int[] extractCoordinates(JsonObject o) {
        if (o.has("x") && o.has("y") && o.has("z")) {
            try {
                return new int[]{
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt()
                };
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        // Accept common alternate keys ("pos" = Skyblocker, "coords" seen in some dumps).
        JsonArray arr = firstArray(o, "pos", "coords", "position", "location");
        if (arr != null && arr.size() >= 3) {
            try {
                return new int[]{
                        arr.get(0).getAsInt(),
                        arr.get(1).getAsInt(),
                        arr.get(2).getAsInt()
                };
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasCoordinateArray(JsonObject o) {
        return firstArray(o, "pos", "coords", "position", "location") != null;
    }

    private static int parseColor(JsonObject o) {
        if (o.has("color")) {
            JsonElement c = o.get("color");
            if (c.isJsonPrimitive()) {
                if (c.getAsJsonPrimitive().isNumber()) return c.getAsInt() & 0xFFFFFF;
                String s = c.getAsString().trim();
                if (s.startsWith("#")) s = s.substring(1);
                if (s.contains(":")) {
                    int color = parseSkytilsColor(s);
                    return color < 0 ? Waypoint.DEFAULT_COLOR : color;
                }
                try { return Integer.parseInt(s, 16) & 0xFFFFFF; }
                catch (NumberFormatException ignored) { /* fall through */ }
            }
        }
        if (o.has("colorComponents") && o.get("colorComponents").isJsonArray()) {
            JsonArray cc = o.getAsJsonArray("colorComponents");
            if (cc.size() >= 3) {
                try {
                    return coleweightRgb(
                            cc.get(0).getAsDouble(),
                            cc.get(1).getAsDouble(),
                            cc.get(2).getAsDouble());
                } catch (RuntimeException ignored) { /* fall through */ }
            }
        }
        if (isByteColor(o)) {
            int r = clampByte(o.get("r").getAsInt());
            int gV = clampByte(o.get("g").getAsInt());
            int b = clampByte(o.get("b").getAsInt());
            return (r << 16) | (gV << 8) | b;
        }
        return Waypoint.DEFAULT_COLOR;
    }

    private static int parseSkytilsColor(String s) {
        // Skytils: "<scale>:<a>:<r>:<g>:<b>" (values hex). Keep rgb bytes.
        String[] parts = s.split(":");
        if (parts.length < 5) return -1;
        try {
            int r = Integer.parseInt(parts[2], 16);
            int gV = Integer.parseInt(parts[3], 16);
            int b = Integer.parseInt(parts[4], 16);
            return ((r & 0xFF) << 16) | ((gV & 0xFF) << 8) | (b & 0xFF);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isByteColor(JsonObject o) {
        return o.has("r") && o.has("g") && o.has("b")
                && o.get("r").isJsonPrimitive() && o.get("g").isJsonPrimitive() && o.get("b").isJsonPrimitive()
                && o.get("r").getAsJsonPrimitive().isNumber();
    }

    private static String firstString(JsonObject o, String fallback, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()) {
                return o.get(k).getAsString();
            }
        }
        return fallback;
    }

    private static JsonArray firstArray(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonArray()) return o.getAsJsonArray(k);
        }
        return null;
    }

    static String normalizeZone(String raw) {
        if (raw == null || raw.isBlank()) return Zone.UNKNOWN.id();
        String s = raw.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String aliased = SKYBLOCKER_ISLAND_ALIASES.get(s);
        return Zone.canonicalId(aliased == null ? s : aliased);
    }

    // --- helpers ------------------------------------------------------------------------------

    private static boolean looksLikeJson(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    private static String decodeBase64Gzip(String s) throws Exception {
        byte[] data = decodeBase64Bytes(s);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readUtf8WithLimit(in);
        }
    }

    private static String decodeBase64Utf8(String s) {
        byte[] data = decodeBase64Bytes(s);
        if (data.length > MAX_DECODED_JSON_CHARS) {
            throw new IllegalArgumentException("decoded waypoint JSON is too large (max "
                    + MAX_DECODED_JSON_CHARS + " bytes)");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readUtf8WithLimit(GZIPInputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_DECODED_JSON_CHARS) {
                throw new IllegalArgumentException("decoded waypoint JSON is too large (max "
                        + MAX_DECODED_JSON_CHARS + " bytes)");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] decodeBase64Bytes(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(s);
        }
    }
}
