package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class WaypointPaintPreviewTexture {

    static final int SIZE = 256;
    static final int MIN_SIZE = 64;
    static final int MAX_SIZE = 512;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Executor DRAWER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Waypointer paint preview");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final double[][] VERTICES = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1,  1}, {1, -1,  1}, {1, 1,  1}, {-1, 1,  1}
    };
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6},
            {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private static final FaceQuad[] FACES = {
            new FaceQuad(WaypointPaint.Face.NORTH, 0, 3, 2, 1,  0,  0, -1),
            new FaceQuad(WaypointPaint.Face.SOUTH, 5, 6, 7, 4,  0,  0,  1),
            new FaceQuad(WaypointPaint.Face.WEST,  4, 7, 3, 0, -1,  0,  0),
            new FaceQuad(WaypointPaint.Face.EAST,  1, 2, 6, 5,  1,  0,  0),
            new FaceQuad(WaypointPaint.Face.UP,    7, 6, 2, 3,  0,  1,  0),
            new FaceQuad(WaypointPaint.Face.DOWN, 0, 1, 5, 4,  0, -1,  0)
    };

    record Look(float fillAlpha, boolean outline, int outlineRgb, float outlineAlpha, float outlineWidth) {
        static final Look OPAQUE = new Look(1f, false, 0, 0f, 0f);

        Look {
            fillAlpha = Math.max(0f, Math.min(1f, fillAlpha));
            outlineAlpha = Math.max(0f, Math.min(1f, outlineAlpha));
            outlineRgb &= 0xFFFFFF;
            outline = outline && outlineAlpha > 0f && outlineWidth > 0f;
        }
    }

    private final Identifier id;
    private final DynamicTexture texture;
    private final Raster raster;

    private final int[] finished;
    private final Object handoff = new Object();
    private boolean frameReady;
    private boolean drawing;
    private boolean released;
    private Request pending;
    private Request lastRequested;

    private record Request(WaypointPaint paint, int angleStep, Look look) {}

    WaypointPaintPreviewTexture(int size) {
        long sequence = SEQUENCE.incrementAndGet();
        raster = new Raster(size);
        finished = new int[raster.size * raster.size];
        id = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "paint_preview_" + sequence);
        texture = new DynamicTexture(() -> "waypointer_paint_preview", raster.size, raster.size, false);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    Identifier id() {
        return id;
    }

    int size() {
        return raster.size;
    }

    void update(WaypointPaint paint, float angleDegrees, Look look) {
        Request request = new Request(paint, Math.floorMod(Math.round(angleDegrees), 360),
                look == null ? Look.OPAQUE : look);
        boolean upload;
        synchronized (handoff) {
            if (!request.equals(lastRequested)) {
                lastRequested = request;
                if (drawing) {
                    pending = request;
                } else {
                    drawing = true;
                    DRAWER.execute(() -> draw(request));
                }
            }
            upload = frameReady;
            frameReady = false;
            if (upload) {
                NativeImage image = texture.getPixels();
                if (image == null) return;
                MemoryUtil.memIntBuffer(image.getPointer(), finished.length).put(finished);
            }
        }
        if (upload) texture.upload();
    }

    private void draw(Request request) {
        Request next = request;
        while (next != null) {
            boolean changed;
            try {
                changed = raster.update(next.paint(), next.angleStep(), next.look());
            } catch (RuntimeException failure) {
                Waypointer.LOGGER.warn("Could not draw the paint preview", failure);
                changed = false;
            }
            synchronized (handoff) {
                if (changed && !released) {
                    System.arraycopy(raster.pixels, 0, finished, 0, finished.length);
                    frameReady = true;
                }
                next = released ? null : pending;
                pending = null;
                if (next == null) drawing = false;
            }
        }
    }

    void release() {
        synchronized (handoff) {
            released = true;
            pending = null;
        }
        Minecraft.getInstance().getTextureManager().release(id);
    }

    static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    static final class Raster {

        static final int SUPERSAMPLE = 2;

        final int size;
        private final int fine;
        private final int[] pixels;
        private final int[] samples;
        private final double[][] projected = new double[8][3];
        private final double[][] screen = new double[8][2];
        private final boolean[] frontFaces = new boolean[FACES.length];
        private final int[] colors = new int[WaypointPaint.PIXEL_COUNT];
        private WaypointPaint lastPaint;
        private Look lastLook;
        private int lastAngleStep = Integer.MIN_VALUE;

        private final int[] lastBox = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};

        Raster() {
            this(SIZE);
        }

        Raster(int size) {
            this.size = clampSize(size);
            fine = this.size * SUPERSAMPLE;
            pixels = new int[this.size * this.size];
            samples = new int[fine * fine];
        }

        int[] pixels() {
            return pixels;
        }

        boolean update(WaypointPaint paint, float angleDegrees) {
            return update(paint, angleDegrees, Look.OPAQUE);
        }

        boolean update(WaypointPaint paint, float angleDegrees, Look look) {
            Look effectiveLook = look == null ? Look.OPAQUE : look;
            int angleStep = Math.floorMod(Math.round(angleDegrees), 360);
            boolean paintChanged = !paint.equals(lastPaint);
            if (!paintChanged && angleStep == lastAngleStep && effectiveLook.equals(lastLook)) return false;
            if (paintChanged) {
                int[] palette = paint.paletteCopy();
                byte[] indices = paint.pixelsCopy();
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = palette[Byte.toUnsignedInt(indices[i])] & 0xFFFFFF;
                }
                lastPaint = paint;
            }
            rasterize(angleStep, effectiveLook);
            lastAngleStep = angleStep;
            lastLook = effectiveLook;
            return true;
        }

        private void rasterize(float angleDegrees, Look look) {
            project(angleDegrees);

            int margin = (int) Math.ceil(look.outline() ? look.outlineWidth() * 0.5 : 0) + 2;
            int boxMinX = size, boxMinY = size, boxMaxX = -1, boxMaxY = -1;
            for (double[] vertex : screen) {
                boxMinX = Math.min(boxMinX, (int) Math.floor(vertex[0]) - margin);
                boxMinY = Math.min(boxMinY, (int) Math.floor(vertex[1]) - margin);
                boxMaxX = Math.max(boxMaxX, (int) Math.ceil(vertex[0]) + margin);
                boxMaxY = Math.max(boxMaxY, (int) Math.ceil(vertex[1]) + margin);
            }
            boxMinX = clamp(boxMinX, 0, size - 1);
            boxMinY = clamp(boxMinY, 0, size - 1);
            boxMaxX = clamp(boxMaxX, 0, size - 1);
            boxMaxY = clamp(boxMaxY, 0, size - 1);
            int minX = Math.min(boxMinX, lastBox[0]), minY = Math.min(boxMinY, lastBox[1]);
            int maxX = Math.max(boxMaxX, lastBox[2]), maxY = Math.max(boxMaxY, lastBox[3]);
            for (int y = minY; y <= maxY; y++) {
                Arrays.fill(pixels, y * size + minX, y * size + maxX + 1, 0);
            }
            for (int y = minY * SUPERSAMPLE; y < (maxY + 1) * SUPERSAMPLE; y++) {
                Arrays.fill(samples, y * fine + minX * SUPERSAMPLE, y * fine + (maxX + 1) * SUPERSAMPLE, 0);
            }
            lastBox[0] = boxMinX;
            lastBox[1] = boxMinY;
            lastBox[2] = boxMaxX;
            lastBox[3] = boxMaxY;

            int fillAlpha = Math.round(look.fillAlpha() * 255f);
            if (fillAlpha > 0) {
                if (fillAlpha < 255) {
                    for (int i = 0; i < FACES.length; i++) {
                        if (!frontFaces[i]) drawFace(FACES[i], fillAlpha);
                    }
                }
                for (int i = 0; i < FACES.length; i++) {
                    if (frontFaces[i]) drawFace(FACES[i], fillAlpha);
                }
            }
            resolve(boxMinX, boxMinY, boxMaxX, boxMaxY);
            if (look.outline()) {
                for (int[] edge : EDGES) drawOutline(edge[0], edge[1], look);
            }
        }

        private void project(float angleDegrees) {
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
                projected[i][0] = fine * 0.5 + rx * (fine * 0.67) / cameraDepth;
                projected[i][1] = fine * 0.5 - ry * (fine * 0.67) / cameraDepth;
                projected[i][2] = cameraDepth;
                screen[i][0] = projected[i][0] / SUPERSAMPLE;
                screen[i][1] = projected[i][1] / SUPERSAMPLE;
            }
            for (int i = 0; i < FACES.length; i++) {
                FaceQuad face = FACES[i];
                double nz = -face.nx * sy + face.nz * cy;
                double nzz = face.ny * sp + nz * cp;
                frontFaces[i] = 4.2 * nzz > 1.0;
            }
        }

        private void drawFace(FaceQuad face, int alpha) {
            drawTriangle(face.face, face.a, face.b, face.c, 0, 1, 0, 0, 1, 0, alpha, false);
            drawTriangle(face.face, face.a, face.c, face.d, 0, 1, 1, 0, 1, 1, alpha, true);
        }

        private void drawTriangle(WaypointPaint.Face face,
                                  int ia, int ib, int ic,
                                  double ua, double va, double ub, double vb,
                                  double uc, double vc, int alpha, boolean secondHalf) {
            double[] a = projected[ia], b = projected[ib], c = projected[ic];
            double area = edge(a[0], a[1], b[0], b[1], c[0], c[1]);
            if (Math.abs(area) < 1.0E-6) return;
            int minX = clamp((int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))), 0, fine - 1);
            int maxX = clamp((int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))), 0, fine - 1);
            int minY = clamp((int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))), 0, fine - 1);
            int maxY = clamp((int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))), 0, fine - 1);

            double inverseArea = 1.0 / area;
            double inverseZa = 1.0 / a[2], inverseZb = 1.0 / b[2], inverseZc = 1.0 / c[2];
            double uaz = ua * inverseZa, ubz = ub * inverseZb, ucz = uc * inverseZc;
            double vaz = va * inverseZa, vbz = vb * inverseZb, vcz = vc * inverseZc;
            int faceOffset = face.ordinal() * WaypointPaint.FACE_PIXELS;

            double waStep = (c[1] - b[1]) * inverseArea;
            double wbStep = (a[1] - c[1]) * inverseArea;
            for (int y = minY; y <= maxY; y++) {
                double px = minX + 0.5, py = y + 0.5;
                double wa = edge(b[0], b[1], c[0], c[1], px, py) * inverseArea;
                double wb = edge(c[0], c[1], a[0], a[1], px, py) * inverseArea;
                int row = y * fine;
                boolean entered = false;
                for (int x = minX; x <= maxX; x++, wa += waStep, wb += wbStep) {
                    double wc = 1.0 - wa - wb;

                    if (wa < 0 || wb < 0 || wc < 0 || secondHalf && wc == 0) {
                        if (entered) break;
                        continue;
                    }
                    entered = true;
                    double z = 1.0 / (wa * inverseZa + wb * inverseZb + wc * inverseZc);
                    double u = (wa * uaz + wb * ubz + wc * ucz) * z;
                    double v = (wa * vaz + wb * vbz + wc * vcz) * z;
                    int tx = sampleX(face, u);
                    int ty = clamp((int) Math.floor(v * WaypointPaint.SIZE), 0, WaypointPaint.SIZE - 1);
                    int offset = row + x;
                    samples[offset] = over(colors[faceOffset + ty * WaypointPaint.SIZE + tx],
                            alpha, samples[offset]);
                }
            }
        }

        private void resolve(int minX, int minY, int maxX, int maxY) {
            int count = SUPERSAMPLE * SUPERSAMPLE;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int r = 0, g = 0, b = 0, a = 0;
                    for (int sy = 0; sy < SUPERSAMPLE; sy++) {
                        int row = (y * SUPERSAMPLE + sy) * fine + x * SUPERSAMPLE;
                        for (int sx = 0; sx < SUPERSAMPLE; sx++) {
                            int sample = samples[row + sx];
                            a += sample >>> 24;
                            r += (sample >> 16) & 0xFF;
                            g += (sample >> 8) & 0xFF;
                            b += sample & 0xFF;
                        }
                    }
                    pixels[y * size + x] = straightAbgr(r, g, b, a, count);
                }
            }
        }

        private void drawOutline(int fromIndex, int toIndex, Look look) {
            double ax = screen[fromIndex][0], ay = screen[fromIndex][1];
            double bx = screen[toIndex][0], by = screen[toIndex][1];
            double half = look.outlineWidth() * 0.5;
            double reach = half + 1;
            double dx = bx - ax, dy = by - ay;
            double lengthSq = Math.max(1.0E-9, dx * dx + dy * dy);
            double length = Math.sqrt(lengthSq);
            int r = (look.outlineRgb() >> 16) & 0xFF;
            int g = (look.outlineRgb() >> 8) & 0xFF;
            int b = look.outlineRgb() & 0xFF;

            boolean alongX = Math.abs(dx) >= Math.abs(dy);
            double slope = alongX ? dy / (Math.abs(dx) < 1.0E-9 ? 1.0E-9 : dx) : dx / dy;
            double band = reach * length / Math.max(1.0E-9, Math.abs(alongX ? dx : dy)) + 1;
            int start = (int) Math.floor((alongX ? Math.min(ax, bx) : Math.min(ay, by)) - reach);
            int end = (int) Math.ceil((alongX ? Math.max(ax, bx) : Math.max(ay, by)) + reach);
            for (int major = Math.max(0, start); major <= Math.min(size - 1, end); major++) {
                double centre = major + 0.5;
                double minorCentre = alongX ? ay + (centre - ax) * slope : ax + (centre - ay) * slope;
                int from = Math.max(0, (int) Math.floor(minorCentre - band));
                int to = Math.min(size - 1, (int) Math.ceil(minorCentre + band));
                for (int minor = from; minor <= to; minor++) {
                    double px = alongX ? centre : minor + 0.5;
                    double py = alongX ? minor + 0.5 : centre;
                    double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSq));
                    double ox = px - (ax + t * dx), oy = py - (ay + t * dy);
                    double coverage = half + 0.5 - Math.sqrt(ox * ox + oy * oy);
                    if (coverage <= 0) continue;
                    int offset = (alongX ? minor : major) * size + (alongX ? major : minor);
                    pixels[offset] = blendStraight(pixels[offset], r, g, b,
                            (float) (Math.min(1, coverage) * look.outlineAlpha()));
                }
            }
        }
    }

    private static int over(int rgb, int alpha, int destination) {
        if (alpha == 255) return 0xFF000000 | rgb;
        int inverse = 255 - alpha;
        int a = alpha + div255((destination >>> 24) * inverse);
        int r = div255(((rgb >> 16) & 0xFF) * alpha + ((destination >> 16) & 0xFF) * inverse);
        int g = div255(((rgb >> 8) & 0xFF) * alpha + ((destination >> 8) & 0xFF) * inverse);
        int b = div255((rgb & 0xFF) * alpha + (destination & 0xFF) * inverse);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int div255(int x) {
        x += 128;
        return (x + (x >> 8)) >> 8;
    }

    private static int straightAbgr(int r, int g, int b, int a, int count) {
        if (a == 0) return 0;
        int alpha = Math.round(a / (float) count);
        int red = Math.min(255, Math.round(r * 255f / a));
        int green = Math.min(255, Math.round(g * 255f / a));
        int blue = Math.min(255, Math.round(b * 255f / a));
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    private static int blendStraight(int destinationAbgr, int r, int g, int b, float alpha) {
        float destinationAlpha = (destinationAbgr >>> 24) / 255f;
        float outAlpha = alpha + destinationAlpha * (1 - alpha);
        if (outAlpha <= 0) return 0;
        float keep = destinationAlpha * (1 - alpha);
        int red = Math.round((r * alpha + (destinationAbgr & 0xFF) * keep) / outAlpha);
        int green = Math.round((g * alpha + ((destinationAbgr >> 8) & 0xFF) * keep) / outAlpha);
        int blue = Math.round((b * alpha + ((destinationAbgr >> 16) & 0xFF) * keep) / outAlpha);
        return Math.round(outAlpha * 255) << 24 | clamp(blue, 0, 255) << 16
                | clamp(green, 0, 255) << 8 | clamp(red, 0, 255);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FaceQuad(WaypointPaint.Face face, int a, int b, int c, int d,
                            double nx, double ny, double nz) {}
}
