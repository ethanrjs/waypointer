package dev.ethan.waypointer.core;

import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A Skyblock map/mode, keyed for waypoint grouping.
 *
 * Zones are intentionally coarse: e.g. all Catacombs F7 runs share one zone,
 * regardless of which instance the server assigned us. That's what makes "waypoints
 * across Skyblock servers" possible -- we key storage by zone id, not by instance.
 *
 * <p>Resolution pathways:
 * <ul>
 *   <li>{@link #resolve(String, String, String)} -- authoritative: Hypixel Mod API
 *       location packet fields {@code serverType}, {@code map}, {@code mode}.</li>
 *   <li>{@link #resolveFromDisplayName(String)} -- fallback when the mod-API isn't
 *       available, pulling the island name out of the sidebar.</li>
 *   <li>{@link #fromId(String)} -- replay on-disk group ids back to a {@code Zone}
 *       with the current canonical display name.</li>
 * </ul>
 *
 * <p>Matching is deliberately permissive across {@code map} and {@code mode}:
 * Hypixel's mod-API documentation doesn't pin which field carries what, and field
 * ordering has varied across islands in practice (the {@code mode} field typically
 * carries the Skyblocker-style island id like {@code "foraging_1"}, but some
 * payloads instead put the friendly name in {@code map} and vice versa). For each
 * zone we therefore accept <em>either</em> the internal id <em>or</em> the friendly
 * name in <em>either</em> field.
 *
 * <p>Non-Skyblock server types return {@code null}; callers (e.g.
 * {@code ActiveGroupManager}) use that to hide all waypoints.
 */
public record Zone(String id, String displayName) {

    public static final Zone UNKNOWN = new Zone("unknown", "Unknown");

    /**
     * A canonical Waypointer zone plus the matchers that recognise it.
     *
     * <p>{@code matches} is invoked with the raw ({@code map}, {@code mode}) pair
     * from the Hypixel Mod API. {@code displayMatches} handles the scoreboard
     * fallback where only one human-readable string is visible.
     */
    private record Def(String id, String displayName,
                       BiPredicate<String, String> matches,
                       Predicate<String> displayMatches) {
        Def(String id, String displayName, BiPredicate<String, String> matches) {
            this(id, displayName, matches, name -> displayName.equalsIgnoreCase(name));
        }
    }

    // ---- matchers --------------------------------------------------------

    /**
     * Accept the zone if any of {@code accepted} matches either the {@code map}
     * or {@code mode} field (case-insensitive exact match).
     *
     * <p>Typical use: passing the Skyblocker-style island id
     * ({@code "foraging_1"}) <em>and</em> the friendly name ({@code "The Park"})
     * so the resolver doesn't care which field Hypixel populated.
     */
    private static BiPredicate<String, String> tokens(String... accepted) {
        return (map, mode) -> {
            for (String tok : accepted) {
                if (equalsIC(map, tok) || equalsIC(mode, tok)) return true;
            }
            return false;
        };
    }

    /**
     * Prefix variant of {@link #tokens}. Used for islands whose Hypixel payload
     * or scoreboard line varies with a suffix (e.g. Galatea's scoreboard reads
     * {@code "Galatea Foraging 2"} rather than the bare {@code "Galatea"}).
     */
    private static BiPredicate<String, String> prefixTokens(String... accepted) {
        return (map, mode) -> {
            for (String tok : accepted) {
                if (startsWithIC(map, tok) || startsWithIC(mode, tok)) return true;
            }
            return false;
        };
    }

    /**
     * Dungeons are the one zone family where both fields must be inspected
     * together: Hypixel sends {@code mode="dungeon"} plus the floor id
     * (e.g. {@code "F7"}) in {@code map}. We additionally accept the swapped
     * order defensively, since the packet's field semantics aren't formally
     * documented and have shifted between data versions.
     */
    private static BiPredicate<String, String> dungeonFloor(String floor) {
        return (map, mode) -> {
            boolean isDungeon = equalsIC(map, "dungeon") || equalsIC(mode, "dungeon");
            boolean hasFloor  = equalsIC(map, floor)     || equalsIC(mode, floor);
            return isDungeon && hasFloor;
        };
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

    private static boolean equalsIC(String value, String token) {
        return value != null && value.equalsIgnoreCase(token);
    }

    private static boolean startsWithIC(String value, String prefix) {
        return value != null && value.toLowerCase(Locale.ROOT)
                .startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    // ---- known zones -----------------------------------------------------

    /*
     * Source of truth: Skyblocker's {@code Location} enum (which in turn
     * mirrors Hypixel's LocationUpdateS2CPacket location ids). Each Def
     * carries both the Skyblocker id (e.g. "foraging_1") and the friendly
     * name (e.g. "The Park"); the `tokens` matcher accepts either in either
     * mod-API field.
     *
     * Blazing Fortress (combat_2) is intentionally omitted -- the 1.8.9 island
     * was removed years ago and only survives in legacy exports that should
     * retarget to Crimson Isle manually.
     */
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

            // Dwarven sub-areas (Skyblocker {@code Area.DwarvenMines}). The Hypixel
            // location packet often stays {@code mining_3} for all of these; the
            // sidebar shows the specific glacite sub-area. See
            // {@link #tryResolveDwarvenSubAreaFromSidebarBlob} and client-side
            // refinement in {@code HypixelApiZoneSource}.
            new Def("great_glacite_lake", "Great Glacite Lake",  tokens("Great Glacite Lake"),
                    anyDisplay("Great Glacite Lake")),
            new Def("glacite_tunnels",    "Glacite Tunnels",     tokens("Glacite Tunnels"),
                    anyDisplay("Glacite Tunnels")),
            new Def("dwarven_base_camp",  "Dwarven Base Camp",   tokens("Dwarven Base Camp"),
                    anyDisplay("Dwarven Base Camp")),
            new Def("dwarven_mines",     "Dwarven Mines",        tokens("mining_3", "Dwarven Mines")),
            new Def("crystal_hollows",   "Crystal Hollows",      tokens("crystal_hollows", "Crystal Hollows")),
            // "Unknown Mineshaft" -- runtime zone emitted when we know the player
            // is in Glacite Mineshafts but the sidebar didn't identify a variant
            // (Topaz 1, Jasper Crystal, ...). Every mineshaft type has a distinct
            // layout, so a waypoint group scoped to the generic `mineshaft`
            // bucket would activate in ~33 unrelated layouts -- almost always
            // the wrong answer. Users who genuinely want "this group triggers
            // for any unidentified shaft" target this zone explicitly.
            //
            // Claims the "Glacite Mineshafts" / "Mineshaft" display names so
            // scoreboard fallback + scoreboard-blob resolution + legacy
            // Skyblocker imports all converge here; the generic `mineshaft`
            // Def below still accepts mod-API tokens (because Hypixel's packet
            // uses the bare "mineshaft" mode) but gets upgraded by
            // refineIfDwarvenMinesContext before anything user-facing sees it.
            new Def("mineshaft_unknown", "Unknown Mineshaft",
                    (map, mode) -> false,
                    anyDisplay("Glacite Mineshafts", "Mineshaft", "Unknown Mineshaft")),
            // Legacy mod-API anchor. Skyblocker's own enum notes the packet
            // sometimes keeps reporting this location (the player stays on
            // mining_3), so we still accept it to seed refinement. `fromId`
            // resolves the id for legacy stored groups but the runtime pipeline
            // never emits this zone -- every live path upgrades to either a
            // specific variant or `mineshaft_unknown`.
            new Def("mineshaft",         "Glacite Mineshafts",
                    tokens("mineshaft", "Glacite Mineshafts", "Mineshaft"),
                    name -> false),

            new Def("backwater_bayou",   "Backwater Bayou",      tokens("fishing_1", "Backwater Bayou")),

            new Def("garden",            "Garden",               tokens("garden", "Garden", "The Garden"),
                    anyDisplay("Garden", "The Garden")),
            new Def("rift",              "The Rift",             tokens("rift", "The Rift")),
            new Def("winter",            "Jerry's Workshop",     tokens("winter", "Jerry's Workshop",
                                                                        "Winter Island"),
                    anyDisplay("Jerry's Workshop", "Winter Island")),
            new Def("dark_auction",      "Dark Auction",         tokens("dark_auction", "Dark Auction")),

            // Galatea: scoreboard reads "Galatea Foraging 2" while Skyblocker
            // imports use the bare id. Prefix-match across both the Hypixel
            // mod-API fields AND the scoreboard so every signal funnels into
            // the one canonical zone id users actually type.
            new Def("galatea",           "Galatea",
                    prefixTokens("foraging_2", "Galatea"),
                    displayStartsWithAny("Galatea")),

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

    // ---- mineshaft sub-types ---------------------------------------------

    /*
     * Glacite Mineshaft has ~33 gemstone variants (Topaz 1, Ruby Crystal, Jasper,
     * Littlefoot's Den, ...) that Hypixel's mod-API collapses into the single
     * {@code mineshaft} zone. The variant is surfaced in the sidebar as a
     * SHORT CODE appended to the server-identifier line, e.g.
     * {@code "04/18/26 m197CD AQUA_C"} while inside an Aquamarine Crystal
     * shaft. We match on those codes for detection -- the full display names
     * ("Aquamarine Crystal") never appear in the live scoreboard text.
     *
     * <p>Token list mirrors the {@code MineshaftType.name()} values used by
     * SkyHanni's MineshaftDetection module (5.0.0) for the same reason:
     * those ARE the strings Hypixel writes to the scoreboard.
     *
     * <p>Codes are globally unique across variants, so {@link
     * #tryResolveMineshaftTypeFromSidebarBlob} doesn't need any ordering
     * gymnastics (unlike when we were matching on human display names, where
     * "Jasper" used to collide with "Jasper Crystal"). The only collision to
     * guard against is substring-inside-a-larger-word, handled by
     * {@link #containsAsToken}.
     */
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
            // SkyHanni's enum constant for Vanguard is FAIR_1 -- matching the
            // code Hypixel actually writes rather than the display name.
            new MineshaftType("vanguard",       "FAIR_1", "Vanguard"),
            new MineshaftType("littlefoots_den", "LITT_L", "Littlefoot's Den")
    );

    /**
     * Lookup from canonical id to the mineshaft-type metadata, used so
     * {@link #fromId(String)} renders imported mineshaft zones with a nice
     * display name without polluting the main {@link #KNOWN} table.
     */
    private static MineshaftType mineshaftTypeById(String id) {
        if (id == null || !id.startsWith("mineshaft_")) return null;
        for (MineshaftType t : MINESHAFT_TYPES) {
            if (canonicalMineshaftId(t).equals(id)) return t;
        }
        return null;
    }

    private static String canonicalMineshaftId(MineshaftType t) {
        return "mineshaft_" + t.idSuffix();
    }

    private static String canonicalMineshaftDisplayName(MineshaftType t) {
        return "Mineshaft: " + t.rawName();
    }

    // ---- resolve ---------------------------------------------------------

    /**
     * Resolve a Zone from a Hypixel Mod API (serverType, map, mode) triple.
     * Returns null when the player isn't on Skyblock -- callers use that to
     * hide every waypoint (see {@code ActiveGroupManager.activeGroups}).
     */
    public static Zone resolve(String serverType, String map, String mode) {
        if (serverType == null || !"SKYBLOCK".equalsIgnoreCase(serverType)) return null;
        for (Def def : KNOWN) {
            if (def.matches.test(map, mode)) return new Zone(def.id, def.displayName);
        }
        // Fallback: prefer the friendly map label for display if present,
        // otherwise use the mode id so we at least produce a stable zone key.
        String display = (map != null && !map.isBlank()) ? map
                       : (mode != null && !mode.isBlank()) ? mode
                       : null;
        if (display == null) return UNKNOWN;
        String rawId = display + (mode != null && !mode.isBlank() && !display.equals(mode)
                ? "_" + mode : "");
        return new Zone(sanitizeId(rawId), display);
    }

    /**
     * Look up a known zone by id. Unknown ids get prettified so the UI still
     * reads cleanly even when a third-party mod imported a never-seen zone.
     */
    public static Zone fromId(String id) {
        if (id == null || id.isBlank()) return UNKNOWN;
        for (Def def : KNOWN) {
            if (def.id.equals(id)) return new Zone(def.id, def.displayName);
        }
        MineshaftType mt = mineshaftTypeById(id);
        if (mt != null) return new Zone(canonicalMineshaftId(mt), canonicalMineshaftDisplayName(mt));
        return new Zone(id, prettify(id));
    }

    /**
     * Fallback used by {@code ScoreboardZoneResolver} when the Hypixel Mod API
     * isn't available. Matches on the human-readable map name only (mode can't
     * be recovered from the sidebar cleanly).
     */
    public static Zone resolveFromDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return null;
        String cleaned = displayName.trim();
        for (Def def : KNOWN) {
            if (def.displayMatches.test(cleaned)) return new Zone(def.id, def.displayName);
        }
        // Also allow the bare mineshaft-type name (e.g. "Topaz 1", "Jasper Crystal")
        // to resolve -- handy when the scoreboard's ⏣ line carries the variant
        // directly and users type it into /waypointer group commands.
        for (MineshaftType t : MINESHAFT_TYPES) {
            if (cleaned.equalsIgnoreCase(t.rawName())) {
                return new Zone(canonicalMineshaftId(t), canonicalMineshaftDisplayName(t));
            }
        }
        return new Zone(sanitizeId(cleaned), cleaned);
    }

    /**
     * Detects glacite sub-areas of Dwarven Mines from the full sidebar text.
     * Hypixel's location packet frequently reports {@code mining_3} for every
     * sub-area; Skyblocker instead reads {@code Area.DwarvenMines}-style names
     * from the sidebar. We substring-match the blob (all lines, case-folded)
     * with most-specific phrases first so "Great Glacite Lake" wins over any
     * shorter shared prefix.
     *
     * @return a known glacite sub-zone, or null if the blob doesn't name one
     */
    public static Zone tryResolveDwarvenSubAreaFromSidebarBlob(String colorStrippedSidebarText) {
        if (colorStrippedSidebarText == null || colorStrippedSidebarText.isBlank()) return null;
        String b = colorStrippedSidebarText.toLowerCase(Locale.ROOT);
        if (b.contains("great glacite lake")) return fromId("great_glacite_lake");
        if (b.contains("glacite tunnels")) return fromId("glacite_tunnels");
        // "Glacite Mineshafts" alone (no variant identified) means we know the
        // island but not the type. Emit the Unknown zone -- see comment on the
        // mineshaft_unknown Def for why generic mineshaft waypoints are bad UX.
        if (b.contains("glacite mineshafts")) return fromId("mineshaft_unknown");
        if (b.contains("dwarven base camp")) return fromId("dwarven_base_camp");
        return null;
    }

    /**
     * Scan the sidebar for a Glacite Mineshaft variant code. Hypixel writes
     * a compact identifier on the server-info line (e.g.
     * {@code "04/18/26 m197CD AQUA_C"} inside an Aquamarine Crystal shaft);
     * that code -- not the human-readable "Aquamarine Crystal" name -- is
     * the only variant signal in the live scoreboard. Matches SkyHanni's
     * MineshaftDetection strategy of scanning sidebar lines for the enum
     * constant name.
     *
     * <p>Codes are unique across variants, so no ordering is required.
     * {@link #containsAsToken} still guards against a code falsely matching
     * inside a larger alphanumeric token.
     *
     * @return a mineshaft-type zone, or null if no variant code is found
     */
    public static Zone tryResolveMineshaftTypeFromSidebarBlob(String colorStrippedSidebarText) {
        if (colorStrippedSidebarText == null || colorStrippedSidebarText.isBlank()) return null;
        for (MineshaftType t : MINESHAFT_TYPES) {
            // Case-sensitive: codes are all-caps in the scoreboard and making
            // the match case-insensitive would let stray lowercase substrings
            // like "tung_1" inside a username sneak through.
            if (containsAsToken(colorStrippedSidebarText, t.code())) {
                return new Zone(canonicalMineshaftId(t), canonicalMineshaftDisplayName(t));
            }
        }
        return null;
    }

    /**
     * Substring check that refuses matches wedged inside a larger alphanumeric
     * run, so a username like {@code "xTOPA_1x"} can't satisfy the
     * {@code TOPA_1} mineshaft probe. Underscore is treated as an interior
     * character of the token itself (codes always contain one) and as a
     * boundary character otherwise -- both halves fall out of
     * {@link Character#isLetterOrDigit} naturally.
     */
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

    /**
     * When the mod API reports the broad Dwarven bucket ({@code mining_3} →
     * {@code dwarven_mines} or {@code mineshaft}), refine using the live sidebar
     * so routes keyed to tunnels / lake / base camp / specific mineshaft
     * variants activate in the right place. If the sidebar doesn't mention a
     * glacite sub-area, {@code packetZone} is returned unchanged.
     *
     * <p>Mineshaft-variant refinement runs before the generic glacite sub-area
     * check because "Topaz 1" is strictly more specific than "Glacite
     * Mineshafts" -- both may appear in the same sidebar when entering a
     * variant, and the variant is the one users keyed their waypoints to.
     */
    public static Zone refineIfDwarvenMinesContext(Zone packetZone, String colorStrippedSidebarText) {
        if (packetZone == null) return null;
        String id = packetZone.id();
        if (!"dwarven_mines".equals(id) && !"mineshaft".equals(id)) return packetZone;
        Zone mineshaftType = tryResolveMineshaftTypeFromSidebarBlob(colorStrippedSidebarText);
        if (mineshaftType != null) return mineshaftType;
        Zone sub = tryResolveDwarvenSubAreaFromSidebarBlob(colorStrippedSidebarText);
        if (sub != null) return sub;
        // Packet said mineshaft but the sidebar didn't confirm the variant --
        // upgrade to the Unknown Mineshaft zone so variant-scoped waypoint
        // groups cleanly don't activate. A dwarven_mines packet with no
        // sub-area identified stays as-is (player is in the central hub).
        if ("mineshaft".equals(id)) return fromId("mineshaft_unknown");
        return packetZone;
    }

    // ---- helpers ---------------------------------------------------------

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

    private static String sanitizeId(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
