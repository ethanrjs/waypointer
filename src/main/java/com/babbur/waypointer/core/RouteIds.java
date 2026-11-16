package com.babbur.waypointer.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route IDs like {@code @mod:1} (route 1 in Mines of Divan) and waypoint IDs like
 * {@code @mod:1#15} or {@code @mod:1#2.1}. Zones and dungeon rooms have short names; any
 * other zone uses its full zone ID. Route numbers are stored locally per zone and are
 * not reused after deletion. Waypoint suffixes follow the editor's current numbering.
 */
public final class RouteIds {

    private static final Pattern REFERENCE = Pattern.compile(
            "@([a-z0-9_-]{1,64}):([0-9]{1,10})(?:#([0-9]{1,10}(?:\\.[0-9]{1,10})?))?");

    private static final Map<String, String> ZONES = linked(
            "hub", "hub",
            "private_island", "pi",
            "dungeon_hub", "dh",
            "the_park", "park",
            "the_farming_isles", "fi",
            "spiders_den", "sd",
            "the_end", "te",
            "crimson_isle", "ci",
            "kuudra", "kuu",
            "gold_mine", "gm",
            "deep_caverns", "dc",
            "dwarven_mines", "dm",
            "crystal_hollows", "ch",
            "mineshaft_unknown", "ms",
            "mineshaft_crystal", "msc",
            "backwater_bayou", "bb",
            "lotus_atoll", "la",
            "garden", "gdn",
            "rift", "rift",
            "winter", "jw",
            "dark_auction", "da",
            "galatea", "gal",
            "torrhus_canyon", "tc",
            "safari", "saf",
            "private_world", "pw",
            "unknown", "unk",
            "dungeon", "cata",
            "dungeon_f1", "f1",
            "dungeon_f2", "f2",
            "dungeon_f3", "f3",
            "dungeon_f4", "f4",
            "dungeon_f5", "f5",
            "dungeon_f6", "f6",
            "dungeon_f7", "f7",
            "dungeon_m1", "m1",
            "dungeon_m2", "m2",
            "dungeon_m3", "m3",
            "dungeon_m4", "m4",
            "dungeon_m5", "m5",
            "dungeon_m6", "m6",
            "dungeon_m7", "m7",
            "crystal_hollows_structure_jungle_temple", "jt",
            "crystal_hollows_structure_mines_of_divan", "mod",
            "crystal_hollows_structure_goblin_queens_den", "gqd",
            "crystal_hollows_structure_lost_precursor_city", "lpc",
            "crystal_hollows_structure_khazad_dum", "kd",
            "crystal_hollows_structure_dragons_lair", "dl",
            "crystal_hollows_structure_king_yolkar", "ky",
            "crystal_hollows_structure_odawa", "oda",
            "crystal_hollows_structure_xalx", "xalx",
            "mineshaft_topaz_1", "mstop1",
            "mineshaft_topaz_2", "mstop2",
            "mineshaft_sapphire_1", "mssap1",
            "mineshaft_sapphire_2", "mssap2",
            "mineshaft_amethyst_1", "msame1",
            "mineshaft_amethyst_2", "msame2",
            "mineshaft_amber_1", "msamb1",
            "mineshaft_amber_2", "msamb2",
            "mineshaft_jade_1", "msjade1",
            "mineshaft_jade_2", "msjade2",
            "mineshaft_ruby_1", "msruby1",
            "mineshaft_ruby_2", "msruby2",
            "mineshaft_onyx_1", "msonyx1",
            "mineshaft_onyx_2", "msonyx2",
            "mineshaft_aquamarine_1", "msaqua1",
            "mineshaft_aquamarine_2", "msaqua2",
            "mineshaft_citrine_1", "mscit1",
            "mineshaft_citrine_2", "mscit2",
            "mineshaft_peridot_1", "msper1",
            "mineshaft_peridot_2", "msper2",
            "mineshaft_jasper", "msjas",
            "mineshaft_opal", "msopal",
            "mineshaft_titanium", "mstit",
            "mineshaft_umber", "msumb",
            "mineshaft_tungsten", "mstung",
            "mineshaft_vanguard", "msvan",
            "mineshaft_littlefoots_den", "mslfd");

    private static final Map<String, String> ROOMS = linked(
            "admin", "adm",
            "altar", "alt",
            "andesite", "and",
            "archway", "arc",
            "arrow-trap", "at",
            "atlas", "atl",
            "balcony", "bal",
            "banners", "ban",
            "basement", "bas",
            "beams", "bea",
            "big-red-flag", "brf",
            "black-flag", "bf",
            "blood", "blo",
            "blue-skulls", "bs",
            "boulder", "bou",
            "bridges", "bri",
            "buttons", "but",
            "cage", "cage",
            "cages", "cgs",
            "carpets", "car",
            "cathedral", "cat",
            "catwalk", "cwk",
            "cell", "cell",
            "chains", "cha",
            "chambers", "cham",
            "cobble-wall-pillar", "cwp",
            "creeper-beams", "cb",
            "criss-cross", "cc",
            "crypt", "cry",
            "deathmite", "dea",
            "default", "def",
            "diagonal", "dia",
            "dino-site", "ds",
            "dip", "dip",
            "dome", "dome",
            "doors", "doo",
            "double-diamond", "dd",
            "dragon", "dra",
            "drop", "drop",
            "dueces", "due",
            "duncan", "dun",
            "end", "end",
            "entrance", "ent",
            "fairy", "fai",
            "flags", "fla",
            "gold", "gold",
            "golden-oasis", "go",
            "grand-library", "gl",
            "granite", "gra",
            "grass-ruin", "gr",
            "gravel", "grav",
            "hall", "hall",
            "hallway", "hwy",
            "higher-blaze", "hb",
            "ice-fill", "if",
            "ice-path", "ip",
            "jumping-skulls", "js",
            "king-midas", "km",
            "knight", "kni",
            "lava-pit", "lp",
            "lava-ravine", "lr",
            "layers", "lay",
            "leaves", "lea",
            "locked-away", "law",
            "logs", "logs",
            "long-hall", "lh",
            "lots-of-floors", "lof",
            "lower-blaze", "lb",
            "mage", "mage",
            "market", "mar",
            "melon", "mel",
            "mines", "min",
            "mirror", "mir",
            "mossy", "mos",
            "multicolored", "mul",
            "mural", "mur",
            "museum", "mus",
            "mushroom", "mush",
            "new-trap", "nt",
            "old-trap", "ot",
            "overgrown", "ove",
            "overgrown-chains", "oc",
            "painting", "pai",
            "pedestal", "ped",
            "perch", "per",
            "pipes", "pip",
            "pirate", "pir",
            "pit", "pit",
            "pressure-plates", "pp",
            "prison-cell", "pc",
            "purple-flags", "pf",
            "quad-lava", "ql",
            "quartz-knight", "qk",
            "quiz", "quiz",
            "raccoon", "rac",
            "rail-track", "rt",
            "rails", "rai",
            "rare-carpets", "rc",
            "rare-overgrown", "ro",
            "rare-pillars", "rp",
            "red-blue", "rb",
            "red-green", "rg",
            "redstone-crypt", "rcr",
            "redstone-key", "rk",
            "redstone-warrior", "rw",
            "ritual", "rit",
            "sand-dragon", "sdr",
            "sarcophagus", "sar",
            "scaffolding", "sca",
            "shadow-assassin", "sa",
            "silver-sword", "ss",
            "skull", "sku",
            "slabs", "sla",
            "slime", "sli",
            "sloth", "slo",
            "small-stairs", "sst",
            "small-waterfall", "sw",
            "spider", "spi",
            "spikes", "spk",
            "staircase", "stc",
            "stairs", "str",
            "steps", "ste",
            "stone-window", "swi",
            "supertall", "sup",
            "teleport-maze", "tm",
            "temple", "tem",
            "three-floors", "tf",
            "three-weirdos", "tw",
            "tic-tac-toe", "ttt",
            "tombstone", "tom",
            "tomioka", "tomi",
            "trinity", "tri",
            "vinny-8-ball", "v8b",
            "water", "wat",
            "water-board", "wb",
            "waterfall", "wf",
            "well", "well",
            "withermancer", "wit",
            "wizard", "wiz",
            "zodd", "zodd");

    private static final Map<String, String> SHORT_NAMES = merged(ZONES, ROOMS);
    private static final Map<String, String> ZONE_BY_SHORT_NAME = reversed(SHORT_NAMES);

    private RouteIds() {}

    public record Reference(String zoneId, int routeNumber, String waypointLabel) {
        public boolean hasWaypoint() {
            return waypointLabel != null;
        }
    }

    /** The short name for a zone or dungeon room, or the zone ID itself when it has none. */
    public static String shortName(String zoneId) {
        String canonical = Zone.canonicalId(zoneId);
        return SHORT_NAMES.getOrDefault(canonical, canonical);
    }

    /** The zone ID a short name stands for; a full zone ID also works. */
    public static String zoneId(String shortName) {
        String key = shortName == null ? "" : shortName.trim().toLowerCase(Locale.ROOT);
        String zone = ZONE_BY_SHORT_NAME.get(key);
        return zone != null ? zone : Zone.canonicalId(key);
    }

    public static String routeId(WaypointGroup group) {
        if (group == null || group.temp() || group.runtimeOnly() || !group.routeNumberMatchesZone()) return null;
        return "@" + shortName(group.zoneId()) + ":" + group.routeNumber();
    }

    public static String waypointId(WaypointGroup group, int index) {
        String route = routeId(group);
        if (route == null || index < 0 || index >= group.size()) return null;
        String label = group.displayIndexLabel(index);
        return route + (label.startsWith("#") ? label : "#" + label);
    }

    public static boolean looksLikeReference(String text) {
        return text != null && text.trim().startsWith("@");
    }

    /** Parses {@code @zone:N} or {@code @zone:N#W}; null when the text is not one. */
    public static Reference parse(String text) {
        if (text == null) return null;
        Matcher matcher = REFERENCE.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        int number;
        try {
            number = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException outsideRange) {
            return null;
        }
        if (number <= 0) return null;
        return new Reference(zoneId(matcher.group(1)), number, matcher.group(3));
    }

    /** The saved route a reference names. A master floor also finds its normal floor's routes. */
    public static WaypointGroup find(Collection<WaypointGroup> groups, Reference reference) {
        if (groups == null || reference == null) return null;
        WaypointGroup exact = find(groups, reference.zoneId(), reference.routeNumber());
        if (exact != null) return exact;
        String shared = Zone.routeZoneKey(reference.zoneId());
        return shared.equals(reference.zoneId()) ? null : find(groups, shared, reference.routeNumber());
    }

    private static WaypointGroup find(Collection<WaypointGroup> groups, String zoneId, int number) {
        for (WaypointGroup group : groups) {
            if (!group.temp() && !group.runtimeOnly() && group.routeNumber() == number
                    && group.routeNumberMatchesZone() && zoneId.equals(group.zoneId())) {
                return group;
            }
        }
        return null;
    }

    static Map<String, String> shortNames() {
        return SHORT_NAMES;
    }

    static Map<String, String> roomShortNames() {
        return ROOMS;
    }

    private static Map<String, String> linked(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
        Map<String, String> out = new LinkedHashMap<>(first);
        out.putAll(second);
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> reversed(Map<String, String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        names.forEach((zone, name) -> {
            if (out.putIfAbsent(name, zone) != null) {
                throw new IllegalStateException("Duplicate route ID short name " + name);
            }
        });
        return Collections.unmodifiableMap(out);
    }
}
