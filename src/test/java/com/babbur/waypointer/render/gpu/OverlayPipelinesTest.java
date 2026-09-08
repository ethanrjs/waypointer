package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayPipelinesTest {
    @Test
    void fogFreeOutlinesKeepDepthAndAntialiasingContractsInEverySubmissionPath() {
        boolean original = WaypointerRenderPipelines.antialiasing();
        try {
            OverlayPipelines pipelines = OverlayPipelines.create();
            for (boolean smooth : new boolean[]{false, true}) {
                WaypointerRenderPipelines.setAntialiasing(smooth);
                for (boolean depth : new boolean[]{false, true}) {
                    var legacy = (depth ? WaypointerRenderPipelines.linesDepthTested(smooth)
                            : WaypointerRenderPipelines.linesThroughWalls(smooth)).pipeline();
                    var retained = pipelines.slot(MeshBucket.untextured(MeshBucket.Kind.LINES, depth));
                    var expectedDepth = new DepthStencilState(
                            depth ? DepthStencilState.DEFAULT.depthTest() : CompareOp.ALWAYS_PASS, false);
                    // #137 must stay fixed for legacy, retained world, and post-world drawing.
                    // Removing fog must not remove occlusion or enable depth writes.
                    for (var pipeline : new com.mojang.blaze3d.pipeline.RenderPipeline[]{
                            legacy, retained.inWorld(), retained.postWorld()}) {
                        assertEquals("waypointer:core/screen_line", pipeline.getFragmentShader().toString());
                        assertEquals(expectedDepth, pipeline.getDepthStencilState());
                        assertEquals(smooth, pipeline.getShaderDefines().flags().contains("ANTIALIASING"));
                        assertEquals(legacy.getVertexShader(), pipeline.getVertexShader());
                    }
                }
            }
        } finally {
            WaypointerRenderPipelines.setAntialiasing(original);
        }
    }

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
                assertEquals("waypointer:core/screen_line", sharp.inWorld().getFragmentShader().toString());
                assertEquals(sharp.inWorld().getFragmentShader(), sharp.postWorld().getFragmentShader());
                assertEquals(sharp.inWorld().getFragmentShader(), smooth.inWorld().getFragmentShader());
                assertEquals(sharp.inWorld().getFragmentShader(), smooth.postWorld().getFragmentShader());
                assertFalse(sharp.inWorld().getShaderDefines().flags().contains("ANTIALIASING"));
                assertTrue(smooth.inWorld().getShaderDefines().flags().contains("ANTIALIASING"));
                WaypointerRenderPipelines.setAntialiasing(false);
                assertSame(sharp, pipelines.slot(bucket));
                assertSame(sharpType, depth ? WaypointerRenderPipelines.linesDepthTested()
                        : WaypointerRenderPipelines.linesThroughWalls());
            }
        } finally {
            WaypointerRenderPipelines.setAntialiasing(original);
        }
    }

    @Test
    void lineFragmentPreservesMarkerColorWithoutWorldFog() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/assets/waypointer/shaders/core/screen_line.fsh")) {
            assertNotNull(stream);
            String shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            // Regression #137: apply_fog replaces distant outline RGB with the sky's FogColor.
            assertFalse(shader.contains("fog.glsl"));
            assertFalse(shader.contains("apply_fog"));
            assertTrue(shader.contains("vertexColor * ColorModulator"));
            assertTrue(shader.contains("fragColor = color;"));
        }
    }
}
