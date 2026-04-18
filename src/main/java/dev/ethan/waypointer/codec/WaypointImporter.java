package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

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
 *   - Skytils / Soopy style: raw JSON with either a top-level array of groups or
 *     a single object with a {@code waypoints} array.
 *   - Coleweight-style: a flat JSON array where each entry carries {@code x/y/z},
 *     float {@code r/g/b} in [0,1], and an {@code options} object holding
 *     {@code name} (string or sequence number).
 *
 * Unknown fields are ignored. Missing fields fall back to defaults so a partially
 * malformed payload from a third-party tool still imports the coordinates cleanly.
 */
public final class WaypointImporter {

    public enum Source { WAYPOINTER, SKYBLOCKER, SKYTILS, SOOPY, COLEWEIGHT, JSON }

    /** Skyblocker's current (V1) share-string prefix. Payload after it is base64(gzip(json)). */
    static final String SKYBLOCKER_V1_PREFIX = "[Skyblocker-Waypoint-Data-V1]";

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

    /**
     * @param source one-shot tag for which decoder pipeline matched.
     * @param groups parsed groups; never empty (if no groups parse, importAny throws instead).
     * @param label  sender-supplied label from the codec header; empty for any non-Waypointer
     *               source or for Waypointer payloads that didn't carry one.
     */
    public record ImportResult(Source source, List<WaypointGroup> groups, String label) {}

    private WaypointImporter() {}

    public static ImportResult importAny(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        String trimmed = payload.trim();

        if (WaypointCodec.isCodecString(trimmed)) {
            WaypointCodec.Decoded d = WaypointCodec.decodeFull(trimmed);
            return new ImportResult(Source.WAYPOINTER, d.groups(), d.label());
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
            return importJson(trimmed);
        }

        // Skyblocker exports (and some Skytils strings) are base64(gzip(json)).
        try {
            String decoded = decodeBase64Gzip(trimmed);
            if (looksLikeJson(decoded)) {
                ImportResult r = importJson(decoded);
                return new ImportResult(Source.SKYBLOCKER, r.groups(), "");
            }
        } catch (Exception ignore) {
            // Fall through to error below; preserve the original so the user sees useful feedback.
        }

        throw new IllegalArgumentException("unrecognized waypoint payload (tried Waypointer, Skyblocker, JSON)");
    }

