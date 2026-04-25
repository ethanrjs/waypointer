package dev.ethan.waypointer.dungeon;

/**
 * A visual decoration attached to a parent {@link DungeonWaypoint}. The
 * highlight has no name, no progression behaviour, and no proximity tracking
 * -- it exists purely to draw a colored cube on one specific block once the
 * parent waypoint's room has been resolved.
 *
 * <p>Coordinates are room-local. They get transformed into world coordinates
 * by {@link DungeonMapMath#relativeToActual} once the active
 * {@link DungeonRoom}'s physical corner and orientation are known.
 *
 * <p>This is the "child" half of the {@code waypoint -> highlights} one-to-many
 * relationship called out in issue #9: a single parent waypoint can own
 * arbitrarily many highlights -- e.g. a "wither door" waypoint with a
 * highlight per door block, or a "bat secret" waypoint with one highlight per
 * known bat spawn block.
 *
 * @param color {@code 0xRRGGBB}, or {@link #INHERIT_COLOR} to fall back to
 *              the parent waypoint's category color.
 */
public record DungeonHighlight(int x, int y, int z, DungeonHighlightStyle style, int color) {

    public static final int INHERIT_COLOR = -1;

    public DungeonHighlight {
        if (style == null) style = DungeonHighlightStyle.OUTLINE;
    }

    /** Convenience: an outlined highlight at {@code (x, y, z)} that inherits its parent color. */
    public static DungeonHighlight outline(int x, int y, int z) {
        return new DungeonHighlight(x, y, z, DungeonHighlightStyle.OUTLINE, INHERIT_COLOR);
    }

    /** Convenience: a filled highlight at {@code (x, y, z)} that inherits its parent color. */
    public static DungeonHighlight filled(int x, int y, int z) {
        return new DungeonHighlight(x, y, z, DungeonHighlightStyle.FILLED, INHERIT_COLOR);
    }

    public boolean hasOwnColor() {
        return color != INHERIT_COLOR;
    }
}
