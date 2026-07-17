package com.babbur.waypointer.dungeon;

/**
 * How strongly the dungeon detector believes the active room identity.
 *
 * <p>The detector can now expose the difference between a fallback map shape,
 * an Odin-style core match, and a component-validated core match without
 * changing the public room id/name contract used by route lookup.
 */
public enum DungeonDetectionConfidence {
    UNKNOWN,
    MAP_FALLBACK,
    CORE_MATCHED,
    CORE_CONFIRMED,
    SKELETON_CONFIRMED
}
