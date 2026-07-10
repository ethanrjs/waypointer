package dev.ethan.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Encodes routes into share formats owned by Waypointer and supported SkyBlock
 * mods. Third-party formats intentionally live outside {@link WaypointCodec} so
 * the native binary wire format can evolve without mixing in compatibility JSON.
 */
public final class WaypointExportCodec {

    /**
     * User-selectable export targets. Capability flags are UI-facing: they say
     * whether a Waypointer option can survive a round trip through that target.
     */
    public enum Target {
        WAYPOINTER("Waypointer", true, true, true, true, true, true),
        SKYBLOCKER("Skyblocker", false, true, false, false, false, false),
        SKYTILS("Skytils", false, true, false, false, false, false),
        SKYHANNI("SkyHanni", false, false, false, false, false, false);

        private final String displayName;
        private final boolean supportsNames;
        private final boolean supportsColors;
        private final boolean supportsRadii;
        private final boolean supportsWaypointFlags;
        private final boolean supportsGroupMeta;
        private final boolean supportsLabel;

        Target(String displayName, boolean supportsNames, boolean supportsColors,
               boolean supportsRadii, boolean supportsWaypointFlags,
               boolean supportsGroupMeta, boolean supportsLabel) {
            this.displayName = displayName;
            this.supportsNames = supportsNames;
            this.supportsColors = supportsColors;
            this.supportsRadii = supportsRadii;
            this.supportsWaypointFlags = supportsWaypointFlags;
            this.supportsGroupMeta = supportsGroupMeta;
            this.supportsLabel = supportsLabel;
        }

        public String displayName()          { return displayName; }

        public boolean supportsNames()       { return supportsNames; }

        public boolean supportsColors()      { return supportsColors; }

        public boolean supportsRadii()       { return supportsRadii; }

        public boolean supportsWaypointFlags() { return supportsWaypointFlags; }

        public boolean supportsGroupMeta()   { return supportsGroupMeta; }

        public boolean supportsLabel()       { return supportsLabel; }

        public Target next() {
            Target[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public WaypointCodec.Options coerce(WaypointCodec.Options opts) {
            return opts.toBuilder()
                    .includeNames(supportsNames && opts.includeNames)
                    .includeColors(supportsColors && opts.includeColors)
                    .includeRadii(supportsRadii && opts.includeRadii)
                    .includeWaypointFlags(supportsWaypointFlags && opts.includeWaypointFlags)
                    .includeGroupMeta(supportsGroupMeta && opts.includeGroupMeta)
                    .label(supportsLabel ? opts.label : "")
                    .build();
        }
    }

    private static final Map<String, String> SKYBLOCKER_ISLAND_IDS = Map.ofEntries(
            Map.entry("private_island", "dynamic"),
            Map.entry("the_farming_isles", "farming_1"),
            Map.entry("the_park", "foraging_1"),
            Map.entry("galatea", "foraging_2"),
            Map.entry("spiders_den", "combat_1"),
            Map.entry("the_end", "combat_3"),
            Map.entry("gold_mine", "mining_1"),
            Map.entry("deep_caverns", "mining_2"),
            Map.entry("dwarven_mines", "mining_3"),
            Map.entry("backwater_bayou", "fishing_1"),
            Map.entry("mineshaft", "mineshaft"),
            Map.entry("mineshaft_unknown", "mineshaft"),
            Map.entry("dungeon", "dungeon"),
            Map.entry("hub", "hub"),
            Map.entry("dungeon_hub", "dungeon_hub"),
            Map.entry("rift", "rift"),
            Map.entry("crystal_hollows", "crystal_hollows"),
            Map.entry("kuudra", "kuudra"),
            Map.entry("garden", "garden"),
            Map.entry("winter", "winter"),
            Map.entry("crimson_isle", "crimson_isle"),
            Map.entry("dark_auction", "dark_auction"),
            Map.entry("unknown", "unknown")
    );

    private WaypointExportCodec() {}

    public static String encode(List<WaypointGroup> groups, WaypointCodec.Options opts, Target target) {
        WaypointCodec.Options safeOpts = target.coerce(opts);
        return switch (target) {
            case WAYPOINTER -> WaypointCodec.encode(groups, safeOpts);
            case SKYBLOCKER -> WaypointImporter.SKYBLOCKER_V1_PREFIX
                    + Base64.getEncoder().encodeToString(gzip(skyblockerJson(groups, safeOpts)));
            case SKYTILS -> Base64.getEncoder().encodeToString(skytilsJson(groups, safeOpts)
                    .getBytes(StandardCharsets.UTF_8));
            case SKYHANNI -> skyhanniJson(groups);
        };
    }

    public static String previewLabel(Target target) {
        return switch (target) {
            case WAYPOINTER -> "Export Preview (Waypointer export code)";
            case SKYBLOCKER -> "Export Preview (Skyblocker share string)";
            case SKYTILS -> "Export Preview (Skytils base64 JSON)";
            case SKYHANNI -> "Export Preview (SkyHanni route JSON)";
        };
    }

    private static String skyblockerJson(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        JsonArray root = new JsonArray();
        for (WaypointGroup group : groups) {
            JsonObject out = new JsonObject();
            out.addProperty("name", groupName(group));
            out.addProperty("island", thirdPartyIslandId(group.zoneId()));
            out.addProperty("ordered", group.loadMode() == WaypointGroup.LoadMode.SEQUENCE);

            JsonArray waypoints = new JsonArray();
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                point.add("pos", position(waypoint));
                if (opts.includeNames && waypoint.hasName()) {
                    point.addProperty("name", waypoint.name());
                }
                if (opts.includeColors) {
                    point.add("colorComponents", colorComponents(waypoint.color()));
                }
                waypoints.add(point);
            }
            out.add("waypoints", waypoints);
            root.add(out);
        }
        return root.toString();
    }

