package com.babbur.waypointer.render.gpu;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveOrderingTest {
    @Test
    void staticAndDynamicGeometryCanInterleaveFromFarToNear() {
        // A static mesh contains the near and far markers; a dynamic marker lies between.
        float[] centers = {159, 0, 0, 262, 0, 0, 280, 0, 0};
        double[] distances = new double[3];
        int[] order = new int[3];
        QuadSorter.sortBackToFront(centers, order, distances, new int[3], 3, false, 0, 0, 0);
        assertArrayEquals(new int[]{2, 1, 0}, order);
        int firstEnd = QuadSorter.batchEnd(distances, order, 0, 3, 200 * 200);
        assertEquals(2, firstEnd); // First draw covers indices [0, 12), then the dynamic mesh.
        assertEquals(3, QuadSorter.batchEnd(distances, order, firstEnd, 3, Double.NEGATIVE_INFINITY));
        // Moving the camera also changes order without rebuilding retained geometry.
        assertTrue(QuadSorter.sortBackToFront(centers, order, distances, new int[3], 3, true, 300, 0, 0));
        assertArrayEquals(new int[]{0, 1, 2}, order);
        for (MeshBucket.Kind kind : MeshBucket.Kind.values()) assertTrue(kind.sorted());
    }

    @Test
    void sortedLinesKeepMinecraftExpandedLineWinding() {
        ByteBuffer indices = ByteBuffer.allocate(24);
        GpuMesh.writePrimitiveIndices(indices, MeshTopology.LINES, 8);
        assertArrayEquals(new int[]{8, 9, 10, 11, 10, 9}, ints(indices));
        indices.clear();
        GpuMesh.writePrimitiveIndices(indices, MeshTopology.QUADS, 8);
        assertArrayEquals(new int[]{8, 9, 10, 10, 11, 8}, ints(indices));
    }

    private static int[] ints(ByteBuffer buffer) {
        buffer.flip();
        int[] result = new int[6];
        buffer.asIntBuffer().get(result);
        return result;
    }
}
