package com.babbur.waypointer.crystal;

/**
 * Fair-play acceptance rule for entity-derived Crystal Hollows structure sightings.
 *
 * <p>This deliberately contains no Minecraft types so that the client-side scanner can supply its
 * measured distance and line-of-sight result without putting decision logic in the Minecraft hook.
 */
public final class EntityVisibility {
    public static final double MAX_DISTANCE = 48.0;

    private EntityVisibility() {}

    /**
     * Returns whether an entity-derived sighting may be accepted.
     *
     * @param distance distance from the local player to the entity
     * @param hasLineOfSight whether the game reports a clear clip from player to entity
     * @param sidebarNamesSameStructure whether the visible sidebar independently names this structure
     */
    public static boolean shouldAccept(
            double distance, boolean hasLineOfSight, boolean sidebarNamesSameStructure) {
        return distance <= MAX_DISTANCE && (hasLineOfSight || sidebarNamesSameStructure);
    }
}
