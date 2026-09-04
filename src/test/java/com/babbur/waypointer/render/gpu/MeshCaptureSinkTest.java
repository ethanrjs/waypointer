package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshCaptureSinkTest {

    @Test
    void stableCaptureSkipsStaticWorkButStillRunsDynamicScopes() {
        MeshCaptureSink sink = new MeshCaptureSink(OverlayPipelines.create(), new PoseStack());
        assertTrue(sink.staticGeometryNeeded());
        assertTrue(sink.retainsStaticGeometry());

        sink.beginCapture(false);
        AtomicBoolean dynamicRan = new AtomicBoolean();
        sink.dynamic(() -> dynamicRan.set(true));

        assertFalse(sink.staticGeometryNeeded());
        assertTrue(dynamicRan.get());
        sink.close();
    }
}
