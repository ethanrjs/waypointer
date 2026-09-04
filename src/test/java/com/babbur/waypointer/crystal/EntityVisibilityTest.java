package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntityVisibilityTest {
    @Test
    void acceptsVisibleEntitiesAtTheMaximumDistance() {
        assertTrue(EntityVisibility.shouldAccept(48.0, true, false));
    }

    @Test
    void acceptsSidebarConfirmedEntitiesWithoutLineOfSight() {
        assertTrue(EntityVisibility.shouldAccept(12.0, false, true));
    }

    @Test
    void rejectsOccludedEntitiesWithoutSidebarConfirmation() {
        assertFalse(EntityVisibility.shouldAccept(12.0, false, false));
    }

    @Test
    void rejectsOverDistanceEntitiesEvenWhenVisibleOrSidebarConfirmed() {
        assertFalse(EntityVisibility.shouldAccept(48.000_001, true, false));
        assertFalse(EntityVisibility.shouldAccept(48.000_001, false, true));
    }
}