    /**
     * Decode a {@code [Skyblocker-...]<base64>} wrapper, strip the prefix, run
     * base64+gzip, and hand the resulting JSON to {@link #importJson}. Source
     * tagging is forced to {@link Source#SKYBLOCKER} regardless of the inner
     * JSON shape -- the prefix is a stronger signal of origin than our
     * content-sniffing heuristics.
     */
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
        return new ImportResult(Source.SKYBLOCKER, r.groups(), "");
    }

    // --- JSON path ---------------------------------------------------------------------------

    private static ImportResult importJson(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<WaypointGroup> groups = new ArrayList<>();
        Source source = Source.JSON;

        if (root.isJsonArray()) {
            // Either a list of groups, a coleweight flat route, or a list of loose waypoints.
            // Distinguish by content shape: groups have nested point arrays, coleweight has an
            // `options` object per point, loose waypoints are plain {x,y,z,...} dicts.
            JsonArray arr = root.getAsJsonArray();
            if (looksLikeGroupArray(arr)) {
                for (JsonElement el : arr) groups.add(parseGroup(el.getAsJsonObject()));
            } else if (looksLikeColeweightArray(arr)) {
                groups.add(parseColeweightRoute(arr));
                source = Source.COLEWEIGHT;
            } else {
                WaypointGroup g = WaypointGroup.create("Imported", Zone.UNKNOWN.id());
                g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                for (JsonElement el : arr) if (el.isJsonObject()) addWaypointFromLoose(g, el.getAsJsonObject());
                groups.add(g);
            }
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            // Soopy/Skytils-esque single-group object.
            if (obj.has("waypoints") && obj.get("waypoints").isJsonArray()) {
                groups.add(parseGroup(obj));
                source = Source.SKYTILS;
            } else if (obj.has("groups") && obj.get("groups").isJsonArray()) {
                // Could be a Waypointer JSON export (verbatim storage dump).
                for (JsonElement el : obj.getAsJsonArray("groups")) groups.add(parseGroup(el.getAsJsonObject()));
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
                        for (JsonElement el : val.getAsJsonArray()) {
                            if (el.isJsonObject()) addWaypointFromLoose(g, el.getAsJsonObject());
                        }
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

    private static boolean looksLikeGroupArray(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("waypoints") || o.has("points")) return true;
        }
        return false;
    }

    /**
     * Coleweight's signature: each entry has its metadata nested under {@code options}.
     * The other supported formats all put {@code name}/{@code color} at the top level,
     * so the presence of an {@code options} object on a coord-bearing entry is a clean
     * marker we can use without false positives.
     */
    private static boolean looksLikeColeweightArray(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!(o.has("x") && o.has("y") && o.has("z"))) continue;
            if (o.has("options") && o.get("options").isJsonObject()) return true;
        }
        return false;
    }

    /**
     * Build a single route group from a coleweight export.
     *
     * Coleweight stores sequential routes where waypoint order matters but isn't
     * guaranteed by the JSON array order -- the option {@code name} field holds the
     * intended step index. We sort by that when every entry names itself with an
     * integer so the imported group walks in the same order the author intended.
     * Non-numeric names disable the sort and we fall back to JSON array order,
     * which is still correct for any sensibly-written export.
     *
     * AUTO gradient is chosen deliberately: coleweight routes almost always paint
     * every point the same color, so preserving those identical colors (as MANUAL
     * does for Skytils/Skyblocker) would leave the player unable to see route
     * direction at a glance. The gradient sweeps cool -> hot so "next" reads
     * clearly.
     */
    private static WaypointGroup parseColeweightRoute(JsonArray arr) {
        WaypointGroup g = WaypointGroup.create("Coleweight Route", Zone.UNKNOWN.id());
        g.setGradientMode(WaypointGroup.GradientMode.AUTO);

        List<JsonObject> points = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("x") && o.has("y") && o.has("z")) points.add(o);
        }

        if (allOptionNamesAreIntegers(points)) {
            points.sort(Comparator.comparingInt(WaypointImporter::coleweightOptionNameAsInt));
        }

        for (JsonObject p : points) addWaypointFromColeweight(g, p);
        return g;
    }

    private static void addWaypointFromColeweight(WaypointGroup g, JsonObject o) {
        int x = o.get("x").getAsInt();
        int y = o.get("y").getAsInt();
        int z = o.get("z").getAsInt();
        String name = coleweightName(o);
        int color = parseColeweightColor(o);
        g.add(new Waypoint(x, y, z, name, color, 0, 0.0));
    }

    /**
     * Coleweight R/G/B are normalized floats in [0,1]. A coleweight export with
     * integer literals (e.g. {@code "r":0,"g":1,"b":0}) is still valid -- those
     * are just the endpoints of the same [0,1] range, not byte values. Either
     * way, scaling by 255 gives the correct 8-bit channel.
     */
    private static int parseColeweightColor(JsonObject o) {
        if (!(o.has("r") && o.has("g") && o.has("b"))) return Waypoint.DEFAULT_COLOR;
        return coleweightRgb(
                o.get("r").getAsDouble(),
                o.get("g").getAsDouble(),
                o.get("b").getAsDouble());
    }

    /**
     * Visible for tests: the pure float-to-24-bit-RGB conversion used by the
     * coleweight path. Extracted so tests can assert the channel math without
     * having to work around the AUTO gradient that overwrites per-point colors
     * the moment a waypoint is added to a coleweight group.
     */
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

        // Imports carry explicit per-waypoint colors that must not be overwritten
        // by auto-gradient coloring when we add() them below. The exception is
        // Skyblocker's ordered routes: they're step-by-step paths where every
        // point normally shares one color, and an auto gradient makes the
        // sequence visually readable at a glance (same rationale as Coleweight).
        boolean ordered = o.has("ordered")
                && o.get("ordered").isJsonPrimitive()
                && o.get("ordered").getAsJsonPrimitive().isBoolean()
                && o.get("ordered").getAsBoolean();
        g.setGradientMode(ordered
                ? WaypointGroup.GradientMode.AUTO
                : WaypointGroup.GradientMode.MANUAL);

        if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
            g.setEnabled(o.get("enabled").getAsBoolean());
        }

        JsonArray pts = firstArray(o, "waypoints", "points");
        if (pts != null) {
            for (JsonElement el : pts) if (el.isJsonObject()) addWaypointFromLoose(g, el.getAsJsonObject());
        }
        return g;
    }

    private static void addWaypointFromLoose(WaypointGroup g, JsonObject o) {
        int[] pos = extractCoordinates(o);
        if (pos == null) return;

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
        g.add(new Waypoint(pos[0], pos[1], pos[2], name, color, 0, radius));
    }

    /**
     * Pull x/y/z out of either the flat {@code {x, y, z}} shape used by Coleweight,
     * Skytils, and Waypointer's own JSON export, or the {@code pos: [x, y, z]} array
     * shape Skyblocker's V1 codec emits. Returns null when neither form yields
     * three numeric coordinates, so the caller can skip the point without throwing.
     */
    private static int[] extractCoordinates(JsonObject o) {
        if (o.has("x") && o.has("y") && o.has("z")) {
            return new int[]{
                    o.get("x").getAsInt(),
                    o.get("y").getAsInt(),
                    o.get("z").getAsInt()
            };
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

    /**
     * Color encodings we see in the wild:
     *  - "color": 0xRRGGBB int
     *  - "color": "#RRGGBB" string
     *  - "color": "a:rr:gg:bb:bb" (Skytils: alpha-scale + chroma flag -- we ignore the prefix)
     *  - {r, g, b} 0-255 triple (Skyblocker legacy)
     *  - "colorComponents": [r, g, b] floats in [0, 1] (Skyblocker V1)
     */
    private static int parseColor(JsonObject o) {
        if (o.has("color")) {
            JsonElement c = o.get("color");
            if (c.isJsonPrimitive()) {
                if (c.getAsJsonPrimitive().isNumber()) return c.getAsInt() & 0xFFFFFF;
                String s = c.getAsString().trim();
                if (s.startsWith("#")) s = s.substring(1);
                if (s.contains(":")) {
                    // Skytils: "<scale>:<a>:<r>:<g>:<b>" (values hex). Keep rgb bytes.
                    String[] parts = s.split(":");
                    if (parts.length >= 5) {
                        int r = Integer.parseInt(parts[2], 16);
                        int gV = Integer.parseInt(parts[3], 16);
                        int b = Integer.parseInt(parts[4], 16);
                        return ((r & 0xFF) << 16) | ((gV & 0xFF) << 8) | (b & 0xFF);
                    }
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

    private static boolean isByteColor(JsonObject o) {
        return o.has("r") && o.has("g") && o.has("b")
                && o.get("r").isJsonPrimitive() && o.get("g").isJsonPrimitive() && o.get("b").isJsonPrimitive()
                && o.get("r").getAsJsonPrimitive().isNumber();
    }

    private static int clampByte(int v) { return Math.max(0, Math.min(255, v)); }

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

    /**
     * Normalize common island/zone identifiers between mods. Skyblocker and Skytils
     * both use dashed or display-cased ids that we store as lowercase underscores.
     *
     * After lowercasing, the id is checked against {@link #SKYBLOCKER_ISLAND_ALIASES}
     * so Hypixel-internal names like {@code foraging_1} land on Waypointer's
     * display-oriented ids like {@code the_park}. Unknown ids fall through
     * unchanged and either match a Waypointer zone verbatim (generic ids like
     * {@code garden}) or become a new prettified zone the user can rebind from
     * the UI.
     */
    static String normalizeZone(String raw) {
        if (raw == null || raw.isBlank()) return Zone.UNKNOWN.id();
        String s = raw.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String aliased = SKYBLOCKER_ISLAND_ALIASES.get(s);
        return aliased == null ? s : aliased;
    }

    // --- helpers ------------------------------------------------------------------------------

    private static boolean looksLikeJson(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    private static String decodeBase64Gzip(String s) throws Exception {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            data = Base64.getUrlDecoder().decode(s);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
