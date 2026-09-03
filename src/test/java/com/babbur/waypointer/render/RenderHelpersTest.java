package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderHelpersTest {

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
