package com.babbur.waypointer.screen.preview;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertTrue(state.selfOcclusion());

        RoutePreviewRenderState worldAccurate = RoutePreviewRenderState.create(
                RoutePreviewScene.empty(), 0.0f, -1,
                10, 20, 250, 120, 3.5f, 4, false, scissor);
        assertFalse(worldAccurate.selfOcclusion());
    }

    @Test
    void compiledAdaptersConsumeOneSharedOrderedPassPlan() {
        assertEquals(List.of(
                        RoutePreviewPassPlan.Pass.DEPTH,
                        RoutePreviewPassPlan.Pass.SURFACE,
                        RoutePreviewPassPlan.Pass.PAINT_HOVER,
                        RoutePreviewPassPlan.Pass.CONNECTORS,
                        RoutePreviewPassPlan.Pass.OUTLINES),
                RoutePreviewPassPlan.orderedPasses(true));
        assertEquals(List.of(
                        RoutePreviewPassPlan.Pass.SURFACE,
                        RoutePreviewPassPlan.Pass.PAINT_HOVER,
                        RoutePreviewPassPlan.Pass.CONNECTORS,
                        RoutePreviewPassPlan.Pass.OUTLINES),
                RoutePreviewPassPlan.orderedPasses(false));
    }

    @Test
    void connectorAndOutlineWidthsUsePhysicalPixelFloors() {
        assertEquals(1.5f, RoutePreviewRenderCore.MIN_CONNECTOR_WIDTH_PHYSICAL_PIXELS);
        assertEquals(1.0f, RoutePreviewRenderCore.MIN_OUTLINE_WIDTH_PHYSICAL_PIXELS);
    }

    @Test
    void previewPaintAtlasKeepsReplicatedPaddingDimensions() {
        assertEquals(72, RoutePreviewPaintResource.ATLAS_WIDTH);
        assertEquals(54, RoutePreviewPaintResource.ATLAS_HEIGHT);
        assertEquals(1, RoutePreviewPaintResource.PADDING);
    }

    @Test
    void paintedSurfaceUsesACompatibleNoDepthPipelineWhenSelfOcclusionIsDisabled() {
        assertTrue(RoutePreviewPassPlan.paintedSurfaceUsesDepthTest(true));
        assertFalse(RoutePreviewPassPlan.paintedSurfaceUsesDepthTest(false));
    }
}
