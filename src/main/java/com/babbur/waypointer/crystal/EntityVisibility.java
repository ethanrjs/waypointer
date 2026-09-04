package com.babbur.waypointer.crystal;

import java.util.function.BooleanSupplier;

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

    /**
     * Evaluates a possible entity anchor while deferring the Minecraft clip until it is necessary.
     *
     * <p>Unsupported entities never run the clip. A matching sidebar is already server-visible
     * confirmation, so it also avoids the clip. All accepted candidates still flow through
     * {@link #shouldAccept(double, boolean, boolean)}.
     */
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
