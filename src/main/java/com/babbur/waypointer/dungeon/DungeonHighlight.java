package com.babbur.waypointer.dungeon;

/**
 * A room-local block drawn for a parent waypoint. {@code color} is
 * {@code 0xRRGGBB}, or {@link #INHERIT_COLOR} to use the parent color.
 */
public record DungeonHighlight(int x, int y, int z, DungeonHighlightStyle style, int color) {

    public static final int INHERIT_COLOR = -1;

    public DungeonHighlight {
        if (style == null) style = DungeonHighlightStyle.OUTLINE;
    }

    public static DungeonHighlight outline(int x, int y, int z) {
        return new DungeonHighlight(x, y, z, DungeonHighlightStyle.OUTLINE, INHERIT_COLOR);
    }

    public static DungeonHighlight filled(int x, int y, int z) {
        return new DungeonHighlight(x, y, z, DungeonHighlightStyle.FILLED, INHERIT_COLOR);
    }

    public boolean hasOwnColor() {
        return color != INHERIT_COLOR;
    }
}
