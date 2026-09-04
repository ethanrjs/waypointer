package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void raycastsOnlyMatchedEntitiesWithoutSidebarConfirmation() {
        AtomicInteger clips = new AtomicInteger();

        assertFalse(EntityVisibility.shouldAccept(
                12.0, false, false, () -> {
                    clips.incrementAndGet();
                    return true;
                }));
        assertTrue(EntityVisibility.shouldAccept(
                12.0, true, true, () -> {
                    clips.incrementAndGet();
                    return false;
                }));
        assertFalse(EntityVisibility.shouldAccept(
                49.0, true, false, () -> {
                    clips.incrementAndGet();
                    return true;
                }));
        assertTrue(EntityVisibility.shouldAccept(
                12.0, true, false, () -> {
                    clips.incrementAndGet();
                    return true;
                }));
        assertFalse(EntityVisibility.shouldAccept(
                12.0, true, false, () -> {
                    clips.incrementAndGet();
                    return false;
                }));

        assertEquals(2, clips.get());
    }
}
