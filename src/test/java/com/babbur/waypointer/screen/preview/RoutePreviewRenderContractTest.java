package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void depthPrecedesSurfacesAndOutlinesInThePassPlan() {
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
    void defaultFramingContainsEmittedReadableMarkersAndOutlinesThroughoutTheOrbit() {
        WaypointGroup group = WaypointGroup.create("Long route", "hub");
        group.setPaintEnabled(false);
        group.add(Waypoint.at(-10_000, -500, 0));
        group.add(Waypoint.at(10_000, 500, 0));
        WaypointerConfig config = new WaypointerConfig();
        config.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
        RoutePreviewScene scene = RoutePreviewScene.build(group, config, null);
        int width = 600, height = 300;
        double scale = RoutePreviewProjection.rotationSafeScale(scene, width, height)
                * new RoutePreviewZoom().factor();

        for (int guiScale : new int[]{1, 2, 4}) {
            for (int yaw = 0; yaw < 360; yaw += 15) {
                RoutePreviewRenderState state = RoutePreviewRenderState.create(
                        scene, (float) Math.toRadians(yaw), -1,
                        0, 0, width, height, (float) scale, guiScale, null);
                PoseStack pose = new PoseStack();
                RoutePreviewRenderCore.applyView(state, pose);
                RecordingConsumer vertices = new RecordingConsumer();
                RoutePreviewRenderCore.emitSurfaces(state, pose, vertices);
                RoutePreviewRenderCore.emitOutlines(state, pose, vertices);
                assertFalse(vertices.positions.isEmpty());
                for (Vector3f point : vertices.positions) {
                    assertTrue(Math.abs(point.x * scale) < width * 0.5,
                            "default framing must include inflated markers at yaw " + yaw);
                    assertTrue(Math.abs(point.y * scale) < height * 0.5,
                            "default framing must include outlines at yaw " + yaw);
                }
            }
        }
    }

    @Test
    void emittedOutlineAndConnectorWidthsStayDistinctAtEveryGuiScale() {
        WaypointGroup group = WaypointGroup.create("Widths", "hub");
        group.setPaintEnabled(false);
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(4, 0, 0));
        WaypointerConfig config = new WaypointerConfig();
        config.setBoxStyle(WaypointerConfig.BoxStyle.OUTLINED);
        config.setWaypointOutlineThickness(1.0);
        RoutePreviewScene scene = RoutePreviewScene.build(group, config, null);

        for (int guiScale : new int[]{1, 2, 4}) {
            RoutePreviewRenderState state = RoutePreviewRenderState.create(
                    scene, 0.0f, -1, 0, 0, 240, 160, 20.0f, guiScale, null);
            PoseStack pose = new PoseStack();
            RoutePreviewRenderCore.applyView(state, pose);
            RecordingConsumer outlines = new RecordingConsumer();
            RecordingConsumer connectors = new RecordingConsumer();
            RoutePreviewRenderCore.emitOutlines(state, pose, outlines);
            RoutePreviewRenderCore.emitConnectors(state, pose, connectors);
            assertEquals(1.0, firstRibbonWidth(outlines) * state.scale() * guiScale, 1.0e-4);
            assertEquals(1.5, firstRibbonWidth(connectors) * state.scale() * guiScale, 1.0e-4);
        }
    }

    private static double firstRibbonWidth(RecordingConsumer consumer) {
        Vector3f first = consumer.positions.get(0);
        Vector3f second = consumer.positions.get(1);
        return Math.hypot(second.x - first.x, second.y - first.y);
    }

    private static final class RecordingConsumer implements VertexConsumer {
        private final List<Vector3f> positions = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            positions.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override
        public VertexConsumer setColor(int argb) { return this; }
        @Override
        public VertexConsumer setUv(float u, float v) { return this; }
        @Override
        public VertexConsumer setUv1(int u, int v) { return this; }
        @Override
        public VertexConsumer setUv2(int u, int v) { return this; }
        @Override
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override
        public VertexConsumer setLineWidth(float width) { return this; }
    }
}
