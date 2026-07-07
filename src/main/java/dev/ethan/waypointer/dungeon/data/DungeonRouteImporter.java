package dev.ethan.waypointer.dungeon.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.ethan.waypointer.dungeon.DungeonHighlight;
import dev.ethan.waypointer.dungeon.DungeonHighlightStyle;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import dev.ethan.waypointer.dungeon.DungeonWaypointTrigger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Imports third-party dungeon secret routes into Waypointer room definitions.
 *
 * <p>Supported formats, sniffed from the payload shape:
 *
 * <ul>
 *   <li><b>Waypointer</b> -- a {@code WPD:} share payload or the native
 *       {@code {"schema":…,"rooms":[…]}} JSON.</li>
 *   <li><b>SecretRoutes</b> ({@code routes.json}) -- a map of DungeonRoomsMod
 *       room names to ordered route steps. Each step becomes one progress
 *       waypoint at the step's secret, with the step's action positions
 *       (etherwarps, stonk mines, superboom walls, levers) as colored
 *       highlights.</li>
 *   <li><b>Odin waypoint packs</b> -- a map of room names to waypoint lists,
 *       either as the pack file JSON or the Base64+GZIP string Odin's share
 *       command produces. {@code SECRET}/{@code ETHERWARP} waypoints become
 *       ordered progress waypoints; the rest import as persistent markers.</li>
 * </ul>
 *
 * <p>Both external formats use the same room-local frame as Waypointer:
 * SecretRoutes inherits Skyblocker/DRM's NW-corner convention outright, and
 * Odin's clay-relative "north" frame shares the origin block and axes with the
 * NW-corner frame (verified against Odin's {@code rotateToNorth} and clay
 * probe offsets), so coordinates copy over 1:1 with no rotation math.
 */
public final class DungeonRouteImporter {

    public enum Format { WAYPOINTER, SECRET_ROUTES, ODIN_PACK }

    /**
     * @param definitions     room definitions ready for
     *                        {@link DungeonRoomData#importCustomDefinitions}
     * @param waypointCount   total imported waypoints across all rooms
     * @param unmatchedRooms  source room names that matched no catalog room
     * @param skippedVariants alternate SecretRoutes routes ({@code "Room:2"})
     *                        not imported because Waypointer keeps one route
     *                        per room
     */
    public record Result(List<DungeonRoomDefinition> definitions,
                         int waypointCount,
                         List<String> unmatchedRooms,
                         int skippedVariants,
                         Format format) {}

    private static final int MAX_PAYLOAD_CHARS = 16 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    /** Room-local coordinates live in [0, 4*32); anything far outside is world data. */
    private static final int MAX_ROOM_LOCAL_ABS = 512;

    private DungeonRouteImporter() {}

