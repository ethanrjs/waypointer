package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointPaintPreviewTextureTest {

    @Test
    void interpolatesTextureCoordinatesInPerspective() {
        double value = WaypointPaintPreviewTexture.perspectiveInterpolate(
                0.5, 0.25, 0.25,
                0.0, 1.0, 1.0,
                2.0, 4.0, 4.0);

        assertEquals(1.0 / 3.0, value, 1.0E-9);
    }
}
