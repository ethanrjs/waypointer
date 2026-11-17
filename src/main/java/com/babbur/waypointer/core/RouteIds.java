package com.babbur.waypointer.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route IDs like {@code @fg1} (route 1 in Fairy Grotto) and waypoint IDs like
 * {@code @fg1#15} or {@code @fg1#2.1}. Zones and dungeon rooms have short names; any
 * other zone uses its full zone ID. A zone name ending in a digit takes a colon before the
 * number ({@code @f1:2}), and the colon form is accepted for every zone ({@code @fg:1}).
 * Route numbers are stored locally per zone and are not reused after deletion. Waypoint
 * suffixes follow the editor's current numbering. {@code @1} and {@code @1#15} leave the zone
 * out and mean the zone the player is in.
 */
public final class RouteIds {

    private static final String WAYPOINT_SUFFIX = "(?:#([0-9]{1,10}(?:\\.[0-9]{1,10})?))?";

    /** {@code @fg1}: a zone whose name ends in a letter runs straight into the route number. */
    private static final Pattern COMPACT = Pattern.compile(
            "@([a-z0-9_-]{0,63}[a-z_-])?([0-9]{1,10})" + WAYPOINT_SUFFIX);

    /** {@code @f1:2}: the colon form, needed when the zone name ends in a digit, and always accepted. */
    private static final Pattern COLON = Pattern.compile(
            "@([a-z0-9_-]{1,64}):([0-9]{1,10})" + WAYPOINT_SUFFIX);

    private static final Pattern ZONE_TOKEN = Pattern.compile("[a-z0-9_-]{1,64}");

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
            "crystal_hollows_structure_fairy_grotto", "fg",
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

    /**
     * A parsed ID. {@code zoneId} is null when the ID left the zone out ({@code @1}), and
     * {@code literalZoneId} is the zone exactly as typed, for a zone whose own ID happens to be
     * another zone's short name.
     */
    public record Reference(String zoneId, int routeNumber, String waypointLabel, String literalZoneId) {
        public Reference(String zoneId, int routeNumber, String waypointLabel) {
            this(zoneId, routeNumber, waypointLabel, zoneId);
        }

        public boolean hasWaypoint() {
            return waypointLabel != null;
        }

        /** True for {@code @1}: the zone is whichever one the player is in. */
        public boolean currentZone() {
            return zoneId == null;
        }

        /** The same reference with its zone filled in, for IDs that left it out. */
        public Reference inZone(String currentZoneId) {
            if (zoneId != null || currentZoneId == null) return this;
            String zone = Zone.canonicalId(currentZoneId);
            return new Reference(zone, routeNumber, waypointLabel, zone);
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
        String zone = shortName(group.zoneId());
        // A zone ID that cannot be typed back (spaces, punctuation from an old import) has no ID.
        if (!ZONE_TOKEN.matcher(zone).matches()) return null;
        return routePrefix(zone) + group.routeNumber();
    }

    /**
     * What a zone's route IDs start with: {@code @fg} for Fairy Grotto ({@code @fg1}), or
     * {@code @f1:} for a name ending in a digit, where {@code @f12} would be ambiguous.
     */
    public static String routePrefix(String zoneShortName) {
        String zone = zoneShortName == null ? "" : zoneShortName;
        if (zone.isEmpty()) return "@";
        return Character.isDigit(zone.charAt(zone.length() - 1)) ? "@" + zone + ":" : "@" + zone;
    }

    /** The route ID prefix for a zone ID, as {@link #routePrefix(String)} gives for its short name. */
    public static String routePrefixForZone(String zoneId) {
        return routePrefix(shortName(zoneId));
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

    /**
     * Parses {@code @fg1}, {@code @fg1#5}, {@code @f1:2}, {@code @fg:1}, {@code @1} or
     * {@code @1#5}; null when the text is not one.
     */
    public static Reference parse(String text) {
        if (text == null) return null;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = COLON.matcher(lower);
        if (!matcher.matches()) matcher = COMPACT.matcher(lower);
        if (!matcher.matches()) return null;
        int number;
        try {
            number = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException outsideRange) {
            return null;
        }
        if (number <= 0) return null;
        String zone = matcher.group(1);
        if (zone == null) return new Reference(null, number, matcher.group(3), null);
        return new Reference(zoneId(zone), number, matcher.group(3), Zone.canonicalId(zone));
    }

    /** Why an ID named no route, for an error that says what to fix. */
    public enum Miss { ZONE_HAS_NO_ROUTES, NUMBER_NOT_IN_ZONE, NO_CURRENT_ZONE }

    public static Miss explainMiss(Collection<WaypointGroup> groups, Reference reference) {
        if (reference == null || reference.zoneId() == null) return Miss.NO_CURRENT_ZONE;
        String shared = Zone.routeZoneKey(reference.zoneId());
        for (WaypointGroup group : groups) {
            if (group.temp() || group.runtimeOnly() || !group.routeNumberMatchesZone()) continue;
            String zone = group.zoneId();
            if (zone.equals(reference.zoneId()) || zone.equals(shared) || zone.equals(reference.literalZoneId())) {
                return Miss.NUMBER_NOT_IN_ZONE;
            }
        }
        return Miss.ZONE_HAS_NO_ROUTES;
    }

    /** The saved route a reference names. A master floor also finds its normal floor's routes. */
    public static WaypointGroup find(Collection<WaypointGroup> groups, Reference reference) {
        if (groups == null || reference == null || reference.zoneId() == null) return null;
        WaypointGroup exact = find(groups, reference.zoneId(), reference.routeNumber());
        if (exact != null) return exact;
        String shared = Zone.routeZoneKey(reference.zoneId());
        if (!shared.equals(reference.zoneId())) {
            WaypointGroup floor = find(groups, shared, reference.routeNumber());
            if (floor != null) return floor;
        }
        String literal = reference.literalZoneId();
        return literal == null || literal.equals(reference.zoneId())
                ? null : find(groups, literal, reference.routeNumber());
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
