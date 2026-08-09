package com.babbur.waypointer.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A reusable SkyBlock area for grouping routes. Resolution accepts either a
 * location ID or display name in either Hypixel packet field. Non-SkyBlock
 * server types resolve to {@code null} so waypoints stay hidden.
 */
public record Zone(String id, String displayName) {

    private static final String DWARVEN_MINES_ZONE_ID = "dwarven_mines";
    private static final String DWARVEN_MINES_DISPLAY_NAME = "Dwarven Mines";
    public static final String MINESHAFT_CRYSTAL_ZONE_ID = "mineshaft_crystal";
    private static final String MINESHAFT_CRYSTAL_DISPLAY_NAME = "Mineshaft: Crystal";

    public static final Zone UNKNOWN = new Zone("unknown", "Unknown");
    public static final Zone PRIVATE_WORLD = new Zone("private_world", "Private World");

    public Zone {
        String rawId = id == null || id.isBlank() ? "unknown" : id;
        String canonicalId = canonicalId(rawId);
        boolean collapsedLegacyCrystal = !canonicalId.equals(rawId)
                && MINESHAFT_CRYSTAL_ZONE_ID.equals(canonicalId);
        boolean collapsedLegacyDwarvenSurface = isLegacyDwarvenSurfaceZoneId(
                rawId.trim().toLowerCase(Locale.ROOT));
        id = canonicalId;
        if (collapsedLegacyCrystal) {
            displayName = MINESHAFT_CRYSTAL_DISPLAY_NAME;
        } else if (collapsedLegacyDwarvenSurface) {
            displayName = DWARVEN_MINES_DISPLAY_NAME;
        } else if (displayName == null || displayName.isBlank()) {
            displayName = prettify(canonicalId);
        }
    }

    private record Def(String id, String displayName,
                       BiPredicate<String, String> matches,
                       Predicate<String> displayMatches) {
        Def(String id, String displayName, BiPredicate<String, String> matches) {
            this(id, displayName, matches, name -> displayName.equalsIgnoreCase(name));
        }
    }

    private static BiPredicate<String, String> tokens(String... accepted) {
        return (map, mode) -> {
            for (String tok : accepted) {
                if (equalsIC(map, tok) || equalsIC(mode, tok)) return true;
            }
            return false;
        };
    }

    private static BiPredicate<String, String> prefixTokens(String... accepted) {
        return (map, mode) -> {
            for (String tok : accepted) {
                if (startsWithIC(map, tok) || startsWithIC(mode, tok)) return true;
            }
            return false;
        };
    }

    // Dungeon packets split "dungeon" and the floor between the two fields.
    private static BiPredicate<String, String> dungeonFloor(String floor) {
        return (map, mode) -> {
            boolean isDungeon = equalsIC(map, "dungeon") || equalsIC(mode, "dungeon");
            boolean hasFloor  = equalsIC(map, floor)     || equalsIC(mode, floor);
            return isDungeon && hasFloor;
        };
    }

    private static boolean genericDungeon(String map, String mode) {
        return equalsIC(map, "dungeon") && equalsIC(mode, "dungeon");
    }

    private static Predicate<String> anyDisplay(String... names) {
        return name -> {
            if (name == null) return false;
            for (String n : names) if (n.equalsIgnoreCase(name)) return true;
            return false;
        };
    }

    private static Predicate<String> displayStartsWithAny(String... prefixes) {
        return name -> {
            for (String p : prefixes) if (startsWithIC(name, p)) return true;
            return false;
        };
    }

        private static boolean neverMatchesPacket(String map, String mode) {
        return false;
    }

    private static boolean equalsIC(String value, String token) {
        return value != null && value.equalsIgnoreCase(token);
    }

