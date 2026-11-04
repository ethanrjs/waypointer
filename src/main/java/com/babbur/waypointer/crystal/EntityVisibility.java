package com.babbur.waypointer.crystal;

import java.util.function.BooleanSupplier;

public final class EntityVisibility {
    public static final double MAX_DISTANCE = 48.0;
    public static final double NAME_TAG_DISTANCE = 64.0;

    private EntityVisibility() {}

    public static boolean shouldAccept(
            double distance, boolean hasLineOfSight, boolean sidebarNamesSameStructure) {
        return distance <= MAX_DISTANCE && (hasLineOfSight || sidebarNamesSameStructure);
    }

    public static boolean nameTagInRange(double cameraDistanceSquared) {
        return cameraDistanceSquared < NAME_TAG_DISTANCE * NAME_TAG_DISTANCE;
    }

    public static boolean nameTagThroughWalls(boolean nameTagShown, boolean discrete,
                                              double cameraDistanceSquared) {
        return nameTagShown && !discrete && nameTagInRange(cameraDistanceSquared);
    }

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
