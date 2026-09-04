package com.babbur.waypointer.crystal;

import com.babbur.waypointer.crystal.compass.WishingCompassTarget;
import java.util.List;

/** Structures and points of interest detected in a Crystal Hollows lobby. */
public enum CrystalHollowsStructure {
    JUNGLE_TEMPLE("jungle_temple", "Jungle Temple", 0x9B59B6, Kind.MAIN_STRUCTURE, false,
            "Jungle Temple", List.of("[NPC] Kalhuiki Door Guardian:"),
            List.of("jungle temple", "temple", "jt"), WishingCompassTarget.JUNGLE_TEMPLE),
    MINES_OF_DIVAN("mines_of_divan", "Mines of Divan", 0x2ECC71, Kind.MAIN_STRUCTURE, false,
            "Mines of Divan", List.of("[NPC] Keeper of Diamond:", "[NPC] Keeper of Lapis:",
                    "[NPC] Keeper of Emerald:", "[NPC] Keeper of Gold:"),
            List.of("mines of divan", "divan", "mod"), WishingCompassTarget.MINES_OF_DIVAN),
    GOBLIN_QUEENS_DEN("goblin_queens_den", "Goblin Queen's Den", 0xE67E22,
            Kind.MAIN_STRUCTURE, false, "Goblin Queen's Den", List.of(),
            List.of("goblin queen's den", "goblin queens den", "queen", "gqd", "den"),
            WishingCompassTarget.GOBLIN_QUEEN),
    LOST_PRECURSOR_CITY("lost_precursor_city", "Lost Precursor City", 0x00BFFF,
            Kind.MAIN_STRUCTURE, false, "Lost Precursor City", List.of("[NPC] Professor Robot:"),
            List.of("lost precursor city", "precursor city", "city", "lpc"),
            WishingCompassTarget.PRECURSOR_CITY),
    KHAZAD_DUM("khazad_dum", "Khazad-dûm", 0xF1C40F, Kind.MAIN_STRUCTURE, false,
            "Khazad-dûm", List.of(), List.of("khazad-dum", "khazad dum", "khazad-dm", "khazad", "bal", "kd"),
            WishingCompassTarget.BAL),
    FAIRY_GROTTO("fairy_grotto", "Fairy Grotto", 0xFF69B4, Kind.MAIN_STRUCTURE, true,
            "Fairy Grotto", List.of(), List.of("fairy grotto", "grotto", "fairy"), null),
    DRAGONS_LAIR("dragons_lair", "Dragon's Lair", 0xFFAA00, Kind.MAIN_STRUCTURE, false,
            "Dragon's Lair", List.of("[NPC] Golden Dragon:"),
            List.of("dragon's lair", "dragons lair", "dragon", "lair"), null),
    CORLEONE("corleone", "Boss Corleone", 0xFFFFFF, Kind.POI, true, null, List.of(),
            List.of("boss corleone", "corleone", "boss"), null),
    KING_YOLKAR("king_yolkar", "King Yolkar", 0xE74C3C, Kind.POI, false, null,
            List.of("[NPC] King Yolkar:"), List.of("king yolkar", "yolkar", "king"),
            WishingCompassTarget.GOBLIN_KING),
    ODAWA("odawa", "Odawa", 0xFF00FF, Kind.POI, false, null,
            List.of("[NPC] Odawa:"), List.of("odawa", "village"), WishingCompassTarget.ODAWA),
    KEY_GUARDIAN("key_guardian", "Key Guardian", 0xBDC3C7, Kind.POI, true, null, List.of(),
            List.of("key guardian", "guardian"), null),
    XALX("xalx", "Xalx", 0x1ABC9C, Kind.POI, false, null,
            List.of("[NPC] Xalx:"), List.of("xalx"), null),
    CRYSTAL_NUCLEUS("crystal_nucleus", "Crystal Nucleus", 0x55FFFF, Kind.FIXED, false,
            null, List.of(), List.of("crystal nucleus", "nucleus"), WishingCompassTarget.CRYSTAL_NUCLEUS),
    WISHING_TARGET("wishing_target", "Wishing Compass Target", 0xF5F5DC, Kind.POI, true,
            null, List.of(), List.of(), null);

    public enum Kind {
        MAIN_STRUCTURE,
        POI,
        FIXED
    }

    private final String id;
    private final String displayName;
    private final int rgb;
    private final Kind kind;
    private final boolean multiInstance;
    private final String sidebarName;
    private final List<String> npcChatPrefixes;
    private final List<String> aliases;
    private final WishingCompassTarget compassTarget;

    CrystalHollowsStructure(String id, String displayName, int rgb, Kind kind, boolean multiInstance,
                            String sidebarName, List<String> npcChatPrefixes, List<String> aliases,
                            WishingCompassTarget compassTarget) {
        this.id = id;
        this.displayName = displayName;
        this.rgb = rgb;
        this.kind = kind;
        this.multiInstance = multiInstance;
        this.sidebarName = sidebarName;
        this.npcChatPrefixes = List.copyOf(npcChatPrefixes);
        this.aliases = List.copyOf(aliases);
        this.compassTarget = compassTarget;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String shareName() { return displayName; }
    public int rgb() { return rgb; }
    public Kind kind() { return kind; }
    public boolean multiInstance() { return multiInstance; }
    public String sidebarName() { return sidebarName; }
    public List<String> npcChatPrefixes() { return npcChatPrefixes; }
    public List<String> aliases() { return aliases; }
    public WishingCompassTarget compassTarget() { return compassTarget; }
}
