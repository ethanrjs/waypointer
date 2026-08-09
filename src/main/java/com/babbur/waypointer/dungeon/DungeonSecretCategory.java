package com.babbur.waypointer.dungeon;

import java.util.Locale;

/** Secret type, wire ID, and fallback display color. */
public enum DungeonSecretCategory {
    ENTRANCE     ("entrance",  0x2EFF2E),
    SUPERBOOM    ("superboom", 0xFFB300),
    CHEST        ("chest",     0x2EE0FF),
    ITEM         ("item",      0xFFD800),
    BAT          ("bat",       0x9C5A2E),
    WITHER       ("wither",    0xFFFFFF),
    REDSTONE_KEY ("key",       0xFF2E2E),
    LEVER        ("lever",     0xFF8A2E),
    FAIRYSOUL    ("fairysoul", 0xFF61F2),
    STONK        ("stonk",     0x4FE05A),
    DUNGEONBREAKER("dungeonbreaker", 0x6EE7B7),
    ETHERWARP     ("etherwarp", 0x9C2EFF),
    AOTV         ("aotv",      0x9C2EFF),
    PEARL        ("pearl",     0xC0C0FF),
    PRINCE       ("prince",    0xFFC0CB),
    DEFAULT      ("default",   0x4FE05A);

    public final String id;
    public final int defaultColor;

    DungeonSecretCategory(String id, int defaultColor) {
        this.id = id;
        this.defaultColor = defaultColor;
    }

    /** Unknown IDs use {@link #DEFAULT} so imported routes still render. */
    public static DungeonSecretCategory fromId(String id) {
        if (id == null || id.isBlank()) return DEFAULT;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (DungeonSecretCategory c : values()) {
            if (c.id.equals(norm)) return c;
        }
        return DEFAULT;
    }
}
