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
        int x, int y, int z,
        String name,
        List<DungeonHighlight> highlights) {

    public DungeonWaypoint {
        Objects.requireNonNull(id, "id");
        if (category == null) category = DungeonSecretCategory.DEFAULT;
        name = name == null ? "" : name;
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }

    /** Builder helper for waypoints that have no highlights. */
    public static DungeonWaypoint plain(String id, DungeonSecretCategory cat,
                                        int x, int y, int z, String name) {
        return new DungeonWaypoint(id, 0, cat, x, y, z, name, List.of());
    }

    public boolean hasHighlights() { return !highlights.isEmpty(); }

    public boolean hasName() { return !name.isEmpty(); }

    public int color() { return category.defaultColor; }
}
