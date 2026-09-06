package com.babbur.waypointer.crystal;

import java.util.function.BooleanSupplier;

/** Visibility rules for entity-derived structure sightings. */
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

    /** Defers line-of-sight checks until needed; unsupported entities and sidebar-confirmed sightings skip them. */
    public static boolean shouldAccept(
            double distance,
            boolean matchesSupportedEntity,
            boolean sidebarNamesSameStructure,
            BooleanSupplier lineOfSightTest) {
        if (!matchesSupportedEntity) return false;
        boolean hasLineOfSight = distance <= MAX_DISTANCE
                && !sidebarNamesSameStructure
                && lineOfSightTest.getAsBoolean();
        return shouldAccept(distance, hasLineOfSight, sidebarNamesSameStructure);
    }
}
