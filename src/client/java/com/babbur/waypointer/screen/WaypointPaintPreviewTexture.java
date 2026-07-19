package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Perspective-correct software cube used for the painter's rotating live preview. */
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
    private final float[] depth = new float[SIZE * SIZE];
    private final double[][] projected = new double[8][3];
    private final boolean[] visibleFaces = new boolean[FACES.length];
    private WaypointPaint lastPaint;
    private int lastAngleStep = Integer.MIN_VALUE;

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
        int angleStep = Math.floorMod(Math.round(angleDegrees), 360);
        if (paint.equals(lastPaint) && angleStep == lastAngleStep) return;
        lastPaint = paint;
        lastAngleStep = angleStep;
        rasterize(paint, angleStep);
    }

    void release() {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    private void rasterize(WaypointPaint paint, float angleDegrees) {
        NativeImage image = texture.getPixels();
        if (image == null) return;
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) image.setPixelABGR(x, y, 0);
        }

        double yaw = Math.toRadians(angleDegrees);
        // Positive pitch presents the cube from above, matching the world view and mockup.
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
            visibleFaces[faceIndex] = nzz > 1.0E-6;
            if (!visibleFaces[faceIndex]) continue;
            drawTriangle(image, paint, face.face,
                    face.a, face.b, face.c,
                    0, 1, 0, 0, 1, 0);
            drawTriangle(image, paint, face.face,
                    face.a, face.c, face.d,
                    0, 1, 1, 0, 1, 1);
        }
        for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
            if (!visibleFaces[faceIndex]) continue;
            FaceQuad face = FACES[faceIndex];
            drawEdge(image, face.a, face.b);
            drawEdge(image, face.b, face.c);
            drawEdge(image, face.c, face.d);
            drawEdge(image, face.d, face.a);
        }
        texture.upload();
    }

    private void drawTriangle(NativeImage image, WaypointPaint paint, WaypointPaint.Face face,
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

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5, py = y + 0.5;
                double wa = edge(b[0], b[1], c[0], c[1], px, py) / area;
                double wb = edge(c[0], c[1], a[0], a[1], px, py) / area;
                double wc = 1.0 - wa - wb;
                if (wa < -1.0E-5 || wb < -1.0E-5 || wc < -1.0E-5) continue;
                double inverseZ = wa / a[2] + wb / b[2] + wc / c[2];
                float z = (float) (1.0 / inverseZ);
                int offset = y * SIZE + x;
                if (z >= depth[offset]) continue;
                depth[offset] = z;
                double u = perspectiveInterpolate(
                        wa, wb, wc, ua, ub, uc, a[2], b[2], c[2]);
                double v = perspectiveInterpolate(
                        wa, wb, wc, va, vb, vc, a[2], b[2], c[2]);
                int tx = clamp((int) Math.floor(u * WaypointPaint.SIZE), 0, WaypointPaint.SIZE - 1);
                int ty = clamp((int) Math.floor(v * WaypointPaint.SIZE), 0, WaypointPaint.SIZE - 1);
                image.setPixelABGR(x, y, rgbToAbgr(paint.color(face, tx, ty)));
            }
        }
    }

    private void drawEdge(NativeImage image, int fromIndex, int toIndex) {
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
            if (z <= depth[offset] + 0.04f) image.setPixelABGR(x, y, EDGE_ABGR);
        }
    }

    static double perspectiveInterpolate(double wa, double wb, double wc,
                                         double va, double vb, double vc,
                                         double za, double zb, double zc) {
        double inverseZ = wa / za + wb / zb + wc / zc;
        return (wa * va / za + wb * vb / zb + wc * vc / zc) / inverseZ;
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
