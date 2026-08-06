package com.babbur.waypointer.screen.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutePreviewOrbitTest {

    @Test
    void completesExactlyOneClockwiseRevolutionInTwentySeconds() {
        assertEquals(45.0, RoutePreviewOrbit.angleAtSeconds(0), 0.0);
        assertEquals(135.0, RoutePreviewOrbit.angleAtSeconds(5), 0.0);
        assertEquals(225.0, RoutePreviewOrbit.angleAtSeconds(10), 0.0);
        assertEquals(315.0, RoutePreviewOrbit.angleAtSeconds(15), 0.0);
        assertEquals(45.0, RoutePreviewOrbit.angleAtSeconds(20), 0.0);
    }

    @Test
    void pauseAndResumeDoNotJump() {
        RoutePreviewOrbit orbit = new RoutePreviewOrbit();
        assertEquals(45.0, Math.toDegrees(orbit.update(0, true)), 1.0e-9);
        assertEquals(135.0, Math.toDegrees(orbit.update(5_000_000_000L, true)), 1.0e-9);
        assertEquals(135.0, Math.toDegrees(orbit.update(10_000_000_000L, false)), 1.0e-9);
        assertEquals(135.0, Math.toDegrees(orbit.update(15_000_000_000L, false)), 1.0e-9);
        assertEquals(135.0, Math.toDegrees(orbit.update(20_000_000_000L, true)), 1.0e-9);
        assertEquals(225.0, Math.toDegrees(orbit.update(25_000_000_000L, true)), 1.0e-9);
    }
}
