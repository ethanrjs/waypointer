package dev.ethan.waypointer.dungeon;

import java.util.Locale;

/**
 * Gameplay condition that marks a dungeon secret waypoint as found.
 *
 * <p>Triggers are intentionally data, not subclasses: users can author or
 * change them per waypoint without Waypointer needing a new Java type for each
 * room-specific secret variant.
 */
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
    CHAT_MESSAGE;

    public static DungeonWaypointTrigger fromId(String id) {
        if (id == null || id.isBlank()) return MANUAL;
        String norm = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DungeonWaypointTrigger trigger : values()) {
            if (trigger.name().equals(norm)) return trigger;
        }
        return MANUAL;
    }
}
