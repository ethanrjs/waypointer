package com.babbur.waypointer.dungeon;

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
    CHAT_MESSAGE,
    /** Completes when the player etherwarps onto (or next to) the waypoint. */
    ETHERWARP,
    /** Completes when the player launches an Ender Pearl from this waypoint. */
    THROW_PEARL,
    /**
     * Completes on the first observed secret action near the waypoint --
     * interact, item pickup, or bat kill. The right default for imported data
     * (Odin packs, SecretRoutes) that doesn't record which kind a secret is.
     */
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
