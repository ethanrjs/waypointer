package dev.ethan.waypointer.dungeon;

import java.util.List;
import java.util.Objects;

/**
 * A single dungeon secret waypoint, expressed in room-local coordinates so
 * the same data can be re-rendered correctly regardless of how Hypixel
 * rotates the room within its map cell.
 *
 * <p>This is the parent half of the {@code waypoint -> highlights} one-to-many
 * relationship requested in issue #9. The waypoint itself draws a labelled
 * outline at its target position; its {@link #highlights} provide additional
 * colored cubes (door blocks, lever positions, item-pickup hints, etc.) that
 * point at the specific real-world blocks the player needs to interact with.
 *
 * <p>Immutable by design -- mirrors the immutability contract of
 * {@link dev.ethan.waypointer.core.Waypoint}.
 */
public record DungeonWaypoint(
        String id,
        int secretIndex,
        DungeonSecretCategory category,
        DungeonWaypointTrigger trigger,
        int x, int y, int z,
        String name,
        List<DungeonHighlight> highlights) {

    public DungeonWaypoint {
        Objects.requireNonNull(id, "id");
        if (category == null) category = DungeonSecretCategory.DEFAULT;
        if (trigger == null) trigger = defaultTrigger(category);
        name = name == null ? "" : name;
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }

    public DungeonWaypoint(String id, int secretIndex, DungeonSecretCategory category,
                           int x, int y, int z, String name,
                           List<DungeonHighlight> highlights) {
        this(id, secretIndex, category, defaultTrigger(category), x, y, z, name, highlights);
    }

    /** Builder helper for waypoints that have no highlights. */
    public static DungeonWaypoint plain(String id, DungeonSecretCategory cat,
                                        int x, int y, int z, String name) {
        return new DungeonWaypoint(id, 1, cat, x, y, z, name, List.of());
    }

    public boolean hasHighlights() { return !highlights.isEmpty(); }

    public boolean hasName() { return !name.isEmpty(); }

    public int color() { return category.defaultColor; }

    public DungeonWaypoint withTrigger(DungeonWaypointTrigger next) {
        return new DungeonWaypoint(id, secretIndex, category, next, x, y, z, name, highlights);
    }

    public DungeonWaypoint withPosition(int nx, int ny, int nz) {
        return new DungeonWaypoint(id, secretIndex, category, trigger, nx, ny, nz, name, highlights);
    }

    public DungeonWaypoint withHighlights(List<DungeonHighlight> next) {
        return new DungeonWaypoint(id, secretIndex, category, trigger, x, y, z, name, next);
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
            default -> DungeonWaypointTrigger.MANUAL;
        };
    }
}
