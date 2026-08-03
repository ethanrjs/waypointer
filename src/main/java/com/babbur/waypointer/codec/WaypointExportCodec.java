package com.babbur.waypointer.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        WAYPOINTER("Waypointer", true, true, true, true, true, true, true),
        SKYBLOCKER("Skyblocker", true, true, false, false, false, false, false),
        SKYTILS("Skytils", true, true, false, false, false, false, false),
        SKYHANNI("SkyHanni", false, false, false, false, false, false, false);

        private final String displayName;
        private final boolean supportsNames;
        private final boolean supportsColors;
        private final boolean supportsRadii;
        private final boolean supportsWaypointFlags;
        private final boolean supportsGroupMeta;
        private final boolean supportsLabel;
        private final boolean supportsIslandChoice;

        Target(String displayName, boolean supportsNames, boolean supportsColors,
               boolean supportsRadii, boolean supportsWaypointFlags,
               boolean supportsGroupMeta, boolean supportsLabel,
               boolean supportsIslandChoice) {
            this.displayName = displayName;
            this.supportsNames = supportsNames;
            this.supportsColors = supportsColors;
            this.supportsRadii = supportsRadii;
            this.supportsWaypointFlags = supportsWaypointFlags;
            this.supportsGroupMeta = supportsGroupMeta;
            this.supportsLabel = supportsLabel;
            this.supportsIslandChoice = supportsIslandChoice;
        }

        public String displayName()          { return displayName; }

        public boolean supportsNames()       { return supportsNames; }

        public boolean supportsColors()      { return supportsColors; }

        public boolean supportsRadii()       { return supportsRadii; }

        public boolean supportsWaypointFlags() { return supportsWaypointFlags; }

        public boolean supportsGroupMeta()   { return supportsGroupMeta; }

        public boolean supportsLabel()       { return supportsLabel; }

        /**
         * Whether the sender may drop the island a route was recorded on.
         *
         * Only the native format can: Skyblocker and Skytils both require an
         * island field their client recognizes ("unknown" is not in Skytils'
         * island enum at all), and SkyHanni's route JSON has no island field to
         * begin with, so there is nothing for the sender to decide.
         */
        public boolean supportsIslandChoice() { return supportsIslandChoice; }

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
                    // Inverted on purpose: the unsupported thing here is *dropping*
                    // the island, so a target without the choice always keeps it.
                    .includeZone(!supportsIslandChoice || opts.includeZone)
                    .label(supportsLabel ? opts.label : "")
                    .build();
        }
    }

    private static final Map<String, String> SKYBLOCKER_ISLAND_IDS = Map.ofEntries(
            Map.entry("private_island", "dynamic"),
            Map.entry("the_farming_isles", "farming_1"),
            Map.entry("the_park", "foraging_1"),
            Map.entry("galatea", "foraging_2"),
            Map.entry("torrhus_canyon", "foraging_3"),
            Map.entry("safari", "safari"),
            Map.entry("spiders_den", "combat_1"),
            Map.entry("the_end", "combat_3"),
            Map.entry("gold_mine", "mining_1"),
            Map.entry("deep_caverns", "mining_2"),
            Map.entry("dwarven_mines", "mining_3"),
            Map.entry("backwater_bayou", "fishing_1"),
            Map.entry("lotus_atoll", "lotus_atoll"),
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

    private static final Set<String> SKYBLOCKER_RECIPIENT_ISLAND_IDS = Set.of(
            "dynamic", "garden", "hub", "farming_1", "foraging_1", "foraging_2", "foraging_3",
            "combat_1", "combat_2", "combat_3", "crimson_isle", "mining_1",
            "mining_2", "mining_3", "fishing_1", "dungeon_hub", "winter", "rift",
            "dark_auction", "crystal_hollows", "dungeon", "kuudra", "mineshaft",
            "lotus_atoll", "safari", "unknown");

    /** Skytils 1.x {@code SkyblockIsland.mode} values at the supported upstream revision. */
    private static final Set<String> SKYTILS_RECIPIENT_ISLAND_IDS = Set.of(
            "dynamic", "garden", "combat_1", "crimson_isle", "combat_3", "fishing_1",
            "mining_1", "mining_2", "mining_3", "crystal_hollows", "farming_1",
            "foraging_1", "dungeon", "dungeon_hub", "hub", "dark_auction", "winter",
            "kuudra", "mineshaft", "rift");

    private WaypointExportCodec() {}

    public static String encode(List<WaypointGroup> groups, WaypointCodec.Options opts, Target target) {
        WaypointCodec.Options safeOpts = target.coerce(opts);
        return switch (target) {
            case WAYPOINTER -> WaypointCodec.encode(groups, safeOpts);
            case SKYBLOCKER -> WaypointImporter.SKYBLOCKER_V1_PREFIX
                    + Base64.getEncoder().encodeToString(gzip(skyblockerJson(groups, safeOpts)));
            case SKYTILS -> WaypointImporter.SKYTILS_V1_PREFIX
                    + Base64.getEncoder().encodeToString(gzip(skytilsJson(groups, safeOpts)));
            case SKYHANNI -> skyhanniJson(groups);
        };
    }

    private static String skyblockerJson(List<WaypointGroup> groups, WaypointCodec.Options opts) {
        JsonArray root = new JsonArray();
        for (WaypointGroup group : groups) {
            JsonObject out = new JsonObject();
            out.addProperty("name", groupName(group));
            out.addProperty("island", skyblockerIslandId(group.zoneId()));
            out.addProperty("ordered", group.loadMode() == WaypointGroup.LoadMode.SEQUENCE);
            out.addProperty("renderThroughWalls", !group.isEmpty() && group.waypoints().stream()
                    .allMatch(waypoint -> waypoint.hasFlag(Waypoint.FLAG_THROUGH_WALL)));

            JsonArray waypoints = new JsonArray();
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                point.add("pos", position(waypoint));
                point.addProperty("name", opts.includeNames ? waypoint.name() : "");
                point.add("colorComponents", colorComponents(
                        opts.includeColors ? waypoint.color() : Waypoint.DEFAULT_COLOR));
                point.addProperty("alpha", 0.5f);
                point.addProperty("shouldRender", !waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON)
                        || !waypoint.hasFlag(Waypoint.FLAG_HIDE_NAME));
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
        long exportedAt = System.currentTimeMillis();
        for (WaypointGroup group : groups) {
            JsonObject category = new JsonObject();
            category.addProperty("name", groupName(group));
            category.addProperty("island", skytilsIslandId(group.zoneId()));

            JsonArray waypoints = new JsonArray();
            for (Waypoint waypoint : group.waypoints()) {
                JsonObject point = new JsonObject();
                point.addProperty("x", waypoint.x());
                point.addProperty("y", waypoint.y());
                point.addProperty("z", waypoint.z());
                point.addProperty("name", opts.includeNames && waypoint.hasName()
                        ? waypoint.name() : "Unnamed");
                point.addProperty("enabled", group.enabled()
                        && (!waypoint.hasFlag(Waypoint.FLAG_HIDE_BEACON)
                        || !waypoint.hasFlag(Waypoint.FLAG_HIDE_NAME)));
                if (opts.includeColors) {
                    point.addProperty("color", 0xFF000000 | (waypoint.color() & 0xFFFFFF));
                }
                point.addProperty("addedAt", exportedAt);
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

    static String skyblockerIslandId(String zoneId) {
        String recipientId = coarseThirdPartyIslandId(zoneId);
        if (!SKYBLOCKER_RECIPIENT_ISLAND_IDS.contains(recipientId)) {
            throw unsupportedZone("Skyblocker", zoneId);
        }
        return recipientId;
    }

    static String skytilsIslandId(String zoneId) {
        String recipientId = coarseThirdPartyIslandId(zoneId);
        if (!SKYTILS_RECIPIENT_ISLAND_IDS.contains(recipientId)) {
            throw unsupportedZone("Skytils", zoneId);
        }
        return recipientId;
    }

    private static IllegalArgumentException unsupportedZone(String target, String zoneId) {
        return new IllegalArgumentException(target + " does not recognize the "
                + Zone.fromId(zoneId).displayName() + " zone; use Waypointer export instead");
    }

    private static String coarseThirdPartyIslandId(String zoneId) {
        String canonicalZoneId = Zone.canonicalId(zoneId);
        String mapped = SKYBLOCKER_ISLAND_IDS.get(canonicalZoneId);
        if (mapped != null) return mapped;
        if (canonicalZoneId.startsWith("mineshaft_")) return "mineshaft";
        if (isCatacombsFloor(canonicalZoneId)
                || DungeonRoomData.definition(canonicalZoneId) != null) {
            return "dungeon";
        }
        return canonicalZoneId;
    }

    private static boolean isCatacombsFloor(String zoneId) {
        if (zoneId.length() != "dungeon_f1".length() || !zoneId.startsWith("dungeon_")) {
            return false;
        }
        char mode = zoneId.charAt("dungeon_".length());
        char floor = zoneId.charAt(zoneId.length() - 1);
        return (mode == 'f' || mode == 'm') && floor >= '1' && floor <= '7';
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
