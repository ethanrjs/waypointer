package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.core.WaypointPaint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class RenderHelpers {

    // Line vertices require an explicit width on Minecraft 1.21.11 and newer.
    private static final float DEFAULT_LINE_WIDTH = 3.0f;
    /** Small world-space overlap removes butt-cap pinholes where box edges meet. */
    static final float LINE_BOX_JOIN_OVERLAP = 1.0f / 512.0f;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
    private static final int HUD_LINE_CULL_MARGIN = 64;

    private RenderHelpers() {}

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >>  8) & 0xFF; }
    private static int blue(int rgb)  { return  rgb        & 0xFF; }

    public static int withAlpha(int argb, float alphaScale) {
        float clamped = Math.max(0.0f, Math.min(1.0f, alphaScale));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    public static void emitLineBox(VertexConsumer consumer, PoseStack ps,
                                   float x1, float y1, float z1,
                                   float x2, float y2, float z2,
                                   int rgb, float alpha) {
        emitLineBox(consumer, ps, x1, y1, z1, x2, y2, z2, rgb, alpha, DEFAULT_LINE_WIDTH);
    }

    public static void emitLineBox(VertexConsumer consumer, PoseStack ps,
                                   float x1, float y1, float z1,
                                   float x2, float y2, float z2,
                                   int rgb, float alpha, float width) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        PoseStack.Pose pose = ps.last();

        float join = LINE_BOX_JOIN_OVERLAP;
        seg(consumer, pose, x1 - join, y1, z1, x2 + join, y1, z1, r, g, b, a, 1, 0, 0, width);
        seg(consumer, pose, x2, y1, z1 - join, x2, y1, z2 + join, r, g, b, a, 0, 0, 1, width);
        seg(consumer, pose, x2 + join, y1, z2, x1 - join, y1, z2, r, g, b, a, -1, 0, 0, width);
        seg(consumer, pose, x1, y1, z2 + join, x1, y1, z1 - join, r, g, b, a, 0, 0, -1, width);
        seg(consumer, pose, x1 - join, y2, z1, x2 + join, y2, z1, r, g, b, a, 1, 0, 0, width);
        seg(consumer, pose, x2, y2, z1 - join, x2, y2, z2 + join, r, g, b, a, 0, 0, 1, width);
        seg(consumer, pose, x2 + join, y2, z2, x1 - join, y2, z2, r, g, b, a, -1, 0, 0, width);
        seg(consumer, pose, x1, y2, z2 + join, x1, y2, z1 - join, r, g, b, a, 0, 0, -1, width);
        seg(consumer, pose, x1, y1 - join, z1, x1, y2 + join, z1, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x2, y1 - join, z1, x2, y2 + join, z1, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x2, y1 - join, z2, x2, y2 + join, z2, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x1, y1 - join, z2, x1, y2 + join, z2, r, g, b, a, 0, 1, 0, width);
    }

    public static void emitFilledBox(VertexConsumer consumer, PoseStack ps,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        PoseStack.Pose pose = ps.last();

        quad(consumer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad(consumer, pose, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, b, a);
        quad(consumer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad(consumer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        quad(consumer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        quad(consumer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    public static void emitFilledQuad(VertexConsumer consumer, PoseStack ps,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3,
                                      float x4, float y4, float z4,
                                      int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255f) & 0xFF;
        quad(consumer, ps.last(), x1, y1, z1, x2, y2, z2,
                x3, y3, z3, x4, y4, z4, r, g, b, a);
    }

    public static void emitTexturedBox(VertexConsumer consumer, PoseStack ps,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float alpha,
                                       double cameraX, double cameraY, double cameraZ) {
        emitTexturedBox(consumer, ps, x1, y1, z1, x2, y2, z2, alpha,
                cameraX, cameraY, cameraZ,
                WaypointPaintTextureCache.ATLAS_WIDTH,
                WaypointPaintTextureCache.ATLAS_HEIGHT, 0);
    }

    public static void emitTexturedBox(VertexConsumer consumer, PoseStack ps,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float alpha,
                                       double cameraX, double cameraY, double cameraZ,
                                       int atlasWidth, int atlasHeight, int padding) {
        int visible = visibleTexturedFaces(x1, y1, z1, x2, y2, z2,
                cameraX, cameraY, cameraZ);
        emitTexturedBoxFaces(consumer, ps, x1, y1, z1, x2, y2, z2, alpha,
                visible, atlasWidth, atlasHeight, padding);
    }

    /** Emits every exterior face for a retained mesh; back-face culling selects at draw time. */
    public static void emitTexturedBoxAllFaces(VertexConsumer consumer, PoseStack ps,
                                               float x1, float y1, float z1,
                                               float x2, float y2, float z2,
                                               float alpha) {
        int allFaces = (1 << WaypointPaint.Face.values().length) - 1;
        emitTexturedBoxFaces(consumer, ps, x1, y1, z1, x2, y2, z2, alpha, allFaces,
                WaypointPaintTextureCache.ATLAS_WIDTH,
                WaypointPaintTextureCache.ATLAS_HEIGHT, 0);
    }

    private static void emitTexturedBoxFaces(VertexConsumer consumer, PoseStack ps,
                                             float x1, float y1, float z1,
                                             float x2, float y2, float z2,
                                             float alpha, int visible,
                                             int atlasWidth, int atlasHeight, int padding) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        PoseStack.Pose pose = ps.last();

        if ((visible & 1 << WaypointPaint.Face.DOWN.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.DOWN,
                x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, a,
                atlasWidth, atlasHeight, padding);
        if ((visible & 1 << WaypointPaint.Face.UP.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.UP,
                x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, a,
                atlasWidth, atlasHeight, padding);
        if ((visible & 1 << WaypointPaint.Face.NORTH.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.NORTH,
                x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, a,
                atlasWidth, atlasHeight, padding);
        if ((visible & 1 << WaypointPaint.Face.SOUTH.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.SOUTH,
                x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, a,
                atlasWidth, atlasHeight, padding);
        if ((visible & 1 << WaypointPaint.Face.WEST.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.WEST,
                x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, a,
                atlasWidth, atlasHeight, padding);
        if ((visible & 1 << WaypointPaint.Face.EAST.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.EAST,
                x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, a,
                atlasWidth, atlasHeight, padding);
    }

    /** Select only the camera-facing exterior planes so rear paint cannot bleed through. */
    static int visibleTexturedFaces(float x1, float y1, float z1,
                                    float x2, float y2, float z2,
                                    double cameraX, double cameraY, double cameraZ) {
        int faces = 0;
        if (cameraY <= y1) faces |= 1 << WaypointPaint.Face.DOWN.ordinal();
        else if (cameraY >= y2) faces |= 1 << WaypointPaint.Face.UP.ordinal();
        if (cameraZ <= z1) faces |= 1 << WaypointPaint.Face.NORTH.ordinal();
        else if (cameraZ >= z2) faces |= 1 << WaypointPaint.Face.SOUTH.ordinal();
        if (cameraX <= x1) faces |= 1 << WaypointPaint.Face.WEST.ordinal();
        else if (cameraX >= x2) faces |= 1 << WaypointPaint.Face.EAST.ordinal();
        return faces;
    }

    /** Emits an uncapped column so distant beams do not look like solid pillars. */
    public static void emitVerticalColumn(VertexConsumer consumer, PoseStack ps,
                                          float centerX, float y1, float centerZ,
                                          float y2, float halfWidth,
                                          int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        PoseStack.Pose pose = ps.last();

        float x1 = centerX - halfWidth;
        float x2 = centerX + halfWidth;
        float z1 = centerZ - halfWidth;
        float z2 = centerZ + halfWidth;

        quad(consumer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad(consumer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
        quad(consumer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        quad(consumer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
    }

    public static void emitLine(VertexConsumer consumer, PoseStack ps,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                int rgb, float alpha) {
        emitLine(consumer, ps, x1, y1, z1, x2, y2, z2, rgb, alpha, DEFAULT_LINE_WIDTH);
    }

    public static void emitLine(VertexConsumer consumer, PoseStack ps,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                int rgb, float alpha, float width) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-5f) { nx /= len; ny /= len; nz /= len; }
        else { nx = 0; ny = 1; nz = 0; }
        PoseStack.Pose pose = ps.last();
        seg(consumer, pose, x1, y1, z1, x2, y2, z2, r, g, b, a, nx, ny, nz, width);
    }

    static void drawScreenLine(GuiGraphicsExtractor graphics, double x1, double y1,
                               double x2, double y2, int argb, double thickness) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double scaledThickness = Math.max(1.0 / guiScale,
                crispHudLineThickness(thickness) / guiScale);
        double margin = HUD_LINE_CULL_MARGIN / guiScale;
        double minX = -margin;
        double minY = -margin;
        double maxX = graphics.guiWidth() + margin;
        double maxY = graphics.guiHeight() + margin;
        int out1 = outCode(x1, y1, minX, minY, maxX, maxY);
        int out2 = outCode(x2, y2, minX, minY, maxX, maxY);
        while ((out1 | out2) != 0) {
            if ((out1 & out2) != 0) return;

            int outside = out1 != 0 ? out1 : out2;
            double x;
            double y;
            if ((outside & 8) != 0) {
                x = x1 + (x2 - x1) * (maxY - y1) / (y2 - y1);
                y = maxY;
            } else if ((outside & 4) != 0) {
                x = x1 + (x2 - x1) * (minY - y1) / (y2 - y1);
                y = minY;
            } else if ((outside & 2) != 0) {
                y = y1 + (y2 - y1) * (maxX - x1) / (x2 - x1);
                x = maxX;
            } else {
                y = y1 + (y2 - y1) * (minX - x1) / (x2 - x1);
                x = minX;
            }

            if (outside == out1) {
                x1 = x;
                y1 = y;
                out1 = outCode(x1, y1, minX, minY, maxX, maxY);
            } else {
                x2 = x;
                y2 = y;
                out2 = outCode(x2, y2, minX, minY, maxX, maxY);
            }
        }

        double dx = x2 - x1;
        double dy = y2 - y1;
        if (Math.sqrt(dx * dx + dy * dy) < 0.5) return;

        int samples = screenLineSampleCount(dx, dy);
        int radius = Math.max(0, (int) Math.floor(scaledThickness * 0.5));
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            if (x == lastX && y == lastY) continue;
            graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, argb);
            lastX = x;
            lastY = y;
        }
    }

    static double crispHudLineThickness(double thickness) {
        return Math.max(1.0, Math.floor(thickness));
    }

    static int screenLineSampleCount(double dx, double dy) {
        return Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
    }

    private static int outCode(double x, double y, double minX, double minY,
                               double maxX, double maxY) {
        int code = 0;
        if (x < minX) code |= 1;
        else if (x > maxX) code |= 2;
        if (y < minY) code |= 4;
        else if (y > maxY) code |= 8;
        return code;
    }

    private static void quad(VertexConsumer c, PoseStack.Pose pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             int r, int g, int b, int a) {
        // POSITION_COLOR vertex format -- no normal, no line width. Writing either
        // would throw "unexpected element" against the DEBUG_QUADS-style pipeline.
        c.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        c.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        c.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        c.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    private static void texturedQuad(VertexConsumer c, PoseStack.Pose pose,
                                     WaypointPaint.Face face,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3,
                                     float x4, float y4, float z4,
                                     int alpha, int atlasWidth, int atlasHeight, int padding) {
        int stride = WaypointPaint.SIZE + padding * 2;
        int faceX = (face.atlasX() / WaypointPaint.SIZE) * stride + padding;
        int faceY = (face.atlasY() / WaypointPaint.SIZE) * stride + padding;
        float halfTexelU = 0.5f / atlasWidth;
        float halfTexelV = 0.5f / atlasHeight;
        float u0 = faceX / (float) atlasWidth + halfTexelU;
        float v0 = faceY / (float) atlasHeight + halfTexelV;
        float u1 = (faceX + WaypointPaint.SIZE) / (float) atlasWidth - halfTexelU;
        float v1 = (faceY + WaypointPaint.SIZE) / (float) atlasHeight - halfTexelV;

        // Side quads are wound from exterior-right to exterior-left. Flip only
        // their U axis so editor pixels remain readable when viewed from outside.
        float firstEdgeU = face.isSide() ? u1 : u0;
        float oppositeEdgeU = face.isSide() ? u0 : u1;

        texturedVertex(c, pose, x1, y1, z1, firstEdgeU, v1, alpha);
        texturedVertex(c, pose, x2, y2, z2, firstEdgeU, v0, alpha);
        texturedVertex(c, pose, x3, y3, z3, oppositeEdgeU, v0, alpha);
        texturedVertex(c, pose, x4, y4, z4, oppositeEdgeU, v1, alpha);
    }

    private static void texturedVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                       float x, float y, float z, float u, float v,
                                       int alpha) {
        consumer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setLight(FULL_BRIGHT_LIGHT);
    }

    private static void seg(VertexConsumer c, PoseStack.Pose pose,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2,
                            int r, int g, int b, int a,
                            float nx, float ny, float nz) {
        seg(c, pose, x1, y1, z1, x2, y2, z2, r, g, b, a,
                nx, ny, nz, DEFAULT_LINE_WIDTH);
    }

    private static void seg(VertexConsumer c, PoseStack.Pose pose,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2,
                            int r, int g, int b, int a,
                            float nx, float ny, float nz, float width) {
        // Call order must match the vertex format declaration: POSITION, COLOR, NORMAL,
        // LINE_WIDTH. setLineWidth is new in 1.21.11; writing it in the wrong slot causes
        // BufferBuilder's endLastVertex to throw "Not building!" on the next addVertex
        // because the previous vertex was detected as incomplete and it closed the buffer.
        c.addVertex(pose, x1, y1, z1).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(width);
        c.addVertex(pose, x2, y2, z2).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(width);
    }
}