    private static String skytilsJson(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        JsonObject root = new JsonObject();
        JsonArray categories = new JsonArray();
        for (WaypointGroup group : groups) {
            JsonObject category = new JsonObject();
            category.addProperty("name", groupName(group));
            category.addProperty("island", thirdPartyIslandId(group.zoneId()));

            JsonArray waypoints = new JsonArray();
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                point.addProperty("x", waypoint.x());
                point.addProperty("y", waypoint.y());
                point.addProperty("z", waypoint.z());
                point.addProperty("enabled", true);
                if (opts.includeNames && waypoint.hasName()) {
                    point.addProperty("name", waypoint.name());
                }
                if (opts.includeColors) {
                    point.addProperty("color", 0x7F000000 | (waypoint.color() & 0xFFFFFF));
                }
                waypoints.add(point);
            }
            category.add("waypoints", waypoints);
            categories.add(category);
        }
        root.add("categories", categories);
        return root.toString();
    }

    private static String skyhanniJson(List<WaypointGroup> groups) {
        JsonObject root = new JsonObject();
        JsonArray waypoints = new JsonArray();
        int step = 1;
        for (WaypointGroup group : groups) {
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                point.addProperty("x", waypoint.x());
                point.addProperty("y", waypoint.y());
                point.addProperty("z", waypoint.z());

                point.addProperty("r", 0.0);
                point.addProperty("g", 1.0);
                point.addProperty("b", 0.0);

                JsonObject options = new JsonObject();
                options.addProperty("name", Integer.toString(step));
                point.add("options", options);

                waypoints.add(point);
                step++;
            }
        }
        root.add("waypoints", waypoints);
        return root.toString();
    }

    private static String groupName(WaypointGroup group) {
        if (!group.name().isBlank()) return group.name();
        Zone zone = Zone.fromId(group.zoneId());
        return zone.displayName();
    }

    private static String thirdPartyIslandId(String zoneId) {
        String mapped = SKYBLOCKER_ISLAND_IDS.get(zoneId);
        return mapped == null ? zoneId : mapped;
    }

    private static JsonArray position(Waypoint waypoint) {
        JsonArray pos = new JsonArray();
        pos.add(waypoint.x());
        pos.add(waypoint.y());
        pos.add(waypoint.z());
        return pos;
    }

    private static JsonArray colorComponents(int rgb) {
        JsonArray color = new JsonArray();
        color.add(normalizedChannel(rgb, 16));
        color.add(normalizedChannel(rgb, 8));
        color.add(normalizedChannel(rgb, 0));
        return color;
    }

    private static BigDecimal normalizedChannel(int rgb, int shift) {
        return BigDecimal.valueOf(channel(rgb, shift))
                .divide(BigDecimal.valueOf(255), 3, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static int channel(int rgb, int shift) {
        return (rgb >> shift) & 0xFF;
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("external waypoint export failed", e);
        }
        return out.toByteArray();
    }
}