    private static boolean startsWithIC(String value, String prefix) {
        if (value == null || prefix == null) return false;
        if (value.length() < prefix.length()) return false;
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static final List<Def> KNOWN = List.of(
            new Def("hub",               "Hub",                  tokens("hub", "Hub")),
            new Def("private_island",    "Private Island",       tokens("dynamic", "Private Island"),
                    anyDisplay("Private Island", "Your Island")),
            new Def("dungeon_hub",       "Dungeon Hub",          tokens("dungeon_hub", "Dungeon Hub")),

            new Def("the_park",          "The Park",             tokens("foraging_1", "The Park")),
            new Def("the_farming_isles", "The Farming Islands",  tokens("farming_1", "The Farming Islands",
                                                                        "The Farming Island"),
                    anyDisplay("The Farming Islands", "The Farming Island")),

            new Def("spiders_den",       "Spider's Den",         tokens("combat_1", "Spider's Den")),
            new Def("the_end",           "The End",              tokens("combat_3", "The End")),
            new Def("crimson_isle",      "Crimson Isle",         tokens("crimson_isle", "Crimson Isle")),
            new Def("kuudra",            "Kuudra's Hollow",      tokens("kuudra", "Kuudra's Hollow", "Kuudra"),
                    anyDisplay("Kuudra's Hollow", "Kuudra")),

            new Def("gold_mine",         "Gold Mine",            tokens("mining_1", "Gold Mine")),
            new Def("deep_caverns",      "Deep Caverns",         tokens("mining_2", "Deep Caverns")),

            // Hypixel reports these connected areas as one mining_3 zone.
            new Def(DWARVEN_MINES_ZONE_ID, DWARVEN_MINES_DISPLAY_NAME,
                    tokens("mining_3", DWARVEN_MINES_DISPLAY_NAME,
                            "Great Glacite Lake", "Glacite Tunnels", "Dwarven Base Camp"),
                    anyDisplay(DWARVEN_MINES_DISPLAY_NAME,
                            "Great Glacite Lake", "Glacite Tunnels", "Dwarven Base Camp")),
            new Def("crystal_hollows",   "Crystal Hollows",      tokens("crystal_hollows", "Crystal Hollows")),
            // Use a safe fallback when the scoreboard does not identify the layout.
            // Generic mineshaft routes must not activate across unrelated layouts.
            new Def("mineshaft_unknown", "Unknown Mineshaft",
                    (map, mode) -> false,
                    anyDisplay("Glacite Mineshafts", "Mineshaft", "Unknown Mineshaft")),
            // Legacy packet value used only as the starting point for refinement.
            new Def("mineshaft",         "Glacite Mineshafts",
                    tokens("mineshaft", "Glacite Mineshafts", "Mineshaft"),
                    name -> false),
            new Def(MINESHAFT_CRYSTAL_ZONE_ID, MINESHAFT_CRYSTAL_DISPLAY_NAME,
                    Zone::neverMatchesPacket,
                    anyDisplay(MINESHAFT_CRYSTAL_DISPLAY_NAME, "Crystal Mineshaft")),

            new Def("backwater_bayou",   "Backwater Bayou",      tokens("fishing_1", "Backwater Bayou")),
            new Def("lotus_atoll",       "Lotus Atoll",          tokens("lotus_atoll", "Lotus Atoll")),

            new Def("garden",            "Garden",               tokens("garden", "Garden", "The Garden"),
                    anyDisplay("Garden", "The Garden")),
            new Def("rift",              "The Rift",             tokens("rift", "The Rift")),
            new Def("winter",            "Jerry's Workshop",     tokens("winter", "Jerry's Workshop",
                                                                        "Winter Island"),
                    anyDisplay("Jerry's Workshop", "Winter Island")),
            new Def("dark_auction",      "Dark Auction",         tokens("dark_auction", "Dark Auction")),

            new Def("galatea",           "Galatea",
                    prefixTokens("foraging_2", "Galatea"),
                    displayStartsWithAny("Galatea")),
            new Def("torrhus_canyon",    "Torrhus Canyon",
                    tokens("foraging_3", "Torrhus Canyon"),
                    anyDisplay("Torrhus Canyon")),
            new Def("safari",            "Safari",
                    tokens("safari", "Safari", "Safari Zone"),
                    anyDisplay("Safari", "Safari Zone")),

            new Def("dungeon", "Catacombs", Zone::genericDungeon,
                    anyDisplay("Catacombs", "Dungeon")),
            new Def("dungeon_f1", "Catacombs F1",     dungeonFloor("F1")),
            new Def("dungeon_f2", "Catacombs F2",     dungeonFloor("F2")),
            new Def("dungeon_f3", "Catacombs F3",     dungeonFloor("F3")),
            new Def("dungeon_f4", "Catacombs F4",     dungeonFloor("F4")),
            new Def("dungeon_f5", "Catacombs F5",     dungeonFloor("F5")),
            new Def("dungeon_f6", "Catacombs F6",     dungeonFloor("F6")),
            new Def("dungeon_f7", "Catacombs F7",     dungeonFloor("F7")),
            new Def("dungeon_m1", "Master Mode M1",   dungeonFloor("M1")),
            new Def("dungeon_m2", "Master Mode M2",   dungeonFloor("M2")),
            new Def("dungeon_m3", "Master Mode M3",   dungeonFloor("M3")),
            new Def("dungeon_m4", "Master Mode M4",   dungeonFloor("M4")),
            new Def("dungeon_m5", "Master Mode M5",   dungeonFloor("M5")),
            new Def("dungeon_m6", "Master Mode M6",   dungeonFloor("M6")),
            new Def("dungeon_m7", "Master Mode M7",   dungeonFloor("M7"))
    );

    // The packet only says "mineshaft". The scoreboard's unique short code
    // identifies the actual layout.
    private record MineshaftType(String idSuffix, String code, String rawName) {}

    private static final List<MineshaftType> MINESHAFT_TYPES = List.of(
            new MineshaftType("topaz_1",        "TOPA_1", "Topaz 1"),
            new MineshaftType("topaz_2",        "TOPA_2", "Topaz 2"),
            new MineshaftType("sapphire_1",     "SAPP_1", "Sapphire 1"),
            new MineshaftType("sapphire_2",     "SAPP_2", "Sapphire 2"),
            new MineshaftType("amethyst_1",     "AMET_1", "Amethyst 1"),
            new MineshaftType("amethyst_2",     "AMET_2", "Amethyst 2"),
            new MineshaftType("amber_1",        "AMBE_1", "Amber 1"),
            new MineshaftType("amber_2",        "AMBE_2", "Amber 2"),
            new MineshaftType("jade_1",         "JADE_1", "Jade 1"),
            new MineshaftType("jade_2",         "JADE_2", "Jade 2"),
            new MineshaftType("ruby_1",         "RUBY_1", "Ruby 1"),
            new MineshaftType("ruby_2",         "RUBY_2", "Ruby 2"),
            new MineshaftType("ruby_crystal",       "RUBY_C", "Ruby Crystal"),
            new MineshaftType("onyx_1",         "ONYX_1", "Onyx 1"),
            new MineshaftType("onyx_2",         "ONYX_2", "Onyx 2"),
            new MineshaftType("onyx_crystal",       "ONYX_C", "Onyx Crystal"),
            new MineshaftType("aquamarine_1",   "AQUA_1", "Aquamarine 1"),
            new MineshaftType("aquamarine_2",   "AQUA_2", "Aquamarine 2"),
            new MineshaftType("aquamarine_crystal", "AQUA_C", "Aquamarine Crystal"),
            new MineshaftType("citrine_1",      "CITR_1", "Citrine 1"),
            new MineshaftType("citrine_2",      "CITR_2", "Citrine 2"),
            new MineshaftType("citrine_crystal",    "CITR_C", "Citrine Crystal"),
            new MineshaftType("peridot_1",      "PERI_1", "Peridot 1"),
            new MineshaftType("peridot_2",      "PERI_2", "Peridot 2"),
            new MineshaftType("peridot_crystal",    "PERI_C", "Peridot Crystal"),
            new MineshaftType("jasper",         "JASP_1", "Jasper"),
            new MineshaftType("jasper_crystal",     "JASP_C", "Jasper Crystal"),
            new MineshaftType("opal",           "OPAL_1", "Opal"),
            new MineshaftType("opal_crystal",       "OPAL_C", "Opal Crystal"),

            new MineshaftType("titanium",       "TITA_1", "Titanium"),
            new MineshaftType("umber",          "UMBE_1", "Umber"),
            new MineshaftType("tungsten",       "TUNG_1", "Tungsten"),
            // Hypixel uses FAIR_1 for Vanguard.
            new MineshaftType("vanguard",       "FAIR_1", "Vanguard"),
            new MineshaftType("littlefoots_den", "LITT_L", "Littlefoot's Den")
    );

        private static MineshaftType mineshaftTypeById(String id) {
        if (id == null || !id.startsWith("mineshaft_")) return null;
        if (MINESHAFT_CRYSTAL_ZONE_ID.equals(canonicalId(id))) return null;
        for (MineshaftType t : MINESHAFT_TYPES) {
            if (rawMineshaftId(t).equals(id)) return t;
        }
        return null;
    }

        private static String rawMineshaftId(MineshaftType t) {
        return "mineshaft_" + t.idSuffix();
    }

        private static String canonicalMineshaftId(MineshaftType t) {
        return isCrystalMineshaftType(t) ? MINESHAFT_CRYSTAL_ZONE_ID : rawMineshaftId(t);
    }

        private static String canonicalMineshaftDisplayName(MineshaftType t) {
        return isCrystalMineshaftType(t) ? MINESHAFT_CRYSTAL_DISPLAY_NAME : "Mineshaft: " + t.rawName();
    }

    private static boolean isCrystalMineshaftType(MineshaftType t) {
        return t.idSuffix().endsWith("_crystal");
    }

    // Do not expose the legacy broad mineshaft bucket as a route target.
    private static final List<Zone> KNOWN_ZONES = buildKnownZones();

    private static List<Zone> buildKnownZones() {
        Map<String, Zone> zones = new LinkedHashMap<>();
        for (Def def : KNOWN) {
            if (!"mineshaft".equals(def.id)) {
                zones.put(def.id, new Zone(def.id, def.displayName));
            }
        }
        for (MineshaftType type : MINESHAFT_TYPES) {
            Zone zone = new Zone(canonicalMineshaftId(type), canonicalMineshaftDisplayName(type));
            zones.putIfAbsent(zone.id(), zone);
        }
        return List.copyOf(zones.values());
    }

    /** Resolves a Hypixel location packet, or returns {@code null} outside SkyBlock. */
    public static Zone resolve(String serverType, String map, String mode) {
        if (serverType == null || !"SKYBLOCK".equalsIgnoreCase(serverType)) return null;
        for (Def def : KNOWN) {
            if (def.matches.test(map, mode)) return new Zone(def.id, def.displayName);
        }
        // Prefer the friendly map label when no known zone matches.
        String display = (map != null && !map.isBlank()) ? map
                       : (mode != null && !mode.isBlank()) ? mode
                       : null;
        if (display == null) return UNKNOWN;
        String rawId = display + (mode != null && !mode.isBlank() && !display.equals(mode)
                ? "_" + mode : "");
        return new Zone(sanitizeId(rawId), display);
    }

    public static List<Zone> knownZones() {
        return KNOWN_ZONES;
    }

        public static Zone fromId(String id) {
        if (id == null || id.isBlank()) return UNKNOWN;
        String canonical = canonicalId(id);
        if (PRIVATE_WORLD.id().equals(canonical)) return PRIVATE_WORLD;
        if (MINESHAFT_CRYSTAL_ZONE_ID.equals(canonical)) {
            return new Zone(MINESHAFT_CRYSTAL_ZONE_ID, MINESHAFT_CRYSTAL_DISPLAY_NAME);
        }
        for (Def def : KNOWN) {
            if (def.id.equals(canonical)) return new Zone(def.id, def.displayName);
        }
        MineshaftType mt = mineshaftTypeById(canonical);
        if (mt != null) return new Zone(canonicalMineshaftId(mt), canonicalMineshaftDisplayName(mt));
        return new Zone(canonical, prettify(canonical));
    }

        public static Zone resolveFromDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return null;
        String cleaned = displayName.trim();
        for (Def def : KNOWN) {
            if (def.displayMatches.test(cleaned)) return new Zone(def.id, def.displayName);
        }
        for (MineshaftType t : MINESHAFT_TYPES) {
            if (cleaned.equalsIgnoreCase(t.rawName())) {
                return new Zone(canonicalMineshaftId(t), canonicalMineshaftDisplayName(t));
            }
        }
        return new Zone(sanitizeId(cleaned), cleaned);
    }

