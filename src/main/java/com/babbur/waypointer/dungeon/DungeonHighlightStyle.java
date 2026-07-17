package com.babbur.waypointer.dungeon;

/**
 * Visual style for a {@link DungeonHighlight}. Highlights are children of a
 * {@link DungeonWaypoint}; the parent draws the "go here" label, the children
 * draw the "look at THESE blocks once you get there" decorations.
 *
 * <p>The three styles mirror Waypointer's existing
 * {@link com.babbur.waypointer.config.WaypointerConfig.BoxStyle} so a
 * highlight reads visually consistent with the rest of the mod.
 */
public enum DungeonHighlightStyle {
    OUTLINE,
    FILLED,
    OUTLINE_FILLED
}
