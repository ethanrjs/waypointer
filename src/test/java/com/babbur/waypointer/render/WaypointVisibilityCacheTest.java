package com.babbur.waypointer.render;

import com.babbur.waypointer.core.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointVisibilityCacheTest {

    @Test
    void cachesBothVisibilityResultsWithinAFrame() {
        WaypointVisibilityCache cache = new WaypointVisibilityCache();
        Waypoint waypoint = Waypoint.at(1, 2, 3);
        AtomicInteger checks = new AtomicInteger();
        cache.beginFrame(null, 10L, 1.0, 2.0, 3.0);

        assertFalse(cache.getOrCompute(waypoint, () -> checks.incrementAndGet() > 1));
        assertFalse(cache.getOrCompute(waypoint, () -> checks.incrementAndGet() > 1));
        assertEquals(1, checks.get());
    }

    @Test
    void frameChangesInvalidateCachedVisibility() {
        WaypointVisibilityCache cache = new WaypointVisibilityCache();
        Waypoint waypoint = Waypoint.at(1, 2, 3);
        AtomicInteger checks = new AtomicInteger();
        cache.beginFrame(null, 10L, 1.0, 2.0, 3.0);
        cache.getOrCompute(waypoint, () -> checks.incrementAndGet() > 0);

        cache.beginFrame(null, 11L, 1.0, 2.0, 3.0);

        assertTrue(cache.getOrCompute(waypoint, () -> checks.incrementAndGet() > 0));
        assertEquals(2, checks.get());
    }
}
