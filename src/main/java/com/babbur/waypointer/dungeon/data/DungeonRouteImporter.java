package com.babbur.waypointer.dungeon.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonHighlight;
import com.babbur.waypointer.dungeon.DungeonHighlightStyle;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Imports third-party dungeon secret routes into room-local waypoint groups.
 *
 * <p>Supported formats, sniffed from the payload shape:
 *
 * <ul>
 *   <li><b>Waypointer</b> -- a {@code WPD:} share payload or the native
 *       {@code {"schema":…,"rooms":[…]}} JSON.</li>
 *   <li><b>SecretRoutes</b> ({@code routes.json}) -- a map of DungeonRoomsMod
 *       room names to ordered route steps. Each step becomes a main waypoint
 *       with colored action SubWPs, a secret, and passive path guides.</li>
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
     * @param groups          room-local dungeon routes ready for normal storage
     * @param waypointCount   total imported waypoints across all rooms
     * @param unmatchedRooms  source room names that matched no catalog room
     * @param skippedVariants retained for payload/report compatibility; current
     *                        imports preserve all SecretRoutes variants
     */
    public record Result(List<WaypointGroup> groups,
                         int waypointCount,
                         List<String> unmatchedRooms,
                         int skippedVariants,
                         Format format) {}

    private static final int MAX_PAYLOAD_CHARS = 16 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    /** Room-local coordinates live in [0, 4*32); anything far outside is world data. */
    private static final int MAX_ROOM_LOCAL_ABS = 512;
    private static final String SECRET_ROUTES_ROOM_CORES_RESOURCE =
            "/assets/waypointer/dungeons/catacombs/secret-routes-room-cores.json";
    private static final Map<String, List<Integer>> SECRET_ROUTES_ROOM_CORES =
            loadSecretRoutesRoomCores();
    private static final Map<String, List<Integer>> SECRET_ROUTES_SUFFIX_FREE_CORES =
            buildSecretRoutesSuffixFreeCores(SECRET_ROUTES_ROOM_CORES);

    private DungeonRouteImporter() {}

    public static Result parse(String payload) {
        try {
            return parseUnchecked(payload);
        } catch (IllegalArgumentException invalidPayload) {
            throw invalidPayload;
        } catch (RuntimeException malformedPayload) {
            String detail = malformedPayload.getMessage();
            throw new IllegalArgumentException(detail == null || detail.isBlank()
                    ? "route import payload is malformed"
                    : "route import payload is malformed: " + detail, malformedPayload);
        }
    }

    private static Result parseUnchecked(String payload) {
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
            return new Result(decoded.routes(), decoded.waypointCount(),
                    List.of(), 0, Format.WAYPOINTER);
        }

        JsonObject root = parseRootObject(trimmed);
        if (root.has("rooms") && root.get("rooms").isJsonArray()) {
            List<WaypointGroup> routes = DungeonRoomShareCodec.decodeLegacyJson(trimmed);
            return new Result(routes, DungeonRoomShareCodec.waypointCount(routes),
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

    /** Established external name aliases retained for Odin waypoint packs. */
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

    private static Map<String, DungeonRoomCatalogEntry> catalogIndex() {
        Map<String, DungeonRoomCatalogEntry> index = new HashMap<>();
        for (DungeonRoomCatalogEntry entry : DungeonRoomData.allEntries()) {
            index.putIfAbsent(entry.id(), entry);
            index.putIfAbsent(DungeonRoomCatalogEntry.normalizeId(entry.displayName()), entry);
        }
        return index;
    }

    /**
     * DRM-lineage names carry a trailing secret count ({@code "Arrow-Trap-1"});
     * try the exact normalized name first, then with that suffix stripped.
     */
    private static DungeonRoomCatalogEntry matchRoom(Map<String, DungeonRoomCatalogEntry> index,
                                                     String rawName) {
        String norm = DungeonRoomCatalogEntry.normalizeId(rawName);
        DungeonRoomCatalogEntry match = index.get(norm);
        if (match != null) return match;
        String stripped = norm.replaceFirst("-\\d+$", "");
        match = index.get(stripped);
        if (match != null) return match;
        String alias = DRM_NAME_ALIASES.get(stripped);
        return alias == null ? null : index.get(alias);
    }

    private static Map<String, List<Integer>> loadSecretRoutesRoomCores() {
        try (InputStream stream = DungeonRouteImporter.class
                .getResourceAsStream(SECRET_ROUTES_ROOM_CORES_RESOURCE)) {
            if (stream == null) return Map.of();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject rooms = root.getAsJsonObject("rooms");
                if (rooms == null) return Map.of();

                Map<String, List<Integer>> loaded = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : rooms.entrySet()) {
                    if (!entry.getValue().isJsonArray()) continue;
                    List<Integer> coreHashes = new ArrayList<>();
                    for (JsonElement core : entry.getValue().getAsJsonArray()) {
                        if (core.isJsonPrimitive() && core.getAsJsonPrimitive().isNumber()) {
                            coreHashes.add(core.getAsInt());
                        }
                    }
                    String normalizedName = DungeonRoomCatalogEntry.normalizeId(entry.getKey());
                    if (loaded.putIfAbsent(normalizedName, List.copyOf(coreHashes)) != null) {
                        // A normalized source-name collision is not safe to guess through.
                        loaded.put(normalizedName, List.of());
                    }
                }
                return Map.copyOf(loaded);
            }
        } catch (RuntimeException | IOException invalidResource) {
            return Map.of();
        }
    }

    private static DungeonRoomCatalogEntry matchSecretRoutesRoom(String rawName) {
        String normalizedName = DungeonRoomCatalogEntry.normalizeId(rawName);
        List<Integer> coreHashes = SECRET_ROUTES_ROOM_CORES.get(normalizedName);
        if (coreHashes == null) {
            coreHashes = SECRET_ROUTES_SUFFIX_FREE_CORES.get(normalizedName);
        }
        return matchUniqueCoreRoom(DungeonRoomData.allEntries(), coreHashes);
    }

    /** Keeps suffix-free aliases from the pinned table. Combined hashes reject ambiguous names such as Waterfall. */
    private static Map<String, List<Integer>> buildSecretRoutesSuffixFreeCores(
            Map<String, List<Integer>> sourceRooms) {
        Map<String, List<Integer>> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : sourceRooms.entrySet()) {
            String alias = entry.getKey().replaceFirst("-\\d+$", "");
            if (alias.equals(entry.getKey())) continue;
            aliases.merge(alias, entry.getValue(), (existing, additional) -> {
                List<Integer> combined = new ArrayList<>(existing);
                for (Integer coreHash : additional) {
                    if (!combined.contains(coreHash)) combined.add(coreHash);
                }
                return List.copyOf(combined);
            });
        }
        return Map.copyOf(aliases);
    }

    /** Returns a room only when the supplied core hashes identify one catalog entry. */
    static DungeonRoomCatalogEntry matchUniqueCoreRoom(
            Collection<DungeonRoomCatalogEntry> entries, List<Integer> coreHashes) {
        if (entries == null || coreHashes == null || coreHashes.isEmpty()) return null;
        DungeonRoomCatalogEntry matched = null;
        for (DungeonRoomCatalogEntry entry : entries) {
            boolean sharesCore = false;
            for (Integer coreHash : coreHashes) {
                if (entry.coreHashes().contains(coreHash)) {
                    sharesCore = true;
                    break;
                }
            }
            if (!sharesCore) continue;
            if (matched != null) return null;
            matched = entry;
        }
        return matched;
    }

    // ---- SecretRoutes -----------------------------------------------------------

    private static Result parseSecretRoutes(JsonObject root) {
        List<WaypointGroup> imported = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#") || key.equalsIgnoreCase("version")) continue;
            if (!entry.getValue().isJsonArray()) continue;

            int variantSeparator = key.lastIndexOf(':');
            boolean variant = variantSeparator > 0
                    && isDigits(key.substring(variantSeparator + 1));
            String roomKey = variant ? key.substring(0, variantSeparator) : key;
            int routeNumber = variant ? routeNumber(key.substring(variantSeparator + 1)) : 1;

            DungeonRoomCatalogEntry target = matchSecretRoutesRoom(roomKey);
            if (target == null) {
                unmatched.add(key);
                continue;
            }
            Map<Integer, List<int[]>> pathGuides = new LinkedHashMap<>();
            List<DungeonWaypoint> waypoints =
                    secretRoutesWaypoints(entry.getValue().getAsJsonArray(), pathGuides);
            if (waypoints.isEmpty()) continue;

            imported.add(DungeonRouteGroupAdapter.fromWaypoints(
                    target.id(), target.displayName() + ", route " + routeNumber, waypoints, pathGuides));
        }
        return new Result(List.copyOf(imported), DungeonRoomShareCodec.waypointCount(imported),
                List.copyOf(unmatched), 0, Format.SECRET_ROUTES);
    }

    private static List<DungeonWaypoint> secretRoutesWaypoints(
            JsonArray steps, Map<Integer, List<int[]>> pathGuides) {
        List<DungeonWaypoint> waypoints = new ArrayList<>();
        int secretIndex = 0;
        for (JsonElement stepElement : steps) {
            if (!stepElement.isJsonObject()) continue;
            JsonObject step = stepElement.getAsJsonObject();

            secretIndex++;
            List<int[]> path = positions(step, "locations");
            pathGuides.put(secretIndex, path);
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

            String secretType = step.has("secret") && step.get("secret").isJsonObject()
                    ? string(step.getAsJsonObject("secret"), "type", "interact")
                    : "interact";
            // A dummy has no collectible; some packs leave its placeholder at the origin.
            if (secretType.equalsIgnoreCase("dummy") && !actions.isEmpty()) continue;
            int[] position = secretType.equalsIgnoreCase("dummy")
                    ? (path.isEmpty() ? null : path.getLast()) : secretRoutesStepPosition(step);
            if (position == null) continue;
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
            case "exit", "exitroute" -> "Exit";
            case "dummy" -> "Move";
            case "prince" -> "Prince";
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
            case "exit", "exitroute" -> DungeonSecretCategory.ENTRANCE;
            case "prince" -> DungeonSecretCategory.PRINCE;
            default -> DungeonSecretCategory.DEFAULT;
        };
    }

    private static DungeonWaypointTrigger secretRoutesTrigger(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "bat" -> DungeonWaypointTrigger.KILL_BAT;
            case "item" -> DungeonWaypointTrigger.PICKUP_ITEM;
            // The room-exit marker has no collectible; the player walks past it.
            case "exit", "exitroute" -> DungeonWaypointTrigger.MANUAL;
            case "dummy", "prince" -> DungeonWaypointTrigger.MANUAL;
            // "interact" covers chests, levers, and essence without saying which.
            default -> DungeonWaypointTrigger.ANY_SECRET;
        };
    }

    // ---- Odin waypoint packs -------------------------------------------------

    private static Result parseOdinPack(JsonObject root) {
        Map<String, DungeonRoomCatalogEntry> index = catalogIndex();
        Map<String, WaypointGroup> imported = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            DungeonRoomCatalogEntry target = matchRoom(index, entry.getKey());
            if (target == null) {
                unmatched.add(entry.getKey());
                continue;
            }
            WaypointGroup group = odinRoute(target, entry.getValue().getAsJsonArray());
            if (!group.isEmpty()) imported.put(target.id(), group);
        }
        return new Result(List.copyOf(imported.values()),
                DungeonRoomShareCodec.waypointCount(imported.values()),
                List.copyOf(unmatched), 0, Format.ODIN_PACK);
    }

    private static WaypointGroup odinRoute(DungeonRoomCatalogEntry target, JsonArray array) {
        WaypointGroup group = WaypointGroup.create(target.displayName(), target.id());
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setSkipAheadEnabled(false);
        List<Waypoint> waypoints = new ArrayList<>();
        List<Waypoint> leadingMarkers = new ArrayList<>();
        int secretIndex = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject json = element.getAsJsonObject();

            int[] position = odinPosition(json);
            if (position == null) continue;

            String type = string(json, "type", "").toUpperCase(Locale.ROOT);
            boolean secret = type.equals("SECRET") || booleanValue(json, "secret");
            boolean etherwarp = !secret && type.equals("ETHERWARP");
            String title = WaypointCodec.Options.sanitizeLabel(string(json, "title", "").trim());
            if (title.equalsIgnoreCase("Enter text")) title = "";
            int color = odinColor(json.get("color"));
            int flags;
            if (secret) {
                secretIndex++;
                flags = Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET;
                if (title.isEmpty()) title = "Secret " + secretIndex;
                if (color == DungeonWaypoint.INHERIT_COLOR) color = DungeonSecretCategory.CHEST.defaultColor;
            } else if (etherwarp) {
                secretIndex++;
                flags = Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP;
                if (title.isEmpty()) title = "Etherwarp " + secretIndex;
                if (color == DungeonWaypoint.INHERIT_COLOR) color = DungeonSecretCategory.ETHERWARP.defaultColor;
            } else {
                flags = Waypoint.FLAG_SUBWAYPOINT;
                if (booleanValue(json, "filled")) flags |= Waypoint.FLAG_FILLED_SUBWAYPOINT;
                if (color == DungeonWaypoint.INHERIT_COLOR) color = DungeonSecretCategory.SUPERBOOM.defaultColor;
            }
            if (booleanValue(json, "depth") || etherwarp && !json.has("depth")) {
                flags |= Waypoint.FLAG_DEPTH_CHECKED;
            }
            Waypoint waypoint = new Waypoint(position[0], position[1], position[2],
                    title, color, flags, 0.0);
            if (secretIndex == 0) {
                leadingMarkers.add(waypoint);
            } else {
                waypoints.add(waypoint);
                if (!leadingMarkers.isEmpty()) {
                    waypoints.addAll(leadingMarkers);
                    leadingMarkers.clear();
                }
            }
        }
        if (secretIndex == 0) {
            group.setLoadMode(WaypointGroup.LoadMode.STATIC);
            for (Waypoint marker : leadingMarkers) waypoints.add(marker.withSubwaypoint(false));
        }
        group.addAll(waypoints);
        return group;
    }

    private static boolean booleanValue(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static int[] odinPosition(JsonObject json) {
        if (json.has("blockPos")) {
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
            int x = json.get(json.has("x") ? "x" : "field_11175").getAsInt();
            int y = json.get(json.has("y") ? "y" : "field_11174").getAsInt();
            int z = json.get(json.has("z") ? "z" : "field_11173").getAsInt();
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
        return x >= -MAX_ROOM_LOCAL_ABS && x <= MAX_ROOM_LOCAL_ABS
                && z >= -MAX_ROOM_LOCAL_ABS && z <= MAX_ROOM_LOCAL_ABS
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
