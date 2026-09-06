package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.RenderHelpers;
import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshBuilderTest {

    private static final MeshLayout LINES_LAYOUT = MeshLayout.packedAligned(List.of(
            MeshLayout.Attribute.POSITION, MeshLayout.Attribute.COLOR,
            MeshLayout.Attribute.NORMAL, MeshLayout.Attribute.LINE_WIDTH));

    @Test
    void lineBoxThroughExistingEmitterDuplicatesEveryVertexLikeBufferBuilder() {
        MeshBuilder builder = new MeshBuilder(LINES_LAYOUT, MeshTopology.LINES);

        RenderHelpers.emitLineBox(builder, new PoseStack(),
                0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0xFF8800, 1.0f, 3.0f);

        assertAll(
                () -> assertEquals(24, builder.logicalVertexCount()),
                () -> assertEquals(48, builder.storedVertexCount()),
                () -> assertEquals(48 * LINES_LAYOUT.stride(), builder.byteSize()),
                () -> assertEquals(12 * 6, builder.indexCount()));

        ByteBuffer bytes = builder.bytes();
        int stride = LINES_LAYOUT.stride();
        for (int vertex = 0; vertex < 48; vertex += 2) {
            for (int i = 0; i < stride; i++) {
                assertEquals(bytes.get(vertex * stride + i), bytes.get((vertex + 1) * stride + i),
                        "duplicate of vertex " + vertex + " differs at byte " + i);
            }
        }
    }

    @Test
    void filledBoxWritesPositionColorQuads() {
        MeshBuilder builder = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);

        RenderHelpers.emitFilledBox(builder, new PoseStack(),
                0.0f, 0.0f, 0.0f, 2.0f, 2.0f, 2.0f, 0x102030, 0.5f);

        assertAll(
                () -> assertEquals(24, builder.storedVertexCount()),
                () -> assertEquals(36, builder.indexCount()),
                () -> assertEquals(24 * 16, builder.byteSize()));
        ByteBuffer bytes = builder.bytes();
        int colorOffset = MeshLayout.positionColor().offsetOf(MeshLayout.Attribute.COLOR);
        assertEquals((byte) 0x10, bytes.get(colorOffset));
        assertEquals((byte) 0x20, bytes.get(colorOffset + 1));
        assertEquals((byte) 0x30, bytes.get(colorOffset + 2));
        assertEquals((byte) 127, bytes.get(colorOffset + 3));
    }

    @Test
    void unsetColourDefaultsToOpaqueWhiteAndAbsentAttributesAreIgnored() {
        MeshBuilder builder = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);

        builder.addVertex(1.0f, 2.0f, 3.0f).setUv(0.5f, 0.5f).setNormal(0, 1, 0).setLineWidth(9);

        ByteBuffer bytes = builder.bytes();
        assertAll(
                () -> assertEquals(1, builder.storedVertexCount()),
                () -> assertEquals(1.0f, bytes.getFloat(0)),
                () -> assertEquals(2.0f, bytes.getFloat(4)),
                () -> assertEquals(3.0f, bytes.getFloat(8)),
                () -> assertEquals((byte) 0xFF, bytes.get(12)),
                () -> assertEquals((byte) 0xFF, bytes.get(15)));
    }

    @Test
    void resetKeepsCapacityAndGrowthPreservesEarlierVertices() {
        MeshBuilder builder = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);
        for (int i = 0; i < 1000; i++) {
            builder.addVertex(i, 0, 0).setColor(0xFF000000 | i);
        }
        ByteBuffer bytes = builder.bytes();
        assertEquals(999.0f, bytes.getFloat(999 * 16));
        assertEquals(1000, builder.storedVertexCount());

        builder.reset();
        assertTrue(builder.isEmpty());
        assertEquals(0, builder.indexCount());
        assertFalse(builder.bytes().hasRemaining());
    }

    @Test
    void normalPackingMatchesVanillaEncoding() {
        assertAll(
                () -> assertEquals((byte) 127, MeshBuilder.packNormal(1.0f)),
                () -> assertEquals((byte) -127, MeshBuilder.packNormal(-1.0f)),
                () -> assertEquals((byte) 0, MeshBuilder.packNormal(0.0f)),
                () -> assertEquals((byte) 127, MeshBuilder.packNormal(5.0f)));
    }

    @Test
    void translucentQuadsSortBackToFrontWithStableTies() {
        float[] centers = {
                1.0f, 0.0f, 0.0f,
                4.0f, 0.0f, 0.0f,
                -4.0f, 0.0f, 0.0f,
                2.0f, 0.0f, 0.0f
        };
        int[] order = {0, 1, 2, 3};

        QuadSorter.sortBackToFront(centers, order, new double[order.length],
                new int[order.length], order.length, false, 0.0, 0.0, 0.0);

        assertArrayEquals(new int[]{1, 2, 3, 0}, order);
    }

    @Test
    void translucentSortHandlesLargeAdversarialInput() {
        int count = 100_000;
        float[] centers = new float[count * 3];
        int[] order = new int[count];
        for (int i = 0; i < count; i++) centers[i * 3] = i;

        QuadSorter.sortBackToFront(centers, order, new double[count], new int[count],
                count, false, -1.0, 0.0, 0.0);

        assertEquals(count - 1, order[0]);
        assertEquals(0, order[count - 1]);
    }

    @Test
    void cameraMovementReusesOnlyTheExactBackToFrontOrder() {
        float[] centers = {1, 0, 0, 4, 0, 0, -4, 0, 0, 2, 0, 0};
        int[] order = new int[4];
        double[] distances = new double[4];
        int[] scratch = new int[4];
        assertTrue(QuadSorter.sortBackToFront(centers, order, distances, scratch,
                4, false, 0, 0, 0));
        assertFalse(QuadSorter.sortBackToFront(centers, order, distances, scratch,
                4, true, 0, 1, 0));
        assertArrayEquals(new int[]{1, 2, 3, 0}, order);
        assertTrue(QuadSorter.sortBackToFront(centers, order, distances, scratch,
                4, true, 1, 1, 0));
        assertArrayEquals(new int[]{2, 1, 3, 0}, order);
        assertTrue(QuadSorter.sortBackToFront(centers, order, distances, scratch,
                4, true, 0, 0, 0));
        assertArrayEquals(new int[]{1, 2, 3, 0}, order);
    }

    @Test
    void reusedOrderMatchesFullSortAcrossDenseClusterCameraMovement() {
        int count = 256;
        float[] centers = new float[count * 3];
        Random random = new Random(1);
        for (int i = 0; i < centers.length; i++) centers[i] = random.nextInt(8);
        int[] order = new int[count];
        int[] expected = new int[count];
        double[] distances = new double[count];
        int[] scratch = new int[count];
        for (int frame = 0; frame < 100; frame++) {
            double cameraX = Math.sin(frame * 0.1) * 12;
            boolean geometryChanged = frame == 0 || frame == 50;
            if (geometryChanged) centers[0] += 10;
            QuadSorter.sortBackToFront(centers, order, distances, scratch,
                    count, !geometryChanged, cameraX, 3, -4);
            QuadSorter.sortBackToFront(centers, expected, distances, scratch,
                    count, false, cameraX, 3, -4);
            assertArrayEquals(expected, order, "frame " + frame);
        }
    }
}
