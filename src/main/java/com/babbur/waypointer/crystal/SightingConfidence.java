package com.babbur.waypointer.crystal;

/** Confidence is ordered from weakest to strongest for lobby merge decisions. */
public enum SightingConfidence {
    ROUGH_AREA,
    SHARED_CHAT,
    SHARED_REMOTE,
    NPC_CHAT,
    COMPASS,
    ENTITY,
    MANUAL
}
