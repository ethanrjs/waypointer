package com.babbur.waypointer.screen.preview;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewRenderContractTest {

    @Test
    void renderStateKeepsExactLogicalTargetDimensionsAndBounds() {
        ScreenRectangle scissor = new ScreenRectangle(10, 20, 240, 100);
        RoutePreviewRenderState state = RoutePreviewRenderState.create(
                RoutePreviewScene.empty(), (float) Math.toRadians(45), -1,
                10, 20, 250, 120, 3.5f, 4, scissor);

        assertEquals(240, state.x1() - state.x0());
        assertEquals(100, state.y1() - state.y0());
        assertEquals(240, state.bounds().width());
        assertEquals(100, state.bounds().height());
        assertEquals(3.5f, state.scale());
        assertEquals(4, state.guiScale());
    }

    @Test
    void adaptersSubmitPassesInLockedOrder() throws IOException {
        for (String target : new String[]{"26.1.2", "26.2"}) {
            String source = Files.readString(Path.of(
                    "src", "client-" + target, "java", "com", "babbur", "waypointer",
                    "screen", "preview", "RoutePreviewPipAdapter.java"));
            assertOrdered(source,
                    "RoutePreviewPipelines.depthOnly()",
                    "surfaceType(state)",
                    "RoutePreviewRenderCore::emitPaintHover",
                    "RoutePreviewPipelines.connectors()",
                    "RoutePreviewPipelines.outlines()");
        }
    }

    @Test
    void immediateAdapterFlushesEveryTypeAndDeferredAdapterUsesOrderedNodes()
            throws IOException {
        String immediate = Files.readString(adapter("26.1.2"));
        String deferred = Files.readString(adapter("26.2"));
        assertTrue(immediate.contains("bufferSource.endBatch(renderType)"));
        assertTrue(deferred.contains("collector.order(order).submitCustomGeometry"));
    }

    @Test
    void pipelinesUseNativeDepthDirectionAndAColorlessDepthPrepass() throws IOException {
        String pipelines = Files.readString(Path.of(
                "src", "client", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPipelines.java"));
        assertTrue(pipelines.contains("withDepthStencilState(DepthStencilState.DEFAULT)"));
        assertTrue(pipelines.contains("RoutePreviewPipelineCompat.noColorWrites()"));
        assertTrue(pipelines.contains(
                "new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false)"));
    }

    @Test
    void connectorsAndOutlinesUseContinuousFilledRibbons() throws IOException {
        String pipelines = Files.readString(Path.of(
                "src", "client", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPipelines.java"));
        String core = Files.readString(Path.of(
                "src", "client", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewRenderCore.java"));

        assertTrue(pipelines.contains("buildRibbons"));
        assertTrue(pipelines.contains("RenderPipelines.DEBUG_FILLED_SNIPPET"));
        assertFalse(pipelines.contains("RenderPipelines.LINES_SNIPPET"));
        assertTrue(pipelines.contains("RoutePreviewPipelineCompat.outlineDepthState()"));
        assertTrue(core.contains("RenderHelpers.emitFilledQuad"));
        assertTrue(core.contains("safeScale * Math.max(1, guiScale)"));
        assertEquals(1.5f, RoutePreviewRenderCore.MIN_CONNECTOR_WIDTH_PHYSICAL_PIXELS);
        assertEquals(1.5f, RoutePreviewRenderCore.MIN_OUTLINE_WIDTH_PHYSICAL_PIXELS);

        String normalDepth = Files.readString(Path.of(
                "src", "client-26.1.2", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPipelineCompat.java"));
        String reversedDepth = Files.readString(Path.of(
                "src", "client-26.2", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPipelineCompat.java"));
        assertTrue(normalDepth.contains("false, -1.0f, -1.0f"));
        assertTrue(reversedDepth.contains("false, 1.0f, 1.0f"));
    }

    @Test
    void previewPaintAtlasHasReplicatedPaddingAndDeferredCleanup() throws IOException {
        assertEquals(72, RoutePreviewPaintResource.ATLAS_WIDTH);
        assertEquals(54, RoutePreviewPaintResource.ATLAS_HEIGHT);
        assertEquals(1, RoutePreviewPaintResource.PADDING);
        String resource = Files.readString(Path.of(
                "src", "client", "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPaintResource.java"));
        assertTrue(resource.contains("frame + 2"));
        assertTrue(resource.contains("getTextureManager().release"));
    }

    private static Path adapter(String target) {
        return Path.of("src", "client-" + target, "java", "com", "babbur", "waypointer",
                "screen", "preview", "RoutePreviewPipAdapter.java");
    }

    private static void assertOrdered(String source, String... fragments) {
        int cursor = -1;
        for (String fragment : fragments) {
            int next = source.indexOf(fragment, cursor + 1);
            assertTrue(next > cursor, "missing or out-of-order fragment: " + fragment);
            cursor = next;
        }
    }
}
