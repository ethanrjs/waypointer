package com.babbur.waypointer.render.gpu;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveOrderingTest {
    @Test
    void quadsSortFromFarToNearAndResortWhenTheCameraMoves() {
        float[] centers = {159, 0, 0, 262, 0, 0, 280, 0, 0};
        float[] distances = new float[3];
        int[] order = new int[3];
        QuadSorter.sortBackToFront(centers, order, distances, new long[3], 3, false, 0, 0, 0);
        assertArrayEquals(new int[]{2, 1, 0}, order);
        assertTrue(QuadSorter.sortBackToFront(centers, order, distances, new long[3], 3, true, 300, 0, 0));
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
