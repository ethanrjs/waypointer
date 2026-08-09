package com.babbur.waypointer.screen.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewAvailabilityTest {

    @Test
    void transientFailureRetriesAfterCooldown() {
        RoutePreviewAvailability availability = new RoutePreviewAvailability();
        availability.beginScene("route-a");
        availability.markUnavailableAt(100L);

        assertTrue(availability.unavailableAt(100L + RoutePreviewAvailability.RETRY_NANOS - 1));
        assertFalse(availability.unavailableAt(100L + RoutePreviewAvailability.RETRY_NANOS));
    }

    @Test
    void changingRouteClearsFailureImmediately() {
        RoutePreviewAvailability availability = new RoutePreviewAvailability();
        availability.beginScene("route-a");
        availability.markUnavailableAt(100L);

        availability.beginScene("route-b");

        assertFalse(availability.unavailableAt(101L));
    }
}
