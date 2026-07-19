package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPaintTest {

    @Test
    void solidPaintRepeatsTheFirstPaletteColorAcrossEveryFace() {
        WaypointPaint paint = WaypointPaint.solid(0x123456);

        assertTrue(paint.hasIdenticalFaces());
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            assertEquals(0x123456, paint.color(face, 0, 0));
            assertEquals(0x123456, paint.color(face, 15, 15));
        }
    }

    @Test
    void constructorDefensivelyCopiesAndValidatesPaletteIndices() {
        int[] palette = WaypointPaint.defaultPalette(0x111111);
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 4, 7)] = 3;
        WaypointPaint paint = new WaypointPaint(palette, pixels);

        palette[3] = 0;
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.EAST, 4, 7)] = 0;
        assertEquals(0xE64646, paint.color(WaypointPaint.Face.EAST, 4, 7));

        byte[] invalid = new byte[WaypointPaint.PIXEL_COUNT];
        invalid[0] = 16;
        assertThrows(IllegalArgumentException.class,
                () -> new WaypointPaint(WaypointPaint.defaultPalette(0), invalid));
    }

    @Test
    void base64PixelsRoundTripWithoutChangingFaceOrder() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.UP, 2, 9)] = 11;
        WaypointPaint paint = new WaypointPaint(WaypointPaint.defaultPalette(0), pixels);

        assertArrayEquals(pixels, WaypointPaint.decodePixels(paint.pixelsBase64()));
        assertThrows(IllegalArgumentException.class, () -> WaypointPaint.decodePixels("AA=="));
    }

    @Test
    void identicalFaceDetectionNoticesOneChangedTexel() {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        assertTrue(new WaypointPaint(WaypointPaint.defaultPalette(0), pixels).hasIdenticalFaces());

        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.SOUTH, 8, 8)] = 1;
        assertFalse(new WaypointPaint(WaypointPaint.defaultPalette(0), pixels).hasIdenticalFaces());
    }
}