    public static Result parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("route import payload is empty");
        }
        String trimmed = payload.trim();
        if (trimmed.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("route import payload is too large (max "
                    + MAX_PAYLOAD_CHARS + " chars)");
        }

        if (DungeonRoomShareCodec.isPayload(trimmed)) {
            DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decode(trimmed);
            return new Result(decoded.definitions(), decoded.waypointCount(),
                    List.of(), 0, Format.WAYPOINTER);
        }

        JsonObject root = parseRootObject(trimmed);
        if (root.has("rooms") && root.get("rooms").isJsonArray()) {
            Map<String, DungeonRoomDefinition> parsed = DungeonRoomData.parseDefinitions(trimmed);
            List<DungeonRoomDefinition> definitions = new ArrayList<>(parsed.values());
            return new Result(definitions, DungeonRoomShareCodec.waypointCount(definitions),
                    List.of(), 0, Format.WAYPOINTER);
        }
        if (looksLikeOdinPack(root)) return parseOdinPack(root);
        if (looksLikeSecretRoutes(root)) return parseSecretRoutes(root);
        throw new IllegalArgumentException(
                "unrecognized route format (expected Waypointer rooms, SecretRoutes routes.json,"
                        + " or an Odin waypoint pack)");
    }

    private static JsonObject parseRootObject(String payload) {
        if (payload.startsWith("{")) {
            try {
                return JsonParser.parseString(payload).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException e) {
                throw new IllegalArgumentException("route import payload is not valid JSON", e);
            }
        }
        // Odin's share command emits Base64(GZIP(pack json)).
        String gunzipped = tryBase64Gunzip(payload);
        if (gunzipped != null && gunzipped.startsWith("{")) {
            return parseRootObject(gunzipped);
        }
        throw new IllegalArgumentException(
                "route import payload is neither JSON nor a recognized share string");
    }

    private static String tryBase64Gunzip(String payload) {
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(stripWhitespace(payload));
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("decompressed route payload is too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    // ---- format sniffing ---------------------------------------------------

    private static boolean looksLikeSecretRoutes(JsonObject root) {
        JsonObject step = firstArrayElementObject(root);
        return step != null && (step.has("secret") || step.has("locations"));
    }

    private static boolean looksLikeOdinPack(JsonObject root) {
        JsonObject waypoint = firstArrayElementObject(root);
        return waypoint != null
                && (waypoint.has("blockPos")
                || (waypoint.has("x") && waypoint.has("filled")));
    }

    private static JsonObject firstArrayElementObject(JsonObject root) {
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            JsonArray array = entry.getValue().getAsJsonArray();
            if (!array.isEmpty() && array.get(0).isJsonObject()) {
                return array.get(0).getAsJsonObject();
            }
        }
        return null;
    }

    // ---- room-name matching --------------------------------------------------

    /**
     * DungeonRoomsMod-lineage names that differ from the Odin-lineage catalog
     * names. Every alias was cross-checked against the catalog's room shape
     * and secret count before being added; names whose target is ambiguous
     * (e.g. {@code Four-Banner}, {@code Double-Stair}, {@code Redstone-Skull})
     * are deliberately absent -- importing a route into the wrong room is
     * worse than reporting it unmatched.
     */
    private static final Map<String, String> DRM_NAME_ALIASES = Map.ofEntries(
            Map.entry("silvers-sword", "silver-sword"),
            Map.entry("lava-skulls", "lava-pit"),
            Map.entry("crypts", "crypt"),
            Map.entry("sewer", "pipes"),
            Map.entry("super-tall", "supertall"),
            Map.entry("mithril-cave", "mines"),
            Map.entry("dino-dig-site", "dino-site"),
            Map.entry("withermancers", "withermancer"),
            Map.entry("draw-bridge", "bridges"));

    private static Map<String, DungeonRoomDefinition> catalogIndex() {
        Map<String, DungeonRoomDefinition> index = new HashMap<>();
        for (DungeonRoomDefinition definition : DungeonRoomData.allDefinitions()) {
            index.putIfAbsent(definition.id(), definition);
            index.putIfAbsent(DungeonRoomDefinition.normalizeId(definition.displayName()), definition);
        }
        return index;
    }

    /**
     * DRM-lineage names carry a trailing secret count ({@code "Arrow-Trap-1"});
     * try the exact normalized name first, then with that suffix stripped.
     */
    private static DungeonRoomDefinition matchRoom(Map<String, DungeonRoomDefinition> index,
                                                   String rawName) {
        String norm = DungeonRoomDefinition.normalizeId(rawName);
        DungeonRoomDefinition match = index.get(norm);
        if (match != null) return match;
        String stripped = norm.replaceFirst("-\\d+$", "");
        match = index.get(stripped);
        if (match != null) return match;
        String alias = DRM_NAME_ALIASES.get(stripped);
        return alias == null ? null : index.get(alias);
    }

    // ---- SecretRoutes -----------------------------------------------------------

    private static Result parseSecretRoutes(JsonObject root) {
        Map<String, DungeonRoomDefinition> index = catalogIndex();
        Map<String, DungeonRoomDefinition> imported = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        int waypointCount = 0;
        int skippedVariants = 0;

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#") || key.equalsIgnoreCase("version")) continue;
            if (!entry.getValue().isJsonArray()) continue;

            int variantSeparator = key.lastIndexOf(':');
            if (variantSeparator > 0 && isDigits(key.substring(variantSeparator + 1))) {
                skippedVariants++;
                continue;
            }

            DungeonRoomDefinition target = matchRoom(index, key);
            if (target == null) {
                unmatched.add(key);
                continue;
            }
            List<DungeonWaypoint> waypoints =
                    secretRoutesWaypoints(entry.getValue().getAsJsonArray());
            if (waypoints.isEmpty()) continue;

            imported.put(target.id(), target.withWaypoints(waypoints));
            waypointCount += waypoints.size();
        }
        return new Result(List.copyOf(imported.values()), waypointCount,
                List.copyOf(unmatched), skippedVariants, Format.SECRET_ROUTES);
    }

    private static List<DungeonWaypoint> secretRoutesWaypoints(JsonArray steps) {
        List<DungeonWaypoint> waypoints = new ArrayList<>();
        int secretIndex = 0;
        for (JsonElement stepElement : steps) {
            if (!stepElement.isJsonObject()) continue;
            JsonObject step = stepElement.getAsJsonObject();

            int[] position = secretRoutesStepPosition(step);
            if (position == null) continue;
            secretIndex++;

            String secretType = step.has("secret") && step.get("secret").isJsonObject()
                    ? string(step.getAsJsonObject("secret"), "type", "interact")
                    : "interact";
            DungeonSecretCategory category = secretRoutesCategory(secretType);
            DungeonWaypointTrigger trigger = secretRoutesTrigger(secretType);

            List<DungeonHighlight> highlights = new ArrayList<>();
            addActionHighlights(highlights, step, "etherwarps", DungeonSecretCategory.AOTV);
            addActionHighlights(highlights, step, "mines", DungeonSecretCategory.STONK);
            addActionHighlights(highlights, step, "tnts", DungeonSecretCategory.SUPERBOOM);
            addActionHighlights(highlights, step, "interacts", DungeonSecretCategory.LEVER);
            addActionHighlights(highlights, step, "enderpearls", DungeonSecretCategory.PEARL);

            waypoints.add(new DungeonWaypoint(
                    "sr-" + secretIndex,
                    secretIndex,
                    category,
                    trigger,
                    position[0], position[1], position[2],
                    "Secret " + secretIndex + " (" + secretType + ")",
                    highlights));
        }
        return waypoints;
    }

    /** The step's secret position, or its last path point when no secret is recorded. */
    private static int[] secretRoutesStepPosition(JsonObject step) {
        if (step.has("secret") && step.get("secret").isJsonObject()) {
            JsonObject secret = step.getAsJsonObject("secret");
            if (secret.has("location") && secret.get("location").isJsonArray()) {
                int[] position = positionFromArray(secret.getAsJsonArray("location"));
                if (position != null) return position;
            }
        }
        if (step.has("locations") && step.get("locations").isJsonArray()) {
            JsonArray locations = step.getAsJsonArray("locations");
            for (int i = locations.size() - 1; i >= 0; i--) {
                if (!locations.get(i).isJsonArray()) continue;
                int[] position = positionFromArray(locations.get(i).getAsJsonArray());
                if (position != null) return position;
            }
        }
        return null;
    }

    private static void addActionHighlights(List<DungeonHighlight> highlights, JsonObject step,
                                            String key, DungeonSecretCategory category) {
        if (!step.has(key) || !step.get(key).isJsonArray()) return;
        for (JsonElement element : step.getAsJsonArray(key)) {
            if (!element.isJsonArray()) continue;
            int[] position = positionFromArray(element.getAsJsonArray());
            if (position == null) continue;
            highlights.add(new DungeonHighlight(
                    position[0], position[1], position[2],
                    DungeonHighlightStyle.OUTLINE,
                    category.defaultColor));
        }
    }

    private static DungeonSecretCategory secretRoutesCategory(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "bat" -> DungeonSecretCategory.BAT;
            case "item" -> DungeonSecretCategory.ITEM;
            case "exitroute" -> DungeonSecretCategory.ENTRANCE;
            default -> DungeonSecretCategory.DEFAULT;
        };
    }

    private static DungeonWaypointTrigger secretRoutesTrigger(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "bat" -> DungeonWaypointTrigger.KILL_BAT;
            case "item" -> DungeonWaypointTrigger.PICKUP_ITEM;
            // The room-exit marker has no collectible; the player walks past it.
            case "exitroute" -> DungeonWaypointTrigger.MANUAL;
            // "interact" covers chests, levers, and essence without saying which.
            default -> DungeonWaypointTrigger.ANY_SECRET;
        };
    }

    // ---- Odin waypoint packs -------------------------------------------------

    private static Result parseOdinPack(JsonObject root) {
        Map<String, DungeonRoomDefinition> index = catalogIndex();
        Map<String, DungeonRoomDefinition> imported = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        int waypointCount = 0;

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            DungeonRoomDefinition target = matchRoom(index, entry.getKey());
            if (target == null) {
                unmatched.add(entry.getKey());
                continue;
            }
            List<DungeonWaypoint> waypoints = odinWaypoints(entry.getValue().getAsJsonArray());
            if (waypoints.isEmpty()) continue;

            imported.put(target.id(), target.withWaypoints(waypoints));
            waypointCount += waypoints.size();
        }
        return new Result(List.copyOf(imported.values()), waypointCount,
                List.copyOf(unmatched), 0, Format.ODIN_PACK);
    }

    private static List<DungeonWaypoint> odinWaypoints(JsonArray array) {
        List<DungeonWaypoint> waypoints = new ArrayList<>();
        int secretIndex = 0;
        int markerIndex = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject json = element.getAsJsonObject();

            int[] position = odinPosition(json);
            if (position == null) continue;

            String type = string(json, "type", "").toUpperCase(Locale.ROOT);
            boolean legacySecret = json.has("secret")
                    && json.get("secret").isJsonPrimitive()
                    && json.get("secret").getAsJsonPrimitive().isBoolean()
                    && json.get("secret").getAsBoolean();
            String title = string(json, "title", "");
            int color = odinColor(json.get("color"));

            DungeonWaypoint waypoint;
            if (type.equals("SECRET") || legacySecret) {
                secretIndex++;
                waypoint = new DungeonWaypoint(
                        "odin-secret-" + secretIndex, secretIndex,
                        DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.ANY_SECRET,
                        position[0], position[1], position[2],
                        title.isEmpty() ? "Secret " + secretIndex : title,
                        List.of());
            } else if (type.equals("ETHERWARP")) {
                secretIndex++;
                waypoint = new DungeonWaypoint(
                        "odin-ether-" + secretIndex, secretIndex,
                        DungeonSecretCategory.AOTV, DungeonWaypointTrigger.ETHERWARP,
                        position[0], position[1], position[2],
                        title.isEmpty() ? "Etherwarp " + secretIndex : title,
                        List.of());
            } else {
                markerIndex++;
                waypoint = new DungeonWaypoint(
                        "odin-marker-" + markerIndex, 0,
                        DungeonSecretCategory.DEFAULT, DungeonWaypointTrigger.MANUAL,
                        position[0], position[1], position[2],
                        title,
                        List.of());
            }
            waypoints.add(color == DungeonWaypoint.INHERIT_COLOR
                    ? waypoint
                    : waypoint.withCustomColor(color));
        }
        return waypoints;
    }

    private static int[] odinPosition(JsonObject json) {
        if (json.has("blockPos") && json.get("blockPos").isJsonObject()) {
            JsonObject blockPos = json.getAsJsonObject("blockPos");
            return positionFromObject(blockPos);
        }
        if (json.has("x") && json.has("y") && json.has("z")) {
            return positionFromObject(json);
        }
        return null;
    }

    private static int[] positionFromObject(JsonObject json) {
        try {
            int x = json.get("x").getAsInt();
            int y = json.get("y").getAsInt();
            int z = json.get("z").getAsInt();
            return validRoomLocal(x, y, z) ? new int[] { x, y, z } : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] positionFromArray(JsonArray array) {
        if (array.size() < 3) return null;
        try {
            int x = array.get(0).getAsInt();
            int y = array.get(1).getAsInt();
            int z = array.get(2).getAsInt();
            return validRoomLocal(x, y, z) ? new int[] { x, y, z } : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean validRoomLocal(int x, int y, int z) {
        return Math.abs(x) <= MAX_ROOM_LOCAL_ABS
                && Math.abs(z) <= MAX_ROOM_LOCAL_ABS
                && y >= -64 && y <= 320;
    }

    /**
     * Odin serializes color as {@code "#AARRGGBB"}; older configs used the raw
     * HSB field object. Unparseable colors fall back to the category default.
     */
    private static int odinColor(JsonElement element) {
        if (element == null) return DungeonWaypoint.INHERIT_COLOR;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String hex = element.getAsString().trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            try {
                long value = Long.parseLong(hex, 16);
                return (int) (value & 0xFFFFFF);
            } catch (NumberFormatException e) {
                return DungeonWaypoint.INHERIT_COLOR;
            }
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt() & 0xFFFFFF;
        }
        if (element.isJsonObject()) {
            JsonObject json = element.getAsJsonObject();
            if (json.has("hue") && json.has("saturation") && json.has("brightness")) {
                try {
                    float hue = json.get("hue").getAsFloat();
                    float saturation = json.get("saturation").getAsFloat();
                    float brightness = json.get("brightness").getAsFloat();
                    return hsbToRgb(hue, saturation, brightness);
                } catch (RuntimeException e) {
                    return DungeonWaypoint.INHERIT_COLOR;
                }
            }
            if (json.has("r") && json.has("g") && json.has("b")) {
                try {
                    return (channel(json.get("r")) << 16)
                            | (channel(json.get("g")) << 8)
                            | channel(json.get("b"));
                } catch (RuntimeException e) {
                    return DungeonWaypoint.INHERIT_COLOR;
                }
            }
        }
        return DungeonWaypoint.INHERIT_COLOR;
    }

    private static int channel(JsonElement element) {
        float value = element.getAsFloat();
        int channel = value <= 1.0f ? Math.round(value * 255.0f) : Math.round(value);
        return Math.max(0, Math.min(255, channel));
    }

    private static int hsbToRgb(float hue, float saturation, float brightness) {
        // Odin stores hue in degrees; java.awt expects [0,1).
        int rgb = java.awt.Color.HSBtoRGB(hue / 360.0f, clamp01(saturation), clamp01(brightness));
        return rgb & 0xFFFFFF;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    // ---- shared helpers ---------------------------------------------------------

    private static boolean isDigits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    private static String string(JsonObject json, String name, String fallback) {
        if (!json.has(name) || json.get(name).isJsonNull()) return fallback;
        JsonElement element = json.get(name);
        if (!element.isJsonPrimitive()) return fallback;
        return element.getAsString();
    }
}
