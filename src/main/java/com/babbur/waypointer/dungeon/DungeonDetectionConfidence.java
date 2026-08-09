package com.babbur.waypointer.dungeon;

/** How strongly the detector has confirmed the active room. */
public enum DungeonDetectionConfidence {
    UNKNOWN,
    MAP_FALLBACK,
    CORE_MATCHED,
    CORE_CONFIRMED,
    SKELETON_CONFIRMED
}
