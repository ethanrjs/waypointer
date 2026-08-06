package com.babbur.waypointer.screen.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewZoomTest {

    @Test
    void startsSlightlyCloserAndWheelStepsAreReversible() {
        RoutePreviewZoom zoom = new RoutePreviewZoom();

        assertEquals(1.10, zoom.factor(), 1.0e-12);
        assertTrue(zoom.scroll(1.0));
        assertEquals(1.265, zoom.factor(), 1.0e-12);
        assertTrue(zoom.scroll(-1.0));
        assertEquals(1.10, zoom.factor(), 1.0e-12);
    }

    @Test
    void clampsExtremeScrollAndIgnoresInvalidInput() {
        RoutePreviewZoom zoom = new RoutePreviewZoom();

        assertTrue(zoom.scroll(1_000.0));
        assertEquals(RoutePreviewZoom.MAX_FACTOR, zoom.factor(), 0.0);
        assertFalse(zoom.scroll(Double.NaN));
        assertFalse(zoom.scroll(0.0));
        assertTrue(zoom.scroll(-1_000.0));
        assertEquals(RoutePreviewZoom.MIN_FACTOR, zoom.factor(), 0.0);
    }
}
