package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
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

    @Test
    void samplesSideFacesWithoutMirroringThePaint() {
        assertAll(
                () -> assertEquals(11,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.NORTH, 0.25)),
                () -> assertEquals(11,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.SOUTH, 0.25)),
                () -> assertEquals(11,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.WEST, 0.25)),
                () -> assertEquals(11,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.EAST, 0.25)),
                () -> assertEquals(4,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.UP, 0.25)),
                () -> assertEquals(4,
                        WaypointPaintPreviewTexture.sampleX(WaypointPaint.Face.DOWN, 0.25)));
    }
}
