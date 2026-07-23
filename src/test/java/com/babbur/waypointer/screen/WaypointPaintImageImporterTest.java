package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPaintImageImporterTest {

    @Test
    void faceImportFitsToSixteenPixelsAndRepeatsEveryFace() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0xFF00FF00);
        image.setRGB(0, 1, 0xFF0000FF);
        image.setRGB(1, 1, 0xFFFFFFFF);

        int[] resized = WaypointPaintImageImporter.resize(image, 16, 16);
        assertEquals(0xFFFF0000, resized[0]);
        assertEquals(0xFF00FF00, resized[15]);
        assertEquals(0xFF0000FF, resized[15 * 16]);
        assertEquals(0xFFFFFFFF, resized[16 * 16 - 1]);

        WaypointPaint paint = WaypointPaintImageImporter.importFace(
                image, WaypointPaint.solid(0));

        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    assertEquals(paint.color(WaypointPaint.Face.NORTH, x, y),
                            paint.color(face, x, y));
                }
            }
        }
    }

    @Test
    void atlasImportMapsEveryUvRegionToItsStoredFace() throws IOException {
        BufferedImage atlas = new BufferedImage(
                WaypointPaintImageImporter.ATLAS_WIDTH,
                WaypointPaintImageImporter.ATLAS_HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        int[] faceColors = {
                0x102030, 0x405060, 0x708090,
                0xA0B0C0, 0xD04050, 0x60E070
        };
        int[] cornerColors = {0xF01020, 0x20E030, 0x3040D0, 0xF0E040};
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            int color = 0xFF000000 | faceColors[face.ordinal()];
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    atlas.setRGB(face.atlasX() + x, face.atlasY() + y, color);
                }
            }
            atlas.setRGB(face.atlasX(), face.atlasY(), 0xFF000000 | cornerColors[0]);
            atlas.setRGB(face.atlasX() + 15, face.atlasY(), 0xFF000000 | cornerColors[1]);
            atlas.setRGB(face.atlasX(), face.atlasY() + 15, 0xFF000000 | cornerColors[2]);
            atlas.setRGB(face.atlasX() + 15, face.atlasY() + 15,
                    0xFF000000 | cornerColors[3]);
        }

        WaypointPaint paint = WaypointPaintImageImporter.importFrom(
                () -> atlas, WaypointPaint.solid(0), true);

        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            assertEquals(faceColors[face.ordinal()], paint.color(face, 8, 8));
            assertEquals(cornerColors[0], paint.color(face, 0, 0));
            assertEquals(cornerColors[1], paint.color(face, 15, 0));
            assertEquals(cornerColors[2], paint.color(face, 0, 15));
            assertEquals(cornerColors[3], paint.color(face, 15, 15));
        }
    }

    @Test
    void shrinkingAreaAveragesSourcePixelsInsteadOfAliasing() {
        BufferedImage stripes = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < stripes.getHeight(); y++) {
            for (int x = 0; x < stripes.getWidth(); x++) {
                stripes.setRGB(x, y, x % 2 == 0 ? 0xFF000000 : 0xFFFFFFFF);
            }
        }

        int[] resized = WaypointPaintImageImporter.resize(stripes, 16, 16);

        for (int color : resized) assertEquals(0xFF808080, color);
    }

    @Test
    void quantizationIsDeterministicAndKeepsGradientErrorLow() {
        BufferedImage gradient = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < gradient.getHeight(); y++) {
            for (int x = 0; x < gradient.getWidth(); x++) {
                int value = Math.round(255f * x / (gradient.getWidth() - 1));
                gradient.setRGB(x, y, 0xFF000000 | value << 16 | value << 8 | value);
            }
        }

        WaypointPaint first = WaypointPaintImageImporter.importAtlas(
                gradient, WaypointPaint.solid(0));
        WaypointPaint second = WaypointPaintImageImporter.importAtlas(
                gradient, WaypointPaint.solid(0));

        assertEquals(first, second);
        Set<Integer> colors = new HashSet<>();
        long absoluteError = 0;
        int samples = 0;
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    int actual = first.color(face, x, y) & 0xFF;
                    int expected = Math.round(255f * (face.atlasX() + x) /
                            (WaypointPaintImageImporter.ATLAS_WIDTH - 1));
                    absoluteError += Math.abs(actual - expected);
                    samples++;
                    colors.add(first.color(face, x, y));
                }
            }
        }
        assertTrue(colors.size() <= WaypointPaint.PALETTE_SIZE);
        assertTrue((double) absoluteError / samples < 6.0,
                "sixteen grayscale clusters should average under six levels of error");
    }

    @Test
    void transparentPixelsPreserveExistingArtwork() {
        BufferedImage transparent = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        WaypointPaint paint = WaypointPaintImageImporter.importFace(
                transparent, WaypointPaint.solid(0x123456));

        assertEquals(0x123456, paint.color(WaypointPaint.Face.NORTH, 8, 8));
    }

    @Test
    void halfAlphaBlendsAndTransparentColorDoesNotCreateAFringe() {
        BufferedImage halfRed = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        halfRed.setRGB(0, 0, 0x80FF0000);
        WaypointPaint blended = WaypointPaintImageImporter.importFace(
                halfRed, WaypointPaint.solid(0x0000FF));
        assertEquals(0x80007F, blended.color(WaypointPaint.Face.NORTH, 8, 8));

        BufferedImage edge = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        edge.setRGB(0, 0, 0xFFFFFFFF);
        edge.setRGB(1, 0, 0x00FF0000);
        int[] resized = WaypointPaintImageImporter.resize(edge, 16, 16);
        for (int color : resized) {
            if ((color >>> 24) == 0) continue;
            assertEquals((color >>> 16) & 0xFF, (color >>> 8) & 0xFF);
            assertEquals((color >>> 8) & 0xFF, color & 0xFF);
        }
    }

    @Test
    void duplicateQuantizerCentersKeepTheirPaletteSlots() {
        int[] palette = WaypointPaintImageImporter.completePalette(
                List.of(0x112233, 0x112233, 0x445566),
                WaypointPaint.defaultPalette(0));

        assertEquals(0x112233, palette[0]);
        assertEquals(0x112233, palette[1]);
        assertEquals(0x445566, palette[2]);
    }

    @Test
    void failedImageSourceDoesNotModifyExistingPaint() {
        WaypointPaint existing = WaypointPaint.solid(0x123456);
        IOException unavailable = assertThrows(IOException.class,
                () -> WaypointPaintImageImporter.importFrom(
                        () -> { throw new IOException("image unavailable"); }, existing, false));
        assertEquals("image unavailable", unavailable.getMessage());
        assertEquals(0x123456, existing.color(WaypointPaint.Face.NORTH, 0, 0));
    }
}
