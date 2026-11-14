package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.RenderHelpers;
import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static geometry is rebuilt per route; a bucket's mesh is the routes' builders back to back. */
class PerRouteCaptureTest {

    @Test
    void appendConcatenatesVerticesExactly() {
        MeshBuilder a = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);
        MeshBuilder b = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);
        RenderHelpers.emitFilledBox(a, new PoseStack(), 0, 0, 0, 1, 1, 1, 0x112233, 1.0f);
        for (int i = 0; i < 40; i++) {
            RenderHelpers.emitFilledBox(b, new PoseStack(), i, 0, 0, i + 1, 1, 1, 0x445566, 0.5f);
        }
        MeshBuilder joined = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS);
        joined.append(a);
        joined.append(b);
        joined.append(new MeshBuilder(MeshLayout.positionColor(), MeshTopology.QUADS));

        assertEquals(a.storedVertexCount() + b.storedVertexCount(), joined.storedVertexCount());
        assertEquals(a.logicalVertexCount() + b.logicalVertexCount(), joined.logicalVertexCount());
        ByteBuffer expected = ByteBuffer.allocate(a.byteSize() + b.byteSize());
        expected.put(a.bytes()).put(b.bytes()).flip();
        assertEquals(expected, joined.bytes());

        MeshBuilder lines = new MeshBuilder(MeshLayout.positionColor(), MeshTopology.LINES);
        assertThrows(IllegalArgumentException.class, () -> joined.append(lines));
    }

    @Test
    void onlyNamedRoutesRebuildAndTheRestStayRetained() {
        MeshCaptureSink sink = new MeshCaptureSink(OverlayPipelines.create(), new PoseStack());
        WaypointGroup changed = WaypointGroup.create("Changed", "hub");
        WaypointGroup unchanged = WaypointGroup.create("Unchanged", "hub");

        sink.beginCapture(null, List.of(changed, unchanged));
        assertTrue(sink.staticGeometryNeeded(changed));
        assertTrue(sink.staticGeometryNeeded(unchanged));

        sink.beginCapture(Set.of(changed), List.of(changed, unchanged));
        assertTrue(sink.staticGeometryNeeded());
        assertTrue(sink.staticGeometryNeeded(changed));
        assertFalse(sink.staticGeometryNeeded(unchanged));

        AtomicInteger ran = new AtomicInteger();
        sink.staticGroup(changed, ran::incrementAndGet);
        assertEquals(1, ran.get());
        assertEquals(1, sink.groupRebuilds());

        sink.beginCapture(Set.of(), List.of(unchanged));
        assertFalse(sink.staticGeometryNeeded());
        assertFalse(sink.staticGeometryNeeded(unchanged));
        sink.close();
    }
}
