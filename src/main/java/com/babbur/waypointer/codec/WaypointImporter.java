package com.babbur.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static com.babbur.waypointer.util.MathUtil.clampByte;

public final class WaypointImporter {

    public enum Source { WAYPOINTER, SKYBLOCKER, SKYTILS, SKYHANNI, SOOPY, FIRMAMENT, COLEWEIGHT, ODIN, JSON, CHUNKLOGGER, SKYSOFT }

    static final String SKYBLOCKER_V1_PREFIX = "[Skyblocker-Waypoint-Data-V1]";
    static final String SKYTILS_V1_PREFIX = "<Skytils-Waypoint-Data>(V1):";
    static final String SKYTILS_PREFIX = "<Skytils-Waypoint-Data>(V";
    static final String FIRMAMENT_SHARE_PREFIX = "FIRM_WAYPOINTS/";
    static final String FIRMAMENT_ENCODED_PREFIX = "RklSTV9XQVlQT0lOVFMv";
    static final int SOOPY_SHARE_VERSION = 1;
    static final int MAX_TEXT_PAYLOAD_CHARS = 8 * 1024 * 1024;
    public static final int MAX_DECODED_JSON_CHARS = 8 * 1024 * 1024;
    static final int MAX_GROUPS_PER_IMPORT = 256;
    static final int MAX_WAYPOINTS_PER_GROUP = 20_000;
    static final int MAX_TOTAL_WAYPOINTS_PER_IMPORT = 50_000;
    private static final int MAX_NATIVE_REPAIR_CANDIDATES = 4;
    private static final int MAX_JSON_REPAIR_CANDIDATES = 16;
    private static final String DEFAULT_IMPORTED_ROUTE_NAME = "Imported Route";
    private static final Pattern CATALOG_ZONE_ID = Pattern.compile("[a-z0-9_]{1,64}");

    static final String SKYBLOCKER_LEGACY_ORDERED_PREFIX = "[Skyblocker::OrderedWaypoints::v1]";

    private static final Map<String, String> SKYBLOCKER_ISLAND_ALIASES = Map.ofEntries(
            Map.entry("dynamic",         "private_island"),
            Map.entry("hub",             "hub"),
            Map.entry("farming_1",       "the_farming_isles"),
            Map.entry("foraging_1",      "the_park"),
            Map.entry("foraging_2",      "galatea"),
            Map.entry("foraging_3",      "torrhus_canyon"),
            Map.entry("combat_1",        "spiders_den"),
            Map.entry("combat_3",        "the_end"),
            Map.entry("mining_1",        "gold_mine"),
            Map.entry("mining_2",        "deep_caverns"),
            Map.entry("mining_3",        "dwarven_mines"),
            Map.entry("fishing_1",       "backwater_bayou"),
            Map.entry("lotus_atoll",     "lotus_atoll"),
            Map.entry("dungeon_hub",     "dungeon_hub"),
            Map.entry("dungeon",         Zone.UNKNOWN.id()),
            Map.entry("rift",            "rift"),
            Map.entry("crystal_hollows", "crystal_hollows"),
            Map.entry("kuudra",          "kuudra"),
            Map.entry("mineshaft",       "mineshaft_unknown"),
            Map.entry("garden",          "garden"),
            Map.entry("winter",          "winter"),
            Map.entry("crimson_isle",    "crimson_isle"),
            Map.entry("dark_auction",    "dark_auction"),
            Map.entry("unknown",         Zone.UNKNOWN.id())
    );

    public record ImportResult(
            Source source,
            List<WaypointGroup> groups,
            String label,
            RouteLibraryMetadata libraryMetadata) {
        public ImportResult(Source source, List<WaypointGroup> groups, String label) {
            this(source, groups, label, RouteLibraryMetadata.empty());
        }

        public ImportResult {
            source = Objects.requireNonNull(source, "source");
            groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
            label = label == null ? "" : label;
            libraryMetadata = libraryMetadata == null
                    ? RouteLibraryMetadata.empty() : libraryMetadata;
        }
    }

    private WaypointImporter() {}

    public static ImportResult importAny(String payload) {
        return importAny(payload, DEFAULT_IMPORTED_ROUTE_NAME);
    }

