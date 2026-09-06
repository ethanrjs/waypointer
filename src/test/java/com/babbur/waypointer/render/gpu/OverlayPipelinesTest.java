package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.WaypointerRenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OverlayPipelinesTest {
    @Test
    void antialiasingSwitchesShadersWithoutChangingRetainedMeshLayout() {
        boolean original = WaypointerRenderPipelines.antialiasing();
        try {
            OverlayPipelines pipelines = OverlayPipelines.create();
            for (boolean depth : new boolean[]{false, true}) {
                MeshBucket bucket = MeshBucket.untextured(MeshBucket.Kind.LINES, depth);
                WaypointerRenderPipelines.setAntialiasing(false);
                var sharpType = depth ? WaypointerRenderPipelines.linesDepthTested()
                        : WaypointerRenderPipelines.linesThroughWalls();
                var sharp = pipelines.slot(bucket);
                WaypointerRenderPipelines.setAntialiasing(true);
                var smoothType = depth ? WaypointerRenderPipelines.linesDepthTested()
                        : WaypointerRenderPipelines.linesThroughWalls();
                var smooth = pipelines.slot(bucket);

                assertEquals(bucket, pipelines.bucketFor(sharpType));
                assertEquals(bucket, pipelines.bucketFor(smoothType));
                assertEquals(sharp.layout(), smooth.layout());
                assertNotEquals(sharp.inWorld().getVertexShader(), smooth.inWorld().getVertexShader());
                assertEquals(smooth.inWorld().getVertexShader(), smooth.postWorld().getVertexShader());
                assertEquals("waypointer:core/antialiased_lines", smooth.inWorld().getFragmentShader().toString());
                WaypointerRenderPipelines.setAntialiasing(false);
                assertSame(sharp, pipelines.slot(bucket));
                assertSame(sharpType, depth ? WaypointerRenderPipelines.linesDepthTested()
                        : WaypointerRenderPipelines.linesThroughWalls());
            }
        } finally {
            WaypointerRenderPipelines.setAntialiasing(original);
        }
    }
}
