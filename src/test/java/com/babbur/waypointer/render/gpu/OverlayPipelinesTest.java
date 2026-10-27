package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayPipelinesTest {
    @Test
    @SuppressWarnings("unchecked")
    void runtimeFormatChangeRefreshesCachedEntityStrideWithoutReplacingPipelines() throws ReflectiveOperationException {
        OverlayPipelines pipelines = OverlayPipelines.create();
        MeshBucket bucket = MeshBucket.textured(MeshBucket.Kind.BEAM, false,
                BeaconRenderer.BEAM_LOCATION);
        var original = pipelines.slot(bucket);
        var field = OverlayPipelines.class.getDeclaredField("slots");
        field.setAccessible(true);
        var slots = (Map<Object, OverlayPipelines.Slot>) field.get(pipelines);
        var entry = slots.entrySet().iterator().next();
        var expanded = new MeshLayout(original.layout().stride() + 16, original.layout().attributes());
        entry.setValue(new OverlayPipelines.Slot(original.inWorld(), original.postWorld(), expanded, new Object()));
        assertTrue(pipelines.refreshVertexLayouts());
        var refreshed = pipelines.slot(bucket);
        assertEquals(original.layout(), refreshed.layout());
        assertSame(original.inWorld(), refreshed.inWorld());
        assertSame(original.postWorld(), refreshed.postWorld());
        assertFalse(pipelines.refreshVertexLayouts());
        assertSame(refreshed, pipelines.slot(bucket), "stable frames must reuse the descriptor");
    }

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
                    for (var pipeline : new RenderPipeline[]{
                            legacy, retained.inWorld(), retained.postWorld()}) {
                        assertEquals("waypointer:core/screen_line", PipelineShaders.fragment(pipeline).toString());
                        assertEquals(expectedDepth, pipeline.getDepthStencilState());
                        assertEquals(smooth, pipeline.getShaderDefines().flags().contains("ANTIALIASING"));
                        assertEquals(PipelineShaders.vertex(legacy), PipelineShaders.vertex(pipeline));
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
                assertNotEquals(PipelineShaders.vertex(sharp.inWorld()), PipelineShaders.vertex(smooth.inWorld()));
                assertEquals(PipelineShaders.vertex(smooth.inWorld()), PipelineShaders.vertex(smooth.postWorld()));
                assertEquals("waypointer:core/screen_line", PipelineShaders.fragment(sharp.inWorld()).toString());
                assertEquals(PipelineShaders.fragment(sharp.inWorld()), PipelineShaders.fragment(sharp.postWorld()));
                assertEquals(PipelineShaders.fragment(sharp.inWorld()), PipelineShaders.fragment(smooth.inWorld()));
                assertEquals(PipelineShaders.fragment(sharp.inWorld()), PipelineShaders.fragment(smooth.postWorld()));
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
            assertFalse(shader.contains("fog.glsl"));
            assertFalse(shader.contains("apply_fog"));
            assertTrue(shader.contains("vertexColor * ColorModulator"));
            assertTrue(shader.contains("fragColor = color;"));
        }
    }
}
