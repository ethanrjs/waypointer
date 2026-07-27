package com.babbur.waypointer.dungeon.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.babbur.waypointer.dungeon.DungeonHighlight;
import com.babbur.waypointer.dungeon.DungeonHighlightStyle;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
     * @param skippedVariants retained for payload/report compatibility; current
     *                        imports preserve all SecretRoutes variants
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
     * and upstream core hashes before being added. The less-obvious aliases at
     * the end use exact core-hash matches between SecretRoutes' room catalog and
     * Waypointer's bundled catalog, rather than spelling guesses.
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
            Map.entry("draw-bridge", "bridges"),
            Map.entry("four-banner", "banners"),
            Map.entry("double-stair", "staircase"),
            Map.entry("redstone-skull", "redstone-crypt"));

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
        List<DungeonRoomDefinition> imported = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int waypointCount = 0;

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#") || key.equalsIgnoreCase("version")) continue;
            if (!entry.getValue().isJsonArray()) continue;

            int variantSeparator = key.lastIndexOf(':');
            boolean variant = variantSeparator > 0
                    && isDigits(key.substring(variantSeparator + 1));
            String roomKey = variant ? key.substring(0, variantSeparator) : key;
            int routeNumber = variant ? routeNumber(key.substring(variantSeparator + 1)) : 1;

            DungeonRoomDefinition target = matchRoom(index, roomKey);
            if (target == null) {
                unmatched.add(key);
                continue;
            }
            List<DungeonWaypoint> waypoints =
                    secretRoutesWaypoints(entry.getValue().getAsJsonArray());
            if (waypoints.isEmpty()) continue;

            imported.add(target.withDisplayName(
                    target.displayName() + " — Route " + routeNumber)
                    .withWaypoints(waypoints));
            waypointCount += waypoints.size();
        }
        return new Result(List.copyOf(imported), waypointCount,
                List.copyOf(unmatched), 0, Format.SECRET_ROUTES);
    }

    private static List<DungeonWaypoint> secretRoutesWaypoints(JsonArray steps) {
        List<DungeonWaypoint> waypoints = new ArrayList<>();
        int secretIndex = 0;
        for (JsonElement stepElement : steps) {
            if (!stepElement.isJsonObject()) continue;
            JsonObject step = stepElement.getAsJsonObject();

            secretIndex++;
            List<int[]> path = positions(step, "locations");
            List<SecretRoutesAction> actions = new ArrayList<>();
            addActions(actions, step, "etherwarps", DungeonSecretCategory.ETHERWARP,
                    DungeonWaypointTrigger.ETHERWARP, "TP", path);
            addActions(actions, step, "mines", DungeonSecretCategory.STONK,
                    DungeonWaypointTrigger.BREAK_BLOCKS, "", path);
            addActions(actions, step, "tnts", DungeonSecretCategory.SUPERBOOM,
                    DungeonWaypointTrigger.USE_SUPERBOOM, "TNT", path);
            addActions(actions, step, "interacts", DungeonSecretCategory.LEVER,
                    DungeonWaypointTrigger.FLIP_LEVER, "Lever", path);
            addActions(actions, step, "enderpearls", DungeonSecretCategory.PEARL,
                    DungeonWaypointTrigger.THROW_PEARL, "Pearl", path);
            actions.sort(Comparator.comparingInt(SecretRoutesAction::pathIndex)
                    .thenComparingInt(SecretRoutesAction::sourceIndex));

            int actionIndex = 0;
            for (SecretRoutesAction action : actions) {
                List<DungeonHighlight> highlights = action.trigger()
                        == DungeonWaypointTrigger.THROW_PEARL
                        ? pearlTarget(action, path, step)
                        : List.of();
                waypoints.add(new DungeonWaypoint(
                        "sr-" + secretIndex + "-action-" + (++actionIndex),
                        secretIndex,
                        action.category(),
                        action.trigger(),
                        action.position()[0], action.position()[1], action.position()[2],
                        action.label(),
                        highlights));
            }

            int[] position = secretRoutesStepPosition(step);
            if (position == null) continue;
            String secretType = step.has("secret") && step.get("secret").isJsonObject()
                    ? string(step.getAsJsonObject("secret"), "type", "interact")
                    : "interact";
            DungeonSecretCategory category = secretRoutesCategory(secretType);
            DungeonWaypointTrigger trigger = secretRoutesTrigger(secretType);

            waypoints.add(new DungeonWaypoint(
                    "sr-" + secretIndex,
                    secretIndex,
                    category,
                    trigger,
                    position[0], position[1], position[2],
                    secretLabel(secretType),
                    List.of()));
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

    private static List<int[]> positions(JsonObject step, String key) {
        List<int[]> positions = new ArrayList<>();
        if (!step.has(key) || !step.get(key).isJsonArray()) return positions;
        for (JsonElement element : step.getAsJsonArray(key)) {
            if (!element.isJsonArray()) continue;
            int[] position = positionFromArray(element.getAsJsonArray());
            if (position != null) positions.add(position);
        }
        return positions;
    }

    private static void addActions(List<SecretRoutesAction> actions, JsonObject step,
                                   String key, DungeonSecretCategory category,
                                   DungeonWaypointTrigger trigger, String label,
                                   List<int[]> path) {
        List<int[]> positions = positions(step, key);
        for (int i = 0; i < positions.size(); i++) {
            int[] position = positions.get(i);
            actions.add(new SecretRoutesAction(position, category, trigger, label,
                    nearestPathIndex(position, path), i));
        }
    }

    private static int nearestPathIndex(int[] position, List<int[]> path) {
        if (path.isEmpty()) return Integer.MAX_VALUE;
        int bestIndex = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            int[] point = path.get(i);
            long dx = (long) point[0] - position[0];
            long dy = (long) point[1] - position[1];
            long dz = (long) point[2] - position[2];
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static List<DungeonHighlight> pearlTarget(SecretRoutesAction action,
                                                       List<int[]> path,
                                                       JsonObject step) {
        int[] target = null;
        if (!path.isEmpty() && action.pathIndex() < path.size() - 1) {
            target = path.get(action.pathIndex() + 1);
        }
        if (target == null) target = secretRoutesStepPosition(step);
        return target == null
                ? List.of()
                : List.of(DungeonHighlight.outline(target[0], target[1], target[2]));
    }

    private static String secretLabel(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "bat" -> "Bat";
            case "item" -> "Item";
            case "exitroute" -> "Exit";
            default -> "Chest";
        };
    }

    private record SecretRoutesAction(
            int[] position,
            DungeonSecretCategory category,
            DungeonWaypointTrigger trigger,
            String label,
            int pathIndex,
            int sourceIndex) {}

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
     * Odin serializes color as {@code "#RRGGBBAA"}. The previous importer
     * documented {@code "#AARRGGBB"}, so the common old opaque-leading form
     * with a zero trailing byte remains accepted. Older configs also
     * used raw HSB field objects. Unparseable colors use the category default.
     */
    private static int odinColor(JsonElement element) {
        if (element == null) return DungeonWaypoint.INHERIT_COLOR;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String hex = element.getAsString().trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            try {
                long value = Long.parseLong(hex, 16);
                if (hex.length() == 8) {
                    int leadingByte = (int) ((value >>> 24) & 0xFF);
                    int trailingByte = (int) (value & 0xFF);
                    if (leadingByte == 0xFF && trailingByte == 0x00) {
                        return (int) (value & 0xFFFFFF);
                    }
                    return (int) ((value >>> 8) & 0xFFFFFF);
                }
                if (hex.length() != 6) return DungeonWaypoint.INHERIT_COLOR;
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

    private static int routeNumber(String suffix) {
        try {
            return Math.max(1, Math.addExact(Integer.parseInt(suffix), 1));
        } catch (ArithmeticException | NumberFormatException ignored) {
            return 1;
        }
    }

    private static String string(JsonObject json, String name, String fallback) {
        if (!json.has(name) || json.get(name).isJsonNull()) return fallback;
        JsonElement element = json.get(name);
        if (!element.isJsonPrimitive()) return fallback;
        return element.getAsString();
    }
}
