package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final class RecordingConsumer implements VertexConsumer {
        private int vertices;
        private int colors;
        private int uvs;
        private int lights;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
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