    public static ImportResult importAny(String payload, String defaultImportedRouteName) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        String trimmed = stripMarkdownCodeFence(payload);
        enforceTextPayloadLimit(trimmed);
        String fallbackRouteName = defaultImportedRouteName == null
                || defaultImportedRouteName.isBlank()
                ? DEFAULT_IMPORTED_ROUTE_NAME
                : defaultImportedRouteName;

        IllegalArgumentException originalFailure;
        try {
            return importAnyCore(trimmed, fallbackRouteName);
        } catch (RuntimeException e) {
            originalFailure = normalizedImportFailure(e);
            if (isSkysoftPayload(trimmed)) throw originalFailure;
        }

        IllegalArgumentException repairFailure = null;
        for (String candidate : repairCandidates(trimmed)) {
            if (candidate.equals(trimmed)) continue;
            try {
                return importAnyCore(candidate, fallbackRouteName);
            } catch (RuntimeException e) {
                repairFailure = normalizedImportFailure(e);
                if (isSkysoftPayload(candidate)) throw repairFailure;
            }
        }

        if (repairFailure == null) throw originalFailure;
        throw new IllegalArgumentException(originalFailure.getMessage()
                + " (repair also failed: " + repairFailure.getMessage() + ")", originalFailure);
    }

    private static IllegalArgumentException normalizedImportFailure(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException illegalArgument) return illegalArgument;
        String detail = failure.getMessage();
        return new IllegalArgumentException(detail == null || detail.isBlank()
                ? "malformed waypoint payload"
                : "malformed waypoint payload: " + detail, failure);
    }

    private static boolean isSkysoftPayload(String text) {
        if (text.startsWith(SkysoftWaypointImporter.PREFIX)) return true;
        if (!looksLikeJson(text)) return false;
        try {

            return SkysoftWaypointImporter.looksLikeLibrary(parseBoundedJson(text));
        } catch (RuntimeException malformedJson) {
            return false;
        }
    }

    private static ImportResult importAnyCore(String trimmed, String defaultImportedRouteName) {
        if (trimmed.startsWith(SkysoftWaypointImporter.PREFIX)) {
            return checkedImport(SkysoftWaypointImporter.decodeShare(trimmed));
        }
        if (RouteLibraryCodec.isPayload(trimmed)) {
            RouteLibraryCodec.Decoded decoded = RouteLibraryCodec.decode(trimmed);
            List<WaypointGroup> groups = decoded.groups();
            if (!decoded.label().isBlank() && groups.size() == 1) {
                groups.getFirst().setName(decoded.label());
            }
            ImportResult checked = checkedImport(new ImportResult(
                    Source.WAYPOINTER,
                    groups,
                    decoded.label(),
                    decoded.metadata()));
            checked.libraryMetadata().applyTo(checked.groups());
            return checked;
        }
        if (WaypointCodec.isCodecString(trimmed)) {
            WaypointCodec.Decoded d = WaypointCodec.decodeFull(trimmed);
            List<WaypointGroup> groups = d.groups();
            if (!d.label().isBlank() && groups.size() == 1) {
                groups.getFirst().setName(d.label());
            }

            ImportResult checked = checkedImport(new ImportResult(
                    Source.WAYPOINTER, groups, d.label(), d.metadata()));
            checked.libraryMetadata().applyTo(checked.groups());
            return checked;
        }

        if (trimmed.startsWith(SKYBLOCKER_V1_PREFIX)) {
            return decodeSkyblockerPrefixed(
                    trimmed, SKYBLOCKER_V1_PREFIX, defaultImportedRouteName);
        }
        if (trimmed.startsWith(SKYBLOCKER_LEGACY_ORDERED_PREFIX)) {
            return decodeSkyblockerPrefixed(
                    trimmed, SKYBLOCKER_LEGACY_ORDERED_PREFIX,
                    defaultImportedRouteName);
        }
        if (trimmed.startsWith(SKYTILS_PREFIX)) {
            return decodeSkytilsPrefixed(trimmed);
        }
        if (trimmed.startsWith(FIRMAMENT_ENCODED_PREFIX)) {
            return checkedImport(decodeFirmamentShare(trimmed));
        }
        if (looksLikeSoopyShare(trimmed)) {
            return checkedImport(decodeSoopyShare(trimmed));
        }

        if (looksLikeJson(trimmed)) {
            return checkedImport(importJson(trimmed, defaultImportedRouteName));
        }

        try {
            String decoded = decodeBase64Gzip(trimmed);
            if (looksLikeJson(decoded)) {
                return checkedImport(importJson(decoded, defaultImportedRouteName));
            }
        } catch (Exception ignore) {

        }

        try {
            String decoded = decodeBase64Utf8(trimmed);
            if (looksLikeJson(decoded)) {
                return checkedImport(importJson(decoded, defaultImportedRouteName));
            }
        } catch (Exception ignore) {

        }

        throw new IllegalArgumentException(
                "unrecognized waypoint payload (tried Waypointer, Skyblocker, Skytils, SkyHanni, Soopy, Firmament, Skysoft, JSON)");
    }

    static void enforceTextPayloadLimit(String text) {
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
                result.label() == null ? "" : result.label(), result.libraryMetadata());
    }

    public static ImportResult validatePreparedImport(
            Source source, List<WaypointGroup> groups, String label) {
        if (source == null) throw new IllegalArgumentException("import source is required");
        return checkedImport(new ImportResult(source, groups, label));
    }

    public static void validateCatalogEmbeddedZones(List<WaypointGroup> groups) {
        if (groups == null) {
            throw new IllegalArgumentException("catalog route has no groups");
        }
        for (WaypointGroup group : groups) {
            if (group == null || group.zoneId() == null
                    || !CATALOG_ZONE_ID.matcher(group.zoneId()).matches()) {
                throw new IllegalArgumentException("catalog route has an invalid embedded zone id");
            }
        }
    }

    private static List<String> repairCandidates(String trimmed) {
        List<String> candidates = new ArrayList<>();
        addRepairCandidate(candidates, stripMinecraftFormattingAndInvisibleChars(trimmed).trim());
        addRepairCandidate(candidates, compactLikelyEncodedWhitespace(trimmed));
        addNativeCodecRepairCandidates(candidates, trimmed);
        addJsonRepairCandidates(candidates, trimmed);
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

    private static void addNativeCodecRepairCandidates(List<String> candidates, String text) {
        int start = text.indexOf(WaypointCodec.MAGIC);
        if (start < 0) return;

        int end = start + WaypointCodec.MAGIC.length();
        int minLength = WaypointCodec.MAGIC.length() + 3;
        StringBuilder compact = new StringBuilder(text.length() - start);
        compact.append(WaypointCodec.MAGIC);
        int[] whitespaceBoundaries = new int[MAX_NATIVE_REPAIR_CANDIDATES];
        int boundaryCount = 0;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (!AsciiStreamCodec.isAlphabetChar(c) && !Character.isWhitespace(c)) break;
            if (AsciiStreamCodec.isAlphabetChar(c)) {
                compact.append(c);
            } else if (compact.length() >= minLength) {
                whitespaceBoundaries[boundaryCount % whitespaceBoundaries.length] = compact.length();
                boundaryCount++;
            }
            end++;
        }

        if (compact.length() < minLength) return;
        String full = compact.toString();
        addRepairCandidate(candidates, full);
        int retainedBoundaries = Math.min(boundaryCount, whitespaceBoundaries.length);
        for (int offset = 1; offset <= retainedBoundaries; offset++) {
            int boundary = whitespaceBoundaries[(boundaryCount - offset) % whitespaceBoundaries.length];
            if (boundary < full.length()) {
                addRepairCandidate(candidates, full.substring(0, boundary));
            }
        }
    }

    private static void addJsonRepairCandidates(List<String> candidates, String text) {
        char[] stack = new char[BoundedJson.MAX_DEPTH];
        int[] starts = new int[BoundedJson.MAX_DEPTH];
        int depth = 0;
        int completedCandidates = 0;
        int rootStart = -1;
        int fallbackStart = -1;
        int fallbackEnd = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (depth == 0) {
                if (c != '{' && c != '[') continue;
                rootStart = i;
                stack[0] = c;
                starts[0] = i;
                depth = 1;
                inString = false;
                escaped = false;
                continue;
            }
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
                if (depth == stack.length) {
                    depth = 0;
                    inString = false;
                    escaped = false;
                    continue;
                }
                stack[depth] = c;
                starts[depth] = i;
                depth++;
                continue;
            }
            if (c == '}' || c == ']') {
                if (!jsonCloserMatches(stack[depth - 1], c)) {
                    depth = 0;
                    inString = false;
                    escaped = false;
                    continue;
                }
                int completedStart = starts[depth - 1];
                depth--;
                if (depth == 0) {
                    addRepairCandidate(candidates, text.substring(rootStart, i + 1));
                    completedCandidates++;
                    if (completedCandidates >= MAX_JSON_REPAIR_CANDIDATES) return;
                    continue;
                }
                fallbackStart = completedStart;
                fallbackEnd = i + 1;
            }
        }
        if (fallbackStart >= 0) {
            addRepairCandidate(candidates, text.substring(fallbackStart, fallbackEnd));
        }
    }

    private static boolean jsonCloserMatches(char opener, char closer) {
        return (opener == '{' && closer == '}') || (opener == '[' && closer == ']');
    }

    static String stripMarkdownCodeFence(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        String text = payload.trim();
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }

        int bodyStart = 3;
        int newline = text.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;

        String body = text.substring(bodyStart, text.length() - 3).strip();
        return body.isEmpty() ? text : body;
    }

    private static ImportResult decodeSkyblockerPrefixed(
            String trimmed, String prefix, String defaultImportedRouteName) {
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
        ImportResult r = prefix.equals(SKYBLOCKER_V1_PREFIX)
                ? importSkyblockerV1Json(json)
                : importJson(json, defaultImportedRouteName);
        return checkedImport(new ImportResult(Source.SKYBLOCKER, r.groups(), ""));
    }

    private static ImportResult importSkyblockerV1Json(String json) {
        JsonElement root = parseBoundedJson(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Skyblocker V1 payload must contain a group array");
        }

        List<WaypointGroup> groups = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            WaypointGroup group = parseSkyblockerV1Group(element.getAsJsonObject());
            if (!group.isEmpty()) groups.add(group);
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("Skyblocker V1 payload contained no waypoints");
        }
        return new ImportResult(Source.SKYBLOCKER, groups, "");
    }

    private static WaypointGroup parseSkyblockerV1Group(JsonObject json) {
        String zone = firstString(json, Zone.UNKNOWN.id(), "island");
        String name = WaypointCodec.Options.sanitizeLabel(firstString(json, zone, "name"));
        boolean ordered = json.has("ordered") && json.get("ordered").isJsonPrimitive()
                && json.get("ordered").getAsBoolean();
        boolean throughWalls = !json.has("renderThroughWalls")
                || !json.get("renderThroughWalls").isJsonPrimitive()
                || json.get("renderThroughWalls").getAsBoolean();

        WaypointGroup group = WaypointGroup.create(name, normalizeZone(zone));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(ordered ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);

        JsonArray points = firstArray(json, "waypoints");
        if (points == null) return group;
        List<Waypoint> waypoints = new ArrayList<>(points.size());
        for (JsonElement element : points) {
            if (!element.isJsonObject()) continue;
            Waypoint waypoint = waypointFromSkyblockerV1(element.getAsJsonObject(), throughWalls);
            if (waypoint != null) waypoints.add(waypoint);
        }
        group.addAll(waypoints);
        return group;
    }

    private static Waypoint waypointFromSkyblockerV1(JsonObject json, boolean throughWalls) {
        int[] pos = extractCoordinates(json);
        if (pos == null) return null;

        String name = WaypointCodec.Options.sanitizeLabel(skyblockerComponentText(json.get("name")));
        int flags = throughWalls ? Waypoint.FLAG_THROUGH_WALL : 0;
        if (json.has("shouldRender") && json.get("shouldRender").isJsonPrimitive()
                && !json.get("shouldRender").getAsBoolean()) {
            flags |= Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME;
        }
        return new Waypoint(pos[0], pos[1], pos[2], name, parseColor(json), flags, 0.0);
    }

    private static String skyblockerComponentText(JsonElement name) {
        if (name == null || name.isJsonNull()) return "";
        if (name.isJsonPrimitive()) return name.getAsString();
        if (name.isJsonObject()) return firstString(name.getAsJsonObject(), "", "text");
        return "";
    }

    private static ImportResult decodeSkytilsPrefixed(String payload) {
        int headerEnd = payload.indexOf("):", SKYTILS_PREFIX.length());
        if (headerEnd < 0) {
            throw new IllegalArgumentException("Skytils payload is missing its version terminator");
        }

        String versionText = payload.substring(SKYTILS_PREFIX.length(), headerEnd);
        String body = payload.substring(headerEnd + 2).trim();
        String json;
        try {
            json = switch (versionText) {
                case "1" -> decodeBase64Gzip(body);
                case "2" -> decodeBase64Brotli(body);
                default -> throw new IllegalArgumentException("unknown Skytils waypoint version " + versionText);
            };
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Skytils V" + versionText
                    + " payload failed to decode: " + e.getMessage(), e);
        }

        return checkedImport(importSkytilsCategoryList(json));
    }

    private static ImportResult importSkytilsCategoryList(String json) {
        JsonElement parsed = parseBoundedJson(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Skytils payload must contain a category object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("categories") || !root.get("categories").isJsonArray()) {
            throw new IllegalArgumentException("Skytils payload is missing categories");
        }

        List<WaypointGroup> groups = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("categories")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Skytils category must be an object");
            }
            WaypointGroup group = parseSkytilsCategory(element.getAsJsonObject());
            if (!group.isEmpty()) groups.add(group);
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("Skytils payload contained no waypoints");
        }
        return new ImportResult(Source.SKYTILS, groups, "");
    }

    private static WaypointGroup parseSkytilsCategory(JsonObject category) {
        if (!category.has("island") || !category.get("island").isJsonPrimitive()
                || !category.has("waypoints") || !category.get("waypoints").isJsonArray()) {
            throw new IllegalArgumentException("Skytils category is missing island or waypoints");
        }

        String island = category.get("island").getAsString();
        String name = "Uncategorized";
        if (category.has("name") && category.get("name").isJsonPrimitive()) {
            name = WaypointCodec.Options.sanitizeLabel(category.get("name").getAsString());
            if (name.isBlank()) name = "Uncategorized";
        }

        WaypointGroup group = WaypointGroup.create(name, normalizeZone(island));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        List<Waypoint> waypoints = new ArrayList<>();
        for (JsonElement element : category.getAsJsonArray("waypoints")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Skytils waypoint must be an object");
            }
            waypoints.add(waypointFromSkytils(element.getAsJsonObject()));
        }
        group.addAll(waypoints);
        return group;
    }

    private static Waypoint waypointFromSkytils(JsonObject json) {
        if (!json.has("name") || !json.get("name").isJsonPrimitive()) {
            throw new IllegalArgumentException("Skytils waypoint is missing its name");
        }
        int[] pos = extractCoordinates(json);
        if (pos == null) {
            throw new IllegalArgumentException("Skytils waypoint has invalid coordinates");
        }

        String name = WaypointCodec.Options.sanitizeLabel(json.get("name").getAsString());
        boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
        int flags = enabled ? 0 : Waypoint.FLAG_HIDE_BEACON | Waypoint.FLAG_HIDE_NAME;
        int color = 0xFF0000;
        if (json.has("color")) {
            if (!json.get("color").isJsonPrimitive()
                    || !json.get("color").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Skytils waypoint has an invalid color");
            }
            color = json.get("color").getAsInt() & 0xFFFFFF;
        }
        return new Waypoint(pos[0], pos[1], pos[2], name, color, flags, 0.0);
    }

    private static boolean looksLikeSoopyShare(String payload) {
        try {
            byte[] bytes = decodeBase64Bytes(payload);
            if (bytes.length < 5 || Byte.toUnsignedInt(bytes[0]) != SOOPY_SHARE_VERSION) return false;
            int count = (Byte.toUnsignedInt(bytes[1]) << 24)
                    | (Byte.toUnsignedInt(bytes[2]) << 16)
                    | (Byte.toUnsignedInt(bytes[3]) << 8)
                    | Byte.toUnsignedInt(bytes[4]);
            return count > 0 && count <= MAX_TOTAL_WAYPOINTS_PER_IMPORT;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ImportResult decodeSoopyShare(String payload) {
        byte[] bytes = decodeBase64Bytes(payload);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readUnsignedByte();
            if (version != SOOPY_SHARE_VERSION) {
                throw new IllegalArgumentException("unsupported Soopy waypoint version " + version);
            }
            int count = input.readInt();
            if (count <= 0 || count > MAX_TOTAL_WAYPOINTS_PER_IMPORT) {
                throw new IllegalArgumentException("invalid Soopy waypoint count " + count);
            }

            Map<String, WaypointGroup> groupsByArea = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String id = input.readUTF();
                float x = input.readFloat();
                float y = input.readFloat();
                float z = input.readFloat();
                int red = soopyColorChannel(input.readByte());
                int green = soopyColorChannel(input.readByte());
                int blue = soopyColorChannel(input.readByte());
                String area = input.readUTF();
                String name = WaypointCodec.Options.sanitizeLabel(input.readUTF());
                if (name.isBlank()) name = WaypointCodec.Options.sanitizeLabel(id);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                        || (double) x < Waypoint.MIN_BLOCK_COORDINATE
                        || (double) x > Waypoint.MAX_BLOCK_COORDINATE
                        || (double) y < Waypoint.MIN_BLOCK_COORDINATE
                        || (double) y > Waypoint.MAX_BLOCK_COORDINATE
                        || (double) z < Waypoint.MIN_BLOCK_COORDINATE
                        || (double) z > Waypoint.MAX_BLOCK_COORDINATE) {
                    throw new IllegalArgumentException("Soopy waypoint has invalid coordinates");
                }

                String areaKey = area.trim();
                WaypointGroup group = groupsByArea.computeIfAbsent(areaKey, WaypointImporter::newSoopyGroup);
                group.add(new Waypoint((int) x, (int) y, (int) z, name,
                        (red << 16) | (green << 8) | blue, 0, 0.0));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Soopy payload has trailing data");
            }
            return new ImportResult(Source.SOOPY, new ArrayList<>(groupsByArea.values()), "");
        } catch (IOException e) {
            throw new IllegalArgumentException("Soopy waypoint payload is truncated or malformed", e);
        }
    }

    private static int soopyColorChannel(byte encoded) {
        if (encoded < 0) {
            throw new IllegalArgumentException("Soopy waypoint color is outside the V1 range");
        }
        return clampByte(Math.round(encoded * 2.0f));
    }

    private static WaypointGroup newSoopyGroup(String area) {
        String name = area.isBlank() ? "Soopy Waypoints" : "Soopy - " + area;
        WaypointGroup group = WaypointGroup.create(
                WaypointCodec.Options.sanitizeLabel(name), normalizeZone(area));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        return group;
    }

    private static ImportResult decodeFirmamentShare(String payload) {
        String decoded;
        try {
            decoded = decodeBase64Utf8(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Firmament waypoint payload is not valid Base64", e);
        }
        if (!decoded.startsWith(FIRMAMENT_SHARE_PREFIX)) {
            throw new IllegalArgumentException("Firmament waypoint payload has an invalid prefix");
        }

        JsonElement parsed = parseBoundedJson(decoded.substring(FIRMAMENT_SHARE_PREFIX.length()));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Firmament waypoint payload must contain an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("label") || !root.get("label").isJsonPrimitive()
                || !root.getAsJsonPrimitive("label").isString()
                || !root.has("id") || !root.get("id").isJsonPrimitive()
                || !root.getAsJsonPrimitive("id").isString()
                || !root.has("waypoints") || !root.get("waypoints").isJsonArray()
                || !root.has("isOrdered") || !root.get("isOrdered").isJsonPrimitive()
                || !root.getAsJsonPrimitive("isOrdered").isBoolean()) {
            throw new IllegalArgumentException("Firmament waypoint payload is missing required fields");
        }
        if (root.has("isRelativeTo") && !root.get("isRelativeTo").isJsonNull()) {
            throw new IllegalArgumentException(
                    "relative Firmament waypoints require the sender's import origin and cannot be placed safely");
        }

        String label = WaypointCodec.Options.sanitizeLabel(root.get("label").getAsString());
        if (label.isBlank()) label = WaypointCodec.Options.sanitizeLabel(root.get("id").getAsString());
        if (label.isBlank()) label = "Firmament Waypoints";
        boolean ordered = root.get("isOrdered").getAsBoolean();
        WaypointGroup group = WaypointGroup.create(label, Zone.UNKNOWN.id());
        group.setLoadMode(ordered ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(ordered
                ? WaypointGroup.GradientMode.AUTO
                : WaypointGroup.GradientMode.MANUAL);

        int index = 1;
        for (JsonElement element : root.getAsJsonArray("waypoints")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Firmament waypoint must be an object");
            }
            JsonObject waypoint = element.getAsJsonObject();
            if (!hasFirmamentIntegerCoordinate(waypoint, "x")
                    || !hasFirmamentIntegerCoordinate(waypoint, "y")
                    || !hasFirmamentIntegerCoordinate(waypoint, "z")) {
                throw new IllegalArgumentException("Firmament waypoint has invalid coordinates");
            }
            int[] position = extractCoordinates(waypoint);
            if (position == null) {
                throw new IllegalArgumentException("Firmament waypoint has invalid coordinates");
            }
            group.add(new Waypoint(position[0], position[1], position[2],
                    ordered ? Integer.toString(index) : "", Waypoint.DEFAULT_COLOR, 0, 0.0));
            index++;
        }
        return new ImportResult(Source.FIRMAMENT, List.of(group), "");
    }

    private static boolean hasFirmamentIntegerCoordinate(JsonObject waypoint, String key) {
        if (!waypoint.has(key) || !waypoint.get(key).isJsonPrimitive()
                || !waypoint.getAsJsonPrimitive(key).isNumber()) {
            return false;
        }
        try {
            blockCoordinate(waypoint.get(key));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ImportResult importJson(String json, String defaultImportedRouteName) {
        JsonElement root = parseBoundedJson(json);
        if (SkysoftWaypointImporter.looksLikeLibrary(root)) {
            return SkysoftWaypointImporter.decodeJson(root.getAsJsonObject());
        }
        if (ChunkLoggerRouteCodec.looksLikeRoute(root)) {
            WaypointGroup group = ChunkLoggerRouteCodec.decode(root, defaultImportedRouteName);
            if (group.isEmpty()) {
                throw new IllegalArgumentException("ChunkLogger route contained no valid waypoints");
            }
            return new ImportResult(Source.CHUNKLOGGER, List.of(group), "");
        }

        List<WaypointGroup> groups = new ArrayList<>();
        Source source = Source.JSON;

        if (root.isJsonArray()) {

            JsonArray arr = root.getAsJsonArray();
            if (OdinWaypointImporter.looksLikeExport(arr)) {
                groups.addAll(OdinWaypointImporter.decode(arr));
                source = Source.ODIN;
            } else if (looksLikeGroupArray(arr)) {
                for (JsonElement el : arr) groups.add(parseGroup(el.getAsJsonObject()));
            } else if (looksLikeColeweightArray(arr)) {
                WaypointGroup g = parseColeweightRoute(arr, defaultImportedRouteName);
                if (!g.isEmpty()) groups.add(g);
                source = hasNullCoordinatePlaceholder(arr) ? Source.SOOPY : Source.SKYHANNI;
            } else {
                WaypointGroup g = WaypointGroup.create(defaultImportedRouteName, Zone.UNKNOWN.id());
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
            if (obj.has("waypoints") && obj.get("waypoints").isJsonArray()
                    && looksLikeColeweightArray(obj.getAsJsonArray("waypoints"))) {
                JsonArray waypoints = obj.getAsJsonArray("waypoints");
                WaypointGroup group = parseColeweightRoute(waypoints, defaultImportedRouteName);
                if (!group.isEmpty()) groups.add(group);
                source = hasNullCoordinatePlaceholder(waypoints) ? Source.SOOPY : Source.SKYHANNI;

            } else if (obj.has("categories") && obj.get("categories").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("categories")) {
                    if (el.isJsonObject()) groups.add(parseGroup(el.getAsJsonObject()));
                }
                source = Source.SKYTILS;
            } else if (obj.has("waypoints") && obj.get("waypoints").isJsonArray()) {
                groups.add(parseGroup(obj));
                source = Source.SKYTILS;
            } else if (obj.has("groups") && obj.get("groups").isJsonArray()) {

                for (JsonElement el : obj.getAsJsonArray("groups")) groups.add(parseGroup(el.getAsJsonObject()));
            } else if (looksLikeOdinDungeonWaypointConfig(obj)) {
                groups.addAll(parseOdinDungeonWaypointConfig(obj));
                source = Source.ODIN;
            } else {

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
        DungeonRoomCatalogEntry definition = roomEntryForOdinKey(roomName);
        String zoneId = odinRoomZoneId(roomName, definition);
        String fallbackName = roomName == null || roomName.isBlank()
                ? "Odin Dungeon Waypoints"
                : roomName.trim();
        fallbackName = WaypointCodec.Options.sanitizeLabel(fallbackName);
        if (fallbackName.isEmpty()) fallbackName = "Odin Dungeon Waypoints";
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

    private static DungeonRoomCatalogEntry roomEntryForOdinKey(String roomName) {
        if (roomName == null || roomName.isBlank()) return null;
        DungeonRoomCatalogEntry direct = DungeonRoomData.entry(roomName);
        if (direct != null) return direct;
        String trimmed = roomName.trim();
        for (DungeonRoomCatalogEntry entry : DungeonRoomData.allEntries()) {
            if (entry.displayName().equalsIgnoreCase(trimmed)) return entry;
        }
        return null;
    }

    private static String odinRoomZoneId(String roomName, DungeonRoomCatalogEntry definition) {
        if (definition != null) return definition.id();
        String normalized = DungeonRoomCatalogEntry.normalizeId(roomName);
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
            if (blockPos.has("x") && blockPos.has("y") && blockPos.has("z")) {
                return new int[]{
                        blockCoordinate(blockPos.get("x")),
                        blockCoordinate(blockPos.get("y")),
                        blockCoordinate(blockPos.get("z"))
                };
            }
            return new int[]{
                    blockCoordinate(blockPos.get("field_11175")),
                    blockCoordinate(blockPos.get("field_11174")),
                    blockCoordinate(blockPos.get("field_11173"))
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
        return WaypointCodec.Options.sanitizeLabel(name);
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

    private static WaypointGroup parseColeweightRoute(
            JsonArray arr, String defaultImportedRouteName) {
        WaypointGroup g = WaypointGroup.create(defaultImportedRouteName, Zone.UNKNOWN.id());
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

        return prim.isNumber() ? prim.getAsNumber().toString() : prim.getAsString();
    }

    private static boolean allOptionNamesAreIntegers(List<JsonObject> points) {
        if (points.isEmpty()) return false;
        for (JsonObject p : points) {
            if (!p.has("options") || !p.get("options").isJsonObject()) return false;
            JsonObject opts = p.getAsJsonObject("options");
            if (!opts.has("name") || !opts.get("name").isJsonPrimitive()) return false;
            JsonPrimitive n = opts.get("name").getAsJsonPrimitive();
            try {
                Integer.parseInt(n.getAsString());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static int coleweightOptionNameAsInt(JsonObject p) {
        return p.getAsJsonObject("options").get("name").getAsInt();
    }

    private static WaypointGroup parseGroup(JsonObject o) {
        String name = WaypointCodec.Options.sanitizeLabel(firstString(o, "", "name", "label"));
        String zone = firstString(o, Zone.UNKNOWN.id(), "island", "zone", "world", "category");
        WaypointGroup g = WaypointGroup.create(name.isEmpty() ? zone : name, normalizeZone(zone));

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

        String name = WaypointCodec.Options.sanitizeLabel(firstString(o, "", "name", "label", "title"));
        int color = parseColor(o);

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
                        blockCoordinate(o.get("x")),
                        blockCoordinate(o.get("y")),
                        blockCoordinate(o.get("z"))
                };
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        JsonArray arr = firstArray(o, "pos", "coords", "position", "location");
        if (arr != null && arr.size() >= 3) {
            try {
                return new int[]{
                        blockCoordinate(arr.get(0)),
                        blockCoordinate(arr.get(1)),
                        blockCoordinate(arr.get(2))
                };
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int blockCoordinate(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("waypoint coordinate must be a number");
        }
        int coordinate = element.getAsBigDecimal().intValueExact();
        if (!Waypoint.isRepresentableBlockCoordinate(coordinate)) {
            throw new IllegalArgumentException("waypoint coordinate is outside the precise range");
        }
        return coordinate;
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
                catch (NumberFormatException ignored) {   }
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
                } catch (RuntimeException ignored) {   }
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

    static JsonElement parseBoundedJson(String json) {
        try {
            return BoundedJson.parse(json);
        } catch (IllegalArgumentException failure) {
            if (!(failure.getCause() instanceof IOException)) throw failure;
            throw new IllegalArgumentException("malformed waypoint payload: malformed JSON", failure);
        }
    }

    private static boolean looksLikeJson(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    static String decodeBase64Gzip(String s) throws IOException {
        byte[] data = decodeBase64Bytes(s);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readUtf8WithLimit(in);
        }
    }

    private static String decodeBase64Brotli(String s) throws IOException {
        byte[] data = decodeBase64Bytes(s);
        try (BrotliInputStream in = new BrotliInputStream(new ByteArrayInputStream(data))) {
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

    private static String readUtf8WithLimit(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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
