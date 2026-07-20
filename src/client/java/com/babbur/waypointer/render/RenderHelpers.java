package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.babbur.waypointer.core.WaypointPaint;

/**
 * Tiny render utilities shared by {@link WaypointRenderer} and {@link TracerRenderer}.
 *
 * Built around 1.21+'s VertexConsumer + RenderType pipeline. Boxes and lines reuse
 * the vanilla {@code lines} render type so they batch with debug overlays and the
 * vertex format matches what the line shader expects (POSITION_COLOR_NORMAL).
 */
public final class RenderHelpers {

    // 1.21.11 added LineWidth to the lines vertex format, so every vertex must carry
    // a line width or the buffer check throws "Missing elements in vertex: LineWidth".
    // We use a chunky 3px so outlined boxes stay legible at distance and against
    // busy biomes -- 1px (vanilla default) was disappearing against grass and reeds.
    private static final float DEFAULT_LINE_WIDTH = 3.0f;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

    private RenderHelpers() {}

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >>  8) & 0xFF; }
    private static int blue(int rgb)  { return  rgb        & 0xFF; }

    public static int withAlpha(int argb, float alphaScale) {
        float clamped = Math.max(0.0f, Math.min(1.0f, alphaScale));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Append the 12 segments of an axis-aligned cube outline to {@code consumer}.
     * Caller is responsible for calling {@code endBatch} afterwards (or letting
     * the world flush handle it).
     */
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

        // bottom rectangle
        seg(consumer, pose, x1, y1, z1, x2, y1, z1, r, g, b, a, 1, 0, 0, width);
        seg(consumer, pose, x2, y1, z1, x2, y1, z2, r, g, b, a, 0, 0, 1, width);
        seg(consumer, pose, x2, y1, z2, x1, y1, z2, r, g, b, a, -1, 0, 0, width);
        seg(consumer, pose, x1, y1, z2, x1, y1, z1, r, g, b, a, 0, 0, -1, width);
        // top rectangle
        seg(consumer, pose, x1, y2, z1, x2, y2, z1, r, g, b, a, 1, 0, 0, width);
        seg(consumer, pose, x2, y2, z1, x2, y2, z2, r, g, b, a, 0, 0, 1, width);
        seg(consumer, pose, x2, y2, z2, x1, y2, z2, r, g, b, a, -1, 0, 0, width);
        seg(consumer, pose, x1, y2, z2, x1, y2, z1, r, g, b, a, 0, 0, -1, width);
        // verticals
        seg(consumer, pose, x1, y1, z1, x1, y2, z1, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x2, y1, z1, x2, y2, z1, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x2, y1, z2, x2, y2, z2, r, g, b, a, 0, 1, 0, width);
        seg(consumer, pose, x1, y1, z2, x1, y2, z2, r, g, b, a, 0, 1, 0, width);
    }

    /**
     * Append the 6 faces of an axis-aligned cube as QUADS to {@code consumer}.
     *
     * <p>Used by the filled / filled+outlined box styles. Expects a vertex
     * consumer pulled from {@link WaypointerRenderPipelines#quadsThroughWalls()}
     * (POSITION_COLOR vertex format, QUADS draw mode, translucent blending,
     * no depth test, culling disabled). Each face is wound counter-clockwise;
     * because culling is disabled on that pipeline the winding doesn't matter
     * for visibility but the order below matches DEBUG_FILLED_BOX for future
     * consistency.
     *
     * <p>Alpha is deliberately clamped to the config's waypoint box opacity by the
     * caller -- passing 1.0 here would produce a solid cube that obscures the
     * world behind it. Typical values are 0.15-0.35.
     */
    public static void emitFilledBox(VertexConsumer consumer, PoseStack ps,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        PoseStack.Pose pose = ps.last();

        // -Y face
        quad(consumer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        // +Y face
        quad(consumer, pose, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, b, a);
        // -Z face
        quad(consumer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        // +Z face
        quad(consumer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        // -X face
        quad(consumer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        // +X face
        quad(consumer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    /** Append a six-face textured box using WaypointPaint's 64x48 T-atlas. */
    public static void emitTexturedBox(VertexConsumer consumer, PoseStack ps,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float alpha,
                                       double cameraX, double cameraY, double cameraZ) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        PoseStack.Pose pose = ps.last();
        int visible = visibleTexturedFaces(x1, y1, z1, x2, y2, z2,
                cameraX, cameraY, cameraZ);

        if ((visible & 1 << WaypointPaint.Face.DOWN.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.DOWN,
                x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, a);
        if ((visible & 1 << WaypointPaint.Face.UP.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.UP,
                x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, a);
        if ((visible & 1 << WaypointPaint.Face.NORTH.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.NORTH,
                x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, a);
        if ((visible & 1 << WaypointPaint.Face.SOUTH.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.SOUTH,
                x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, a);
        if ((visible & 1 << WaypointPaint.Face.WEST.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.WEST,
                x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, a);
        if ((visible & 1 << WaypointPaint.Face.EAST.ordinal()) != 0) texturedQuad(consumer, pose, WaypointPaint.Face.EAST,
                x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, a);
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

    /**
     * Append the four side faces of a narrow vertical column.
     *
     * <p>The beam intentionally has no top or bottom cap: capped columns read as
     * solid pillars at long range, while open sides feel closer to a beacon guide.
     */
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

    /** Append a single line segment. */
    public static void emitLine(VertexConsumer consumer, PoseStack ps,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                int rgb, float alpha) {
        emitLine(consumer, ps, x1, y1, z1, x2, y2, z2, rgb, alpha, DEFAULT_LINE_WIDTH);
    }

    /** Append a single line segment using a caller-controlled pixel width. */
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
                                     int alpha) {
        float halfTexelU = 0.5f / WaypointPaintTextureCache.ATLAS_WIDTH;
        float halfTexelV = 0.5f / WaypointPaintTextureCache.ATLAS_HEIGHT;
        float u0 = face.atlasX() / (float) WaypointPaintTextureCache.ATLAS_WIDTH + halfTexelU;
        float v0 = face.atlasY() / (float) WaypointPaintTextureCache.ATLAS_HEIGHT + halfTexelV;
        float u1 = (face.atlasX() + WaypointPaint.SIZE)
                / (float) WaypointPaintTextureCache.ATLAS_WIDTH - halfTexelU;
        float v1 = (face.atlasY() + WaypointPaint.SIZE)
                / (float) WaypointPaintTextureCache.ATLAS_HEIGHT - halfTexelV;

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
