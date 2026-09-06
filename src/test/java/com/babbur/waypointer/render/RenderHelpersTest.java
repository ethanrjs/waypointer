package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class RenderHelpersTest {

    @Test
    void hudLineUsesOneSubpixelQuadAtEveryGuiScale() {
        for (int scale : new int[]{1, 2, 4}) {
            var line = ScreenLineRenderState.create(new Matrix3x2f(),
                    10.25, 20.5, 1010.25, 320.5, 0xA0FF00FF, 2.5, scale, true);
            RecordingConsumer consumer = new RecordingConsumer();
            line.buildVertices(consumer);
            assertEquals(4, consumer.vertices);
            assertEquals(4, consumer.colors);
            assertEquals(4, consumer.uvs);
            assertEquals(10.25f, line.x1());
            assertEquals(1.25f, line.halfWidth());
            assertEquals(1.75, Math.hypot(line.offsetX(), line.offsetY()) * scale, 1e-6);
            assertEquals(List.of(-1.75f, 1.75f, 1.75f, -1.75f), consumer.textureUs);
            assertTrue(line.bounds().left() <= 10);
            assertTrue(line.bounds().right() >= 1011);
        }
        var crisp = ScreenLineRenderState.create(new Matrix3x2f(),
                10.25, 20.5, 10.25, 120.5, 0xFFFFFFFF, 2.5, 2, false);
        assertEquals(crisp.halfWidth(), crisp.outerWidth());
        assertEquals(-0.625f, crisp.offsetX());
        assertEquals(0, crisp.offsetY());
    }

    @Test
    void hudLineRejectsInvalidOrInvisibleGeometry() {
        assertNull(ScreenLineRenderState.create(new Matrix3x2f(),
                0, 0, Double.NaN, 1, 0xFFFFFFFF, 1, 1, true));
        assertNull(ScreenLineRenderState.create(new Matrix3x2f(),
                1, 1, 1, 1, 0xFFFFFFFF, 1, 1, true));
        assertNull(ScreenLineRenderState.create(new Matrix3x2f(),
                0, 0, 1, 1, 0x00FFFFFF, 1, 1, true));
        assertNull(ScreenLineRenderState.create(new Matrix3x2f(),
                0, 0, 1, 1, 0xFFFFFFFF, 1, 0, true));
    }

    @Test
    void paintedBoxWritesTheCompleteBeaconVertexContract() {
        RecordingConsumer consumer = new RecordingConsumer();

        RenderHelpers.emitTexturedBox(consumer, new PoseStack(),
                0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.75f,
                2.0, 2.0, 2.0);

        assertAll(
                () -> assertEquals(12, consumer.vertices),
                () -> assertEquals(12, consumer.colors),
                () -> assertEquals(12, consumer.uvs),
                () -> assertEquals(12, consumer.lights));
    }

    @Test
    void paintedBoxSelectsOnlyTheThreeCameraFacingPlanes() {
        int expected = 1 << com.babbur.waypointer.core.WaypointPaint.Face.UP.ordinal()
                | 1 << com.babbur.waypointer.core.WaypointPaint.Face.SOUTH.ordinal()
                | 1 << com.babbur.waypointer.core.WaypointPaint.Face.EAST.ordinal();

        assertEquals(expected, RenderHelpers.visibleTexturedFaces(
                0, 0, 0, 1, 1, 1, 2, 2, 2));
    }

    @Test
    void retainedPaintedBoxWritesAllSixExteriorPlanes() {
        RecordingConsumer consumer = new RecordingConsumer();

        RenderHelpers.emitTexturedBoxAllFaces(consumer, new PoseStack(),
                0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.75f);

        assertAll(
                () -> assertEquals(24, consumer.vertices),
                () -> assertEquals(24, consumer.colors),
                () -> assertEquals(24, consumer.uvs),
                () -> assertEquals(24, consumer.lights));
    }

    @Test
    void paintedSideFaceMapsTextureRightToExteriorRight() {
        RecordingConsumer consumer = new RecordingConsumer();

        RenderHelpers.emitTexturedBox(consumer, new PoseStack(),
                0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                0.5, 0.5, 2.0);

        float halfTexel = 0.5f / WaypointPaintTextureCache.ATLAS_WIDTH;
        float textureLeft = com.babbur.waypointer.core.WaypointPaint.Face.SOUTH.atlasX()
                / (float) WaypointPaintTextureCache.ATLAS_WIDTH + halfTexel;
        float textureRight = (com.babbur.waypointer.core.WaypointPaint.Face.SOUTH.atlasX()
                + com.babbur.waypointer.core.WaypointPaint.SIZE)
                / (float) WaypointPaintTextureCache.ATLAS_WIDTH - halfTexel;

        assertEquals(List.of(textureRight, textureRight, textureLeft, textureLeft),
                consumer.textureUs);
    }

    @Test
    void paddedPreviewFaceSamplesEveryPaintedColumnAtItsIntendedWidth() {
        RecordingConsumer consumer = new RecordingConsumer();
        int padding = 1;
        int stride = com.babbur.waypointer.core.WaypointPaint.SIZE + padding * 2;
        int width = stride * 4;
        int height = stride * 3;
        var face = com.babbur.waypointer.core.WaypointPaint.Face.SOUTH;
        int faceX = face.atlasX() / com.babbur.waypointer.core.WaypointPaint.SIZE * stride + padding;

        RenderHelpers.emitTexturedBox(consumer, new PoseStack(),
                0, 0, 0, 1, 1, 1, 1,
                0.5, 0.5, 2.0, width, height, padding);

        // Sample near both edges of each screen-space painted column. Center-only
        // samples would miss the old half-texel inset compressing the border columns.
        float left = consumer.textureUs.get(2);
        float right = consumer.textureUs.get(0);
        for (int column = 0; column < 16; column++) {
            for (double within : new double[]{0.1, 0.9}) {
                double uv = left + (right - left) * ((column + within) / 16.0);
                assertEquals(column, (int) Math.floor(uv * width) - faceX,
                        "Painted column " + column + " must retain its full width");
            }
        }
    }

    @Test
    void outlinedBoxEdgesOverlapAtEveryCorner() {
        RecordingConsumer consumer = new RecordingConsumer();

        RenderHelpers.emitLineBox(consumer, new PoseStack(),
                0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0xFFFFFF, 1.0f);

        assertEquals(24, consumer.positions.size());
        float join = RenderHelpers.LINE_BOX_JOIN_OVERLAP;
        assertTrue(consumer.positions.contains(List.of(-join, 0.0f, 0.0f)));
        assertTrue(consumer.positions.contains(List.of(1.0f + join, 0.0f, 0.0f)));
        assertTrue(consumer.positions.contains(List.of(0.0f, -join, 0.0f)));
        assertTrue(consumer.positions.contains(List.of(0.0f, 1.0f + join, 0.0f)));
    }

    private static final class RecordingConsumer implements VertexConsumer {
        private int vertices;
        private int colors;
        private int uvs;
        private int lights;
        private final List<Float> textureUs = new ArrayList<>();
        private final List<List<Float>> positions = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            positions.add(List.of(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            colors++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            colors++;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            uvs++;
            textureUs.add(u);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            lights++;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
