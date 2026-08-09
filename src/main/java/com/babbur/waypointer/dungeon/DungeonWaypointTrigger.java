package com.babbur.waypointer.dungeon;

import java.util.Locale;

public enum DungeonWaypointTrigger {
    MANUAL,
    INTERACT_BLOCK,
    OPEN_CHEST,
    FLIP_LEVER,
    USE_SUPERBOOM,
    PICKUP_ITEM,
    KILL_BAT,
    BREAK_BLOCKS,
    DUNGEONBREAKER,
    CHAT_MESSAGE,
    ETHERWARP,
    THROW_PEARL,
    ANY_SECRET;

    public static DungeonWaypointTrigger fromId(String id) {
        if (id == null || id.isBlank()) return MANUAL;
        String norm = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DungeonWaypointTrigger trigger : values()) {
            if (trigger.name().equals(norm)) return trigger;
        }
        return MANUAL;
    }
}
