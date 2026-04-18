package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

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
    // We use a chunky 3px so the outlined boxes and the crosshair tracer stay legible
    // at distance and against busy biomes -- 1px (vanilla default) was disappearing
    // against grass and reeds.
    private static final float DEFAULT_LINE_WIDTH = 3.0f;

    private RenderHelpers() {}

    public static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    public static int green(int rgb) { return (rgb >>  8) & 0xFF; }
    public static int blue(int rgb)  { return  rgb        & 0xFF; }

    /**
     * Append the 12 segments of an axis-aligned cube outline to {@code consumer}.
     * Caller is responsible for calling {@code endBatch} afterwards (or letting
     * the world flush handle it).
     */
    public static void emitLineBox(VertexConsumer consumer, PoseStack ps,
                                   float x1, float y1, float z1,
                                   float x2, float y2, float z2,
                                   int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        PoseStack.Pose pose = ps.last();

        // bottom rectangle
        seg(consumer, pose, x1, y1, z1, x2, y1, z1, r, g, b, a, 1, 0, 0);
        seg(consumer, pose, x2, y1, z1, x2, y1, z2, r, g, b, a, 0, 0, 1);
        seg(consumer, pose, x2, y1, z2, x1, y1, z2, r, g, b, a, -1, 0, 0);
        seg(consumer, pose, x1, y1, z2, x1, y1, z1, r, g, b, a, 0, 0, -1);
        // top rectangle
        seg(consumer, pose, x1, y2, z1, x2, y2, z1, r, g, b, a, 1, 0, 0);
        seg(consumer, pose, x2, y2, z1, x2, y2, z2, r, g, b, a, 0, 0, 1);
        seg(consumer, pose, x2, y2, z2, x1, y2, z2, r, g, b, a, -1, 0, 0);
        seg(consumer, pose, x1, y2, z2, x1, y2, z1, r, g, b, a, 0, 0, -1);
        // verticals
        seg(consumer, pose, x1, y1, z1, x1, y2, z1, r, g, b, a, 0, 1, 0);
        seg(consumer, pose, x2, y1, z1, x2, y2, z1, r, g, b, a, 0, 1, 0);
        seg(consumer, pose, x2, y1, z2, x2, y2, z2, r, g, b, a, 0, 1, 0);
        seg(consumer, pose, x1, y1, z2, x1, y2, z2, r, g, b, a, 0, 1, 0);
    }

    /** Append a single line segment. */
    public static void emitLine(VertexConsumer consumer, PoseStack ps,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                int rgb, float alpha) {
        int r = red(rgb), g = green(rgb), b = blue(rgb);
        int a = (int) (alpha * 255f) & 0xFF;
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-5f) { nx /= len; ny /= len; nz /= len; }
        else { nx = 0; ny = 1; nz = 0; }
        PoseStack.Pose pose = ps.last();
        seg(consumer, pose, x1, y1, z1, x2, y2, z2, r, g, b, a, nx, ny, nz);
    }

    /** Force-flush a render type from a batched MultiBufferSource. No-op if not a BufferSource. */
    public static void endBatch(MultiBufferSource buffers, RenderType type) {
        if (buffers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(type);
        }
    }

    public static RenderType linesType() {
        return RenderTypes.lines();
    }

    private static void seg(VertexConsumer c, PoseStack.Pose pose,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2,
                            int r, int g, int b, int a,
                            float nx, float ny, float nz) {
        // Call order must match the vertex format declaration: POSITION, COLOR, NORMAL,
        // LINE_WIDTH. setLineWidth is new in 1.21.11; writing it in the wrong slot causes
        // BufferBuilder's endLastVertex to throw "Not building!" on the next addVertex
        // because the previous vertex was detected as incomplete and it closed the buffer.
        c.addVertex(pose, x1, y1, z1).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(DEFAULT_LINE_WIDTH);
        c.addVertex(pose, x2, y2, z2).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(DEFAULT_LINE_WIDTH);
    }
}