    /** Resolves legacy Dwarven sub-areas while keeping Mineshaft layouts separate. */
    public static Zone tryResolveDwarvenSubAreaFromSidebarBlob(String colorStrippedSidebarText) {
        if (colorStrippedSidebarText == null || colorStrippedSidebarText.isBlank()) return null;
        String b = colorStrippedSidebarText.toLowerCase(Locale.ROOT);
        // Prefer the Mineshaft signal when the sidebar briefly shows both areas.
        if (b.contains("glacite mineshafts")) return fromId("mineshaft_unknown");
        if (b.contains("great glacite lake")
                || b.contains("glacite tunnels")
                || b.contains("dwarven base camp")) {
            return fromId(DWARVEN_MINES_ZONE_ID);
        }
        return null;
    }

    /** Finds the unique layout code Hypixel writes on the Mineshaft sidebar. */
    public static Zone tryResolveMineshaftTypeFromSidebarBlob(String colorStrippedSidebarText) {
        if (colorStrippedSidebarText == null || colorStrippedSidebarText.isBlank()) return null;
        for (MineshaftType t : MINESHAFT_TYPES) {
            // Codes are uppercase; case-sensitive matching avoids usernames.
            if (containsAsToken(colorStrippedSidebarText, t.code())) {
                return new Zone(canonicalMineshaftId(t), canonicalMineshaftDisplayName(t));
            }
        }
        return null;
    }

