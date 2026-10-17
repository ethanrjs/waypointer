package com.babbur.waypointer.core;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A route's icon: any Minecraft item, drawn as it looks in the inventory, optionally with the
 * enchantment shimmer. Stored as the item id with a trailing {@code *} for the shimmer, e.g.
 * {@code minecraft:diamond_pickaxe*}. An empty string means no icon was chosen; the zone's
 * default is shown instead.
 */
public final class RouteIcon {
    public static final String NONE = "";
    public static final int MAX_LENGTH = 96;

    private static final Pattern SPEC = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,62}\\*?");

    /** A picture for every zone family, so a row without a chosen icon still says where it is. */
    private static final Map<String, String> ZONE_DEFAULTS = Map.ofEntries(
            Map.entry("hub", "minecraft:oak_sapling"),
            Map.entry("private_island", "minecraft:grass_block"),
            Map.entry("dungeon_hub", "minecraft:wither_skeleton_skull"),
            Map.entry("dungeon", "minecraft:wither_skeleton_skull"),
            Map.entry("the_park", "minecraft:birch_log"),
            Map.entry("the_farming_isles", "minecraft:wheat"),
            Map.entry("garden", "minecraft:sunflower"),
            Map.entry("spiders_den", "minecraft:cobweb"),
            Map.entry("the_end", "minecraft:ender_eye"),
            Map.entry("crimson_isle", "minecraft:blaze_powder"),
            Map.entry("kuudra", "minecraft:magma_cream"),
            Map.entry("gold_mine", "minecraft:gold_ore"),
            Map.entry("deep_caverns", "minecraft:redstone_ore"),
            Map.entry("dwarven_mines", "minecraft:iron_pickaxe"),
            Map.entry("mineshaft", "minecraft:packed_ice"),
            Map.entry("crystal_hollows", "minecraft:amethyst_shard"),
            Map.entry("backwater_bayou", "minecraft:fishing_rod"),
            Map.entry("lotus_atoll", "minecraft:lily_pad"),
            Map.entry("rift", "minecraft:purple_dye"),
            Map.entry("winter", "minecraft:snowball"),
            Map.entry("dark_auction", "minecraft:gold_nugget"),
            Map.entry("galatea", "minecraft:mangrove_propagule"),
            Map.entry("torrhus_canyon", "minecraft:dark_oak_sapling"),
            Map.entry("safari", "minecraft:lead"));
    private static final String FALLBACK = "minecraft:compass";

    private RouteIcon() {}

    /**
     * The canonical form of a typed or stored icon: lower case, {@code minecraft:} added when no
     * namespace is given, or {@link #NONE} when it isn't a valid item id.
     */
    public static String normalize(String raw) {
        if (raw == null) return NONE;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("none")) return NONE;
        if (value.indexOf(':') < 0) value = "minecraft:" + value;
        if (value.length() > MAX_LENGTH || !SPEC.matcher(value).matches()) return NONE;
        return value;
    }

    public static String of(String itemId, boolean glint) {
        String id = normalize(itemId(itemId));
        return id.isEmpty() ? NONE : id + (glint ? "*" : "");
    }

    /** The item id without the shimmer mark. */
    public static String itemId(String spec) {
        if (spec == null) return NONE;
        return spec.endsWith("*") ? spec.substring(0, spec.length() - 1) : spec;
    }

    public static boolean glint(String spec) {
        return spec != null && spec.endsWith("*");
    }

    /** The icon to draw for a route: its own, else its zone's. */
    public static String display(String spec, String zoneId) {
        String own = normalize(spec);
        return own.isEmpty() ? zoneDefault(zoneId) : own;
    }

    public static String zoneDefault(String zoneId) {
        String id = Zone.canonicalId(zoneId);
        String found = ZONE_DEFAULTS.get(id);
        if (found != null) return found;
        if (id.startsWith("dungeon_")) return ZONE_DEFAULTS.get("dungeon");
        if (id.startsWith("mineshaft_")) return ZONE_DEFAULTS.get("mineshaft");
        if (id.startsWith("crystal_hollows")) return ZONE_DEFAULTS.get("crystal_hollows");
        return FALLBACK;
    }
}
