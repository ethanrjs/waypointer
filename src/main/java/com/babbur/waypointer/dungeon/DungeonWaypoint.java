package com.babbur.waypointer.dungeon;

import java.util.List;
import java.util.Objects;

/**
 * An immutable room-local dungeon waypoint. {@code customColor} is
 * {@code 0xRRGGBB}, or {@link #INHERIT_COLOR} to use the category color.
 */
public record DungeonWaypoint(
        String id,
        int secretIndex,
        DungeonSecretCategory category,
        DungeonWaypointTrigger trigger,
        int x, int y, int z,
        String name,
        List<DungeonHighlight> highlights,
        int customColor) {

    public static final int INHERIT_COLOR = DungeonHighlight.INHERIT_COLOR;

    public DungeonWaypoint {
        Objects.requireNonNull(id, "id");
        if (category == null) category = DungeonSecretCategory.DEFAULT;
        if (trigger == null) trigger = defaultTrigger(category);
        name = name == null ? "" : name;
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }

    public DungeonWaypoint(String id, int secretIndex, DungeonSecretCategory category,
                           DungeonWaypointTrigger trigger,
                           int x, int y, int z, String name,
                           List<DungeonHighlight> highlights) {
        this(id, secretIndex, category, trigger, x, y, z, name, highlights, INHERIT_COLOR);
    }

    public DungeonWaypoint(String id, int secretIndex, DungeonSecretCategory category,
                           int x, int y, int z, String name,
                           List<DungeonHighlight> highlights) {
        this(id, secretIndex, category, defaultTrigger(category), x, y, z, name, highlights,
                INHERIT_COLOR);
    }

    public static DungeonWaypoint plain(String id, DungeonSecretCategory cat,
                                        int x, int y, int z, String name) {
        return new DungeonWaypoint(id, 1, cat, x, y, z, name, List.of());
    }

    public boolean hasHighlights() { return !highlights.isEmpty(); }

    public boolean hasName() { return !name.isEmpty(); }

    public boolean hasOwnColor() { return customColor != INHERIT_COLOR; }

    public int color() { return hasOwnColor() ? customColor : category.defaultColor; }

    public DungeonWaypoint withTrigger(DungeonWaypointTrigger next) {
        return new DungeonWaypoint(id, secretIndex, category, next, x, y, z, name, highlights,
                customColor);
    }

    public DungeonWaypoint withPosition(int nx, int ny, int nz) {
        return new DungeonWaypoint(id, secretIndex, category, trigger, nx, ny, nz, name, highlights,
                customColor);
    }

    public DungeonWaypoint withHighlights(List<DungeonHighlight> next) {
        return new DungeonWaypoint(id, secretIndex, category, trigger, x, y, z, name, next,
                customColor);
    }

    public DungeonWaypoint withCustomColor(int nextColor) {
        return new DungeonWaypoint(id, secretIndex, category, trigger, x, y, z, name, highlights,
                nextColor);
    }

    /** True when this action finishes a secret rather than moving toward it. */
    public boolean completesSecret() {
        return switch (trigger) {
            case PICKUP_ITEM, KILL_BAT, OPEN_CHEST, ANY_SECRET, CHAT_MESSAGE -> true;
            default -> false;
        };
    }

    public static DungeonWaypointTrigger defaultTrigger(DungeonSecretCategory category) {
        if (category == null) return DungeonWaypointTrigger.MANUAL;
        return switch (category) {
            case CHEST, WITHER, REDSTONE_KEY -> DungeonWaypointTrigger.OPEN_CHEST;
            case LEVER -> DungeonWaypointTrigger.FLIP_LEVER;
            case ITEM -> DungeonWaypointTrigger.PICKUP_ITEM;
            case BAT -> DungeonWaypointTrigger.KILL_BAT;
            case SUPERBOOM -> DungeonWaypointTrigger.USE_SUPERBOOM;
            case STONK, DUNGEONBREAKER -> DungeonWaypointTrigger.BREAK_BLOCKS;
            case ETHERWARP -> DungeonWaypointTrigger.ETHERWARP;
            case PEARL -> DungeonWaypointTrigger.THROW_PEARL;
            default -> DungeonWaypointTrigger.MANUAL;
        };
    }
}