    /** Rejects a code embedded in a larger alphanumeric token. */
    private static boolean containsAsToken(String haystack, String token) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(token, from);
            if (i < 0) return false;
            char before = i == 0 ? ' ' : haystack.charAt(i - 1);
            int endIdx = i + token.length();
            char after = endIdx >= haystack.length() ? ' ' : haystack.charAt(endIdx);
            if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after)) return true;
            from = i + 1;
        }
    }

    /** Refines only Mineshaft layouts; connected surface areas stay grouped. */
    public static Zone refineIfDwarvenMinesContext(Zone packetZone, String colorStrippedSidebarText) {
        if (packetZone == null) return null;
        String id = packetZone.id();
        if (!"dwarven_mines".equals(id) && !"mineshaft".equals(id)) return packetZone;
        Zone mineshaftType = tryResolveMineshaftTypeFromSidebarBlob(colorStrippedSidebarText);
        if (mineshaftType != null) return mineshaftType;
        Zone sub = tryResolveDwarvenSubAreaFromSidebarBlob(colorStrippedSidebarText);
        if (sub != null) return sub.equals(packetZone) ? packetZone : sub;
        // Unknown prevents a route for the wrong Mineshaft layout from activating.
        if ("mineshaft".equals(id)) return fromId("mineshaft_unknown");
        return packetZone;
    }

    public static String canonicalId(String id) {
        if (id == null || id.isBlank()) return "unknown";
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (isLegacyDwarvenSurfaceZoneId(normalized)) {
            return DWARVEN_MINES_ZONE_ID;
        }
        if (normalized.startsWith("mineshaft_") && normalized.endsWith("_crystal")) {
            return MINESHAFT_CRYSTAL_ZONE_ID;
        }
        if ("foraging_3".equals(normalized)) {
            return "torrhus_canyon";
        }
        return normalized;
    }

    private static boolean isLegacyDwarvenSurfaceZoneId(String normalizedId) {
        return "great_glacite_lake".equals(normalizedId)
                || "glacite_tunnels".equals(normalizedId)
                || "dwarven_base_camp".equals(normalizedId);
    }

    private static String prettify(String id) {
        if (id == null || id.isBlank()) return "";
        String[] rawParts = id.replace('_', ' ').split(" ");
        String[] parts = new String[rawParts.length];
        int count = 0;
        for (String part : rawParts) {
            if (!part.isEmpty()) {
                parts[count] = part;
                count++;
            }
        }

        int displayCount = count;
        if (count > 0 && count % 2 == 0) {
            int half = count / 2;
            boolean duplicated = true;
            for (int i = 0; i < half; i++) {
                if (!parts[i].equalsIgnoreCase(parts[i + half])) {
                    duplicated = false;
                    break;
                }
            }
            if (duplicated) displayCount = half;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < displayCount; i++) {
            String p = parts[i];
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String sanitizeId(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
