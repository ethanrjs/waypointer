package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPaintPreviewTextureTest {

    @Test
    void rasterMatchesPerspectiveRayIntersectionsAcrossTheRotation() {
        int[] palette = WaypointPaint.defaultPalette(0x123456);
        byte[] indices = new byte[WaypointPaint.PIXEL_COUNT];
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    indices[WaypointPaint.pixelOffset(face, x, y)] =
                            (byte) ((face.ordinal() + x + 3 * y) % palette.length);
                }
            }
        }
        WaypointPaint paint = new WaypointPaint(palette, indices);
        WaypointPaintPreviewTexture.Raster raster = new WaypointPaintPreviewTexture.Raster();

        for (int angle = 0; angle < 360; angle += 15) {
            raster.update(paint, angle);
            int compared = 0, painted = 0;
            for (int y = 50; y < 210; y += 7) {
                for (int x = 50; x < 210; x += 7) {
                    int actual = raster.pixels()[y * WaypointPaintPreviewTexture.SIZE + x];
                    int expected = pixelColor(paint, angle, x, y);
                    if (expected == Integer.MIN_VALUE) continue;
                    compared++;
                    if (expected != 0) painted++;
                    assertEquals(expected, actual, "angle=" + angle + ", pixel=" + x + "," + y);
                }
            }
            assertTrue(compared > 300 && painted > 100,
                    "each rotation must compare painted faces and the surrounding background");
        }
    }

    @Test
    void unchangedFramesAreReusedWhilePaintEditsAndRotationReplaceThePixels() {
        WaypointPaintPreviewTexture.Raster raster = new WaypointPaintPreviewTexture.Raster();
        assertTrue(raster.update(WaypointPaint.solid(0x123456), 45));
        int[] firstFrame = raster.pixels().clone();
        assertEquals(0xFF563412, firstFrame[128 * WaypointPaintPreviewTexture.SIZE + 100]);
        assertFalse(raster.update(WaypointPaint.solid(0x123456), 405.2f));
        assertArrayEquals(firstFrame, raster.pixels());

        assertTrue(raster.update(WaypointPaint.solid(0xABCDEF), 45));
        assertEquals(0xFFEFCDAB, raster.pixels()[128 * WaypointPaintPreviewTexture.SIZE + 100]);
        assertTrue(raster.update(WaypointPaint.solid(0xABCDEF), 0));
        WaypointPaintPreviewTexture.Raster fresh = new WaypointPaintPreviewTexture.Raster();
        fresh.update(WaypointPaint.solid(0xABCDEF), 0);
        assertArrayEquals(fresh.pixels(), raster.pixels(),
                "rotation must clear the previous silhouette and depth buffer");
    }

    @Test
    void fillOpacityShowsBackFacesThroughAndSoftensTheSilhouette() {
        WaypointPaintPreviewTexture.Raster raster = new WaypointPaintPreviewTexture.Raster();
        raster.update(WaypointPaint.solid(0x123456), 30,
                new WaypointPaintPreviewTexture.Look(0.33f, false, 0, 0f, 0f));
        int center = raster.pixels()[128 * WaypointPaintPreviewTexture.SIZE + 128] >>> 24;
        assertTrue(center > 84 && center < 255,
                "front and back faces blend at the fill opacity: alpha=" + center);

        raster.update(WaypointPaint.solid(0x123456), 30);
        boolean softEdge = false;
        for (int pixel : raster.pixels()) {
            int alpha = pixel >>> 24;
            softEdge |= alpha > 0 && alpha < 255;
        }
        assertTrue(softEdge, "the box edge is antialiased");
    }

    @Test
    void outlineFollowsTheStyleColorAndWidth() {
        WaypointPaintPreviewTexture.Raster raster = new WaypointPaintPreviewTexture.Raster();
        raster.update(WaypointPaint.solid(0x123456), 0,
                new WaypointPaintPreviewTexture.Look(1f, true, 0xFF0000, 1f, 5f));
        int outlined = 0;
        for (int pixel : raster.pixels()) {
            if (pixel == 0xFF0000FF) outlined++;
        }
        assertTrue(outlined > 500, "outline pixels drawn in the outline color: " + outlined);

        raster.update(WaypointPaint.solid(0x123456), 0);
        for (int pixel : raster.pixels()) {
            assertTrue(pixel != 0xFF0000FF, "no outline when the style draws none");
        }
    }

    private static int pixelColor(WaypointPaint paint, int angle, int x, int y) {
        int first = Integer.MIN_VALUE;
        int samples = WaypointPaintPreviewTexture.Raster.SUPERSAMPLE;
        for (int sy = 0; sy < samples; sy++) {
            for (int sx = 0; sx < samples; sx++) {
                int color = rayColor(paint, angle,
                        x + (sx + 0.5) / samples, y + (sy + 0.5) / samples);
                if (color == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                if (sx == 0 && sy == 0) first = color;
                else if (color != first) return Integer.MIN_VALUE;
            }
        }
        return first;
    }

    private static int rayColor(WaypointPaint paint, int angle, double x, double y) {
        double yaw = Math.toRadians(angle), pitch = Math.toRadians(24);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double dx = (x - 128) / (256 * 0.67);
        double dy = (128 - y) / (256 * 0.67);
        double[] origin = {-4.2 * cp * sy, 4.2 * sp, 4.2 * cp * cy};
        double[] direction = {
                dx * cy + (dy * sp + cp) * sy, dy * cp - sp,
                dx * sy - (dy * sp + cp) * cy
        };
        double nearest = Double.POSITIVE_INFINITY;
        int hitAxis = -1, hitSign = 0;
        double[] hit = null;
        boolean nearOutline = false;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double t = (sign - origin[axis]) / direction[axis];
                if (t < 0 || t >= nearest) continue;
                double[] point = {
                        origin[0] + t * direction[0], origin[1] + t * direction[1],
                        origin[2] + t * direction[2]
                };
                double a = Math.abs(point[(axis + 1) % 3]);
                double b = Math.abs(point[(axis + 2) % 3]);
                if (a <= 1.05 && b <= 1.05) nearOutline = true;
                if (a > 1 || b > 1) continue;
                nearest = t;
                hitAxis = axis;
                hitSign = sign;
                hit = point;
            }
        }
        if (hit == null) return nearOutline ? Integer.MIN_VALUE : 0;
        if (Math.abs(hit[(hitAxis + 1) % 3]) > 0.95
                || Math.abs(hit[(hitAxis + 2) % 3]) > 0.95) return Integer.MIN_VALUE;

        WaypointPaint.Face face;
        double u, v;
        if (hitAxis == 0) {
            face = hitSign < 0 ? WaypointPaint.Face.WEST : WaypointPaint.Face.EAST;
            u = (1 - hitSign * hit[2]) / 2;
            v = (1 - hit[1]) / 2;
        } else if (hitAxis == 1) {
            face = hitSign < 0 ? WaypointPaint.Face.DOWN : WaypointPaint.Face.UP;
            u = (1 - hitSign * hit[2]) / 2;
            v = (1 - hit[0]) / 2;
        } else {
            face = hitSign < 0 ? WaypointPaint.Face.NORTH : WaypointPaint.Face.SOUTH;
            u = (1 + hitSign * hit[0]) / 2;
            v = (1 - hit[1]) / 2;
        }
        if (Math.abs(u * 16 - Math.rint(u * 16)) < 1.0E-7
                || Math.abs(v * 16 - Math.rint(v * 16)) < 1.0E-7) return Integer.MIN_VALUE;
        int rgb = paint.color(face, (int) (u * 16), (int) (v * 16));
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }
}
