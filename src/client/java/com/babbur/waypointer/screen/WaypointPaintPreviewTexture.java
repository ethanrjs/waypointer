package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

final class WaypointPaintPreviewTexture {

    static final int SIZE = 256;
    private static final int EDGE_ABGR = 0xFFD9DDE2;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final double[][] VERTICES = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1,  1}, {1, -1,  1}, {1, 1,  1}, {-1, 1,  1}
    };
    private static final FaceQuad[] FACES = {
            new FaceQuad(WaypointPaint.Face.NORTH, 0, 3, 2, 1,  0,  0, -1),
            new FaceQuad(WaypointPaint.Face.SOUTH, 5, 6, 7, 4,  0,  0,  1),
            new FaceQuad(WaypointPaint.Face.WEST,  4, 7, 3, 0, -1,  0,  0),
            new FaceQuad(WaypointPaint.Face.EAST,  1, 2, 6, 5,  1,  0,  0),
            new FaceQuad(WaypointPaint.Face.UP,    7, 6, 2, 3,  0,  1,  0),
            new FaceQuad(WaypointPaint.Face.DOWN, 0, 1, 5, 4,  0, -1,  0)
    };
    private final Identifier id;
    private final DynamicTexture texture;
    private final Raster raster = new Raster();

    WaypointPaintPreviewTexture() {
        long sequence = SEQUENCE.incrementAndGet();
        id = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "paint_preview_" + sequence);
        texture = new DynamicTexture(() -> "waypointer_paint_preview", SIZE, SIZE, false);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    Identifier id() {
        return id;
    }

    void update(WaypointPaint paint, float angleDegrees) {
        NativeImage image = texture.getPixels();
        if (image == null || !raster.update(paint, angleDegrees)) return;
        MemoryUtil.memIntBuffer(image.getPointer(), SIZE * SIZE).put(raster.pixels);
        texture.upload();
    }

    void release() {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    static final class Raster {
        private final int[] pixels = new int[SIZE * SIZE];
        private final float[] depth = new float[SIZE * SIZE];
        private final double[][] projected = new double[8][3];
        private final boolean[] visibleFaces = new boolean[FACES.length];
        private final int[] colors = new int[WaypointPaint.PIXEL_COUNT];
        private WaypointPaint lastPaint;
        private int lastAngleStep = Integer.MIN_VALUE;

        int[] pixels() {
            return pixels;
        }

        boolean update(WaypointPaint paint, float angleDegrees) {
            int angleStep = Math.floorMod(Math.round(angleDegrees), 360);
            boolean paintChanged = !paint.equals(lastPaint);
            if (!paintChanged && angleStep == lastAngleStep) return false;
            if (paintChanged) {
                int[] palette = paint.paletteCopy();
                byte[] indices = paint.pixelsCopy();
                for (int i = 0; i < palette.length; i++) palette[i] = rgbToAbgr(palette[i]);
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = palette[Byte.toUnsignedInt(indices[i])];
                }
                lastPaint = paint;
            }
            rasterize(angleStep);
            lastAngleStep = angleStep;
            return true;
        }

        private void rasterize(float angleDegrees) {
            Arrays.fill(depth, Float.POSITIVE_INFINITY);
            Arrays.fill(pixels, 0);

            double yaw = Math.toRadians(angleDegrees);
            double pitch = Math.toRadians(24.0);
            double cy = Math.cos(yaw), sy = Math.sin(yaw);
            double cp = Math.cos(pitch), sp = Math.sin(pitch);
            for (int i = 0; i < VERTICES.length; i++) {
                double x = VERTICES[i][0];
                double y = VERTICES[i][1];
                double z = VERTICES[i][2];
                double rx = x * cy + z * sy;
                double rz = -x * sy + z * cy;
                double ry = y * cp - rz * sp;
                double rzz = y * sp + rz * cp;
                double cameraDepth = 4.2 - rzz;
                projected[i][0] = SIZE * 0.5 + rx * (SIZE * 0.67) / cameraDepth;
                projected[i][1] = SIZE * 0.5 - ry * (SIZE * 0.67) / cameraDepth;
                projected[i][2] = cameraDepth;
            }

            for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
                FaceQuad face = FACES[faceIndex];
                double nz = -face.nx * sy + face.nz * cy;
                double nzz = face.ny * sp + nz * cp;
                // Perspective facing depends on the camera-to-face vector, not just its normal.
                visibleFaces[faceIndex] = 4.2 * nzz > 1.0;
                if (!visibleFaces[faceIndex]) continue;
                drawTriangle(face.face,
                        face.a, face.b, face.c,
                        0, 1, 0, 0, 1, 0);
                drawTriangle(face.face,
                        face.a, face.c, face.d,
                        0, 1, 1, 0, 1, 1);
            }
            for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
                if (!visibleFaces[faceIndex]) continue;
                FaceQuad face = FACES[faceIndex];
                drawEdge(face.a, face.b);
                drawEdge(face.b, face.c);
                drawEdge(face.c, face.d);
                drawEdge(face.d, face.a);
            }
        }

        private void drawTriangle(WaypointPaint.Face face,
                                  int ia, int ib, int ic,
                                  double ua, double va, double ub, double vb,
                                  double uc, double vc) {
            double[] a = projected[ia], b = projected[ib], c = projected[ic];
            double area = edge(a[0], a[1], b[0], b[1], c[0], c[1]);
            if (Math.abs(area) < 1.0E-6) return;
            int minX = clamp((int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))), 0, SIZE - 1);
            int maxX = clamp((int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))), 0, SIZE - 1);
            int minY = clamp((int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))), 0, SIZE - 1);
            int maxY = clamp((int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))), 0, SIZE - 1);

            double inverseArea = 1.0 / area;
            double inverseZa = 1.0 / a[2], inverseZb = 1.0 / b[2], inverseZc = 1.0 / c[2];
            double uaz = ua * inverseZa, ubz = ub * inverseZb, ucz = uc * inverseZc;
            double vaz = va * inverseZa, vbz = vb * inverseZb, vcz = vc * inverseZc;
            int faceOffset = face.ordinal() * WaypointPaint.FACE_PIXELS;

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double wa = edge(b[0], b[1], c[0], c[1], px, py) * inverseArea;
                    double wb = edge(c[0], c[1], a[0], a[1], px, py) * inverseArea;
                    double wc = 1.0 - wa - wb;
                    if (wa < -1.0E-5 || wb < -1.0E-5 || wc < -1.0E-5) continue;
                    double z = 1.0 / (wa * inverseZa + wb * inverseZb + wc * inverseZc);
                    int offset = y * SIZE + x;
                    if (z >= depth[offset]) continue;
                    depth[offset] = (float) z;
                    double u = (wa * uaz + wb * ubz + wc * ucz) * z;
                    double v = (wa * vaz + wb * vbz + wc * vcz) * z;
                    int tx = sampleX(face, u);
                    int ty = clamp((int) Math.floor(v * WaypointPaint.SIZE), 0, WaypointPaint.SIZE - 1);
                    pixels[offset] = colors[faceOffset + ty * WaypointPaint.SIZE + tx];
                }
            }
        }

        private void drawEdge(int fromIndex, int toIndex) {
            double[] from = projected[fromIndex];
            double[] to = projected[toIndex];
            int steps = Math.max(1, (int) Math.ceil(Math.max(
                    Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]))));
            for (int step = 0; step <= steps; step++) {
                double t = step / (double) steps;
                int x = (int) Math.round(from[0] + (to[0] - from[0]) * t);
                int y = (int) Math.round(from[1] + (to[1] - from[1]) * t);
                if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) continue;
                float z = (float) (1.0 / ((1.0 - t) / from[2] + t / to[2]));
                int offset = y * SIZE + x;
                if (z <= depth[offset] + 0.04f) pixels[offset] = EDGE_ABGR;
            }
        }
    }

    private static int sampleX(WaypointPaint.Face face, double quadU) {
        int x = clamp((int) Math.floor(quadU * WaypointPaint.SIZE),
                0, WaypointPaint.SIZE - 1);
        return face.isSide() ? WaypointPaint.SIZE - 1 - x : x;
    }

    private static double edge(double ax, double ay, double bx, double by,
                               double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int rgbToAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FaceQuad(WaypointPaint.Face face, int a, int b, int c, int d,
                            double nx, double ny, double nz) {}
}
