package com.babbur.waypointer.render.gpu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeshLayoutTest {

    @Test
    void packedLayoutAssignsSequentialOffsets() {
        MeshLayout layout = MeshLayout.packed(List.of(
                MeshLayout.Attribute.POSITION, MeshLayout.Attribute.COLOR, MeshLayout.Attribute.UV0));
        assertAll(
                () -> assertEquals(24, layout.stride()),
                () -> assertEquals(0, layout.offsetOf(MeshLayout.Attribute.POSITION)),
                () -> assertEquals(12, layout.offsetOf(MeshLayout.Attribute.COLOR)),
                () -> assertEquals(16, layout.offsetOf(MeshLayout.Attribute.UV0)),
                () -> assertEquals(-1, layout.offsetOf(MeshLayout.Attribute.NORMAL)));
    }

    @Test
    void alignedLayoutPadsNormalsToFourBytes() {
        MeshLayout layout = MeshLayout.packedAligned(List.of(
                MeshLayout.Attribute.POSITION, MeshLayout.Attribute.COLOR, MeshLayout.Attribute.NORMAL));
        assertEquals(20, layout.stride());
    }

    @Test
    void rejectsLayoutsWithoutPositionOrOverflowingAttributes() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MeshLayout(16, Map.of(MeshLayout.Attribute.COLOR, 0))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MeshLayout(12, Map.of(MeshLayout.Attribute.POSITION, 4))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> MeshLayout.packed(List.of(MeshLayout.Attribute.POSITION, MeshLayout.Attribute.POSITION))));
    }

    @Test
    void topologyIndexCountsFollowSequentialBufferShapes() {
        assertAll(
                () -> assertEquals(6, MeshTopology.QUADS.indexCountFor(4)),
                () -> assertEquals(12, MeshTopology.QUADS.indexCountFor(8)),
                () -> assertEquals(6, MeshTopology.LINES.indexCountFor(4)),
                () -> assertEquals(0, MeshTopology.LINES.indexCountFor(3)),
                () -> assertEquals(0, MeshTopology.QUADS.indexCountFor(0)));
    }
}
