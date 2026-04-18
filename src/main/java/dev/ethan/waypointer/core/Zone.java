package dev.ethan.waypointer.core;

import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * A Skyblock map/mode, keyed for waypoint grouping.
 *
 * Zones are intentionally coarse: e.g. all Catacombs F7 runs share one zone,
 * regardless of which instance the server assigned us. That's what makes "waypoints
 * across Skyblock servers" possible -- we key storage by zone id, not by instance.
 *
 * Resolution is best-effort: known Hypixel (map, mode) combos get curated display names;
 * anything unrecognised falls through to a sanitised map id. Non-Skyblock server types
 * return {@link #UNKNOWN}.
 */
public record Zone(String id, String displayName) {

    public static final Zone UNKNOWN = new Zone("unknown", "Unknown");

    /** Predicate over (map, mode) so we can match dungeon floors by mode but hubs by map. */
    private record Def(String id, String displayName, BiPredicate<String, String> matches) {}

    private static BiPredicate<String, String> mapEq(String m) {
        return (map, mode) -> m.equalsIgnoreCase(map);
    }

    private static BiPredicate<String, String> dungeonMode(String mode) {
        return (map, mapMode) -> "dungeon".equalsIgnoreCase(map) && mode.equalsIgnoreCase(mapMode);
    }

    private static final List<Def> KNOWN = List.of(
            new Def("hub",              "Hub",                   mapEq("Hub")),
            new Def("private_island",   "Private Island",        mapEq("Private Island")),
            new Def("dungeon_hub",      "Dungeon Hub",           mapEq("Dungeon Hub")),
            new Def("the_end",          "The End",               mapEq("The End")),
            new Def("the_park",         "The Park",              mapEq("The Park")),
            new Def("spiders_den",      "Spider's Den",          mapEq("Spider's Den")),
            new Def("gold_mine",        "Gold Mine",             mapEq("Gold Mine")),
            new Def("deep_caverns",     "Deep Caverns",          mapEq("Deep Caverns")),
            new Def("dwarven_mines",    "Dwarven Mines",         mapEq("Dwarven Mines")),
            new Def("crystal_hollows",  "Crystal Hollows",       mapEq("Crystal Hollows")),
            new Def("mineshaft",        "Mineshaft",             mapEq("Mineshaft")),
            new Def("the_farming_isles","The Farming Islands",   mapEq("The Farming Islands")),
            new Def("garden",           "Garden",                mapEq("Garden")),
            new Def("kuudra",           "Kuudra",                mapEq("Kuudra")),
            new Def("rift",             "The Rift",              mapEq("The Rift")),
            new Def("dungeon_f1", "Catacombs F1", dungeonMode("F1")),
            new Def("dungeon_f2", "Catacombs F2", dungeonMode("F2")),
            new Def("dungeon_f3", "Catacombs F3", dungeonMode("F3")),
            new Def("dungeon_f4", "Catacombs F4", dungeonMode("F4")),
            new Def("dungeon_f5", "Catacombs F5", dungeonMode("F5")),
            new Def("dungeon_f6", "Catacombs F6", dungeonMode("F6")),
            new Def("dungeon_f7", "Catacombs F7", dungeonMode("F7")),
            new Def("dungeon_m1", "Master Mode M1", dungeonMode("M1")),
            new Def("dungeon_m2", "Master Mode M2", dungeonMode("M2")),
            new Def("dungeon_m3", "Master Mode M3", dungeonMode("M3")),
            new Def("dungeon_m4", "Master Mode M4", dungeonMode("M4")),
            new Def("dungeon_m5", "Master Mode M5", dungeonMode("M5")),
            new Def("dungeon_m6", "Master Mode M6", dungeonMode("M6")),
            new Def("dungeon_m7", "Master Mode M7", dungeonMode("M7"))
    );

    /**
     * Resolve a Zone from Hypixel Mod API (serverType, map, mode) triple.
     * Returns null when the player isn't on Skyblock -- callers use that to hide everything.
     */
    public static Zone resolve(String serverType, String map, String mode) {
        if (serverType == null || !"SKYBLOCK".equalsIgnoreCase(serverType)) return null;
        for (Def def : KNOWN) {
            if (def.matches.test(map, mode)) return new Zone(def.id, def.displayName);
        }
        if (map == null || map.isBlank()) return UNKNOWN;
        return new Zone(sanitizeId(map + (mode != null && !mode.isBlank() ? "_" + mode : "")), map);
    }

    /**
     * Look up a known zone by id. If the id was produced by a third-party mod and we
     * don't have a canonical display name, we prettify the id so the UI still reads
     * cleanly rather than showing {@code dwarven_mines} verbatim.
     */
    public static Zone fromId(String id) {
        if (id == null || id.isBlank()) return UNKNOWN;
        for (Def def : KNOWN) {
            if (def.id.equals(id)) return new Zone(def.id, def.displayName);
        }
        return new Zone(id, prettify(id));
    }

    private static String prettify(String id) {
        String[] parts = id.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Fallback used by {@code ScoreboardZoneResolver} when the Hypixel Mod API isn't
     * available. Matches on the human-readable map name only (mode can't be recovered
     * from the sidebar cleanly).
     */
    public static Zone resolveFromDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return null;
        String cleaned = displayName.trim();
        for (Def def : KNOWN) {
            if (def.displayName.equalsIgnoreCase(cleaned)) return new Zone(def.id, def.displayName);
        }
        return new Zone(sanitizeId(cleaned), cleaned);
    }

    private static String sanitizeId(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
