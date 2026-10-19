package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointWorldStyleTest {
    @Test
    void transparentMarkerStatesAndChannelsEmitNoVertices() {
        var config = new WaypointerConfig();
        var vertices = new Recording();
        var pose = new PoseStack();
        double[] bounds = {0, 0, 0, 1, 1, 1};
        for (boolean outline : new boolean[]{false, true}) {
            WaypointWorldRenderer.emitStyledMarker(vertices, pose, bounds, 0xFFFFFF, 0, config, outline);
        }
        assertTrue(vertices.x.isEmpty(), "fully faded markers must not upload invisible geometry");
        config.setBeaconOpacity(0);
        WaypointWorldRenderer.emitStyledMarker(vertices, pose, bounds, 0xFFFFFF, 1, config, false);
        config.setWaypointOutlineOpacity(0);
        WaypointWorldRenderer.emitStyledMarker(vertices, pose, bounds, 0xFFFFFF, 1, config, true);
        assertTrue(vertices.x.isEmpty());
        config.setWaypointOutlineOpacity(1);
        WaypointWorldRenderer.emitStyledMarker(vertices, pose, bounds, 0xFFFFFF, 1, config, true);
        assertEquals(24, vertices.x.size(), "visible outline still has twelve edges");
    }

    @Test
    void longDashedConnectorWorkIsBoundedAndTransparentConnectorsAreSkipped() {
        var config = new WaypointerConfig(); config.setRouteLineDashed(true);
        var vertices = new Recording();
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO,
                new Vec3(1_000_000, 0, 0), config);
        assertEquals(4096, vertices.x.size());
        vertices.x.clear(); config.setRouteLineOpacity(0);
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO,
                new Vec3(1_000_000, 0, 0), config);
        assertTrue(vertices.x.isEmpty());
    }
    @Test
    void dashedConnectorsShareOneBudgetPerPassAndFallBackToSolidLines() {
        var config = new WaypointerConfig(); config.setRouteLineDashed(true);
        var vertices = new Recording();
        var dashes = new WaypointWorldRenderer.DashBudget(3000);
        Vec3 far = new Vec3(1_000_000, 0, 0);
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO, far, config, dashes);
        assertEquals(2 * WaypointWorldRenderer.MAX_DASHES_PER_CONNECTOR, vertices.x.size());
        vertices.x.clear();
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO, far, config, dashes);
        assertEquals(2 * (3000 - WaypointWorldRenderer.MAX_DASHES_PER_CONNECTOR), vertices.x.size());
        vertices.x.clear();
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO, far, config, dashes);
        assertEquals(List.of(0f, 1_000_000f), vertices.x, "a spent budget draws a solid line");
    }

    @Test
    void pointOverrideCanEnableGeometryAndDistanceCrossingsInvalidateRetainedVisibility() {
        WaypointerConfig config = new WaypointerConfig();
        config.setBeaconOpacity(0);
        config.setWaypointOutlineOpacity(0);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.OFF);
        WaypointGroup group = WaypointGroup.create("Styled", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(new Waypoint(0, 64, 0, "", 0xFFFFFF, 0, 0));
        WaypointWorldRenderer renderer = new WaypointWorldRenderer(new ActiveGroupManager(), config);
        assertFalse(renderer.staticBoxGeometryEnabledFor(List.of(group)));
        group.set(0, group.get(0).withStyleOverride(WaypointStyle.EMPTY
                .with("boxStyle", "FILLED").with("beaconOpacity", 1)
                .with("routeMinRenderDistance", 2).with("routeMaxRenderDistance", 10)));
        assertTrue(renderer.staticBoxGeometryEnabledFor(List.of(group)));
        Waypoint point = group.get(0);
        Vec3 center = new Vec3(point.centerX(), point.centerY(), point.centerZ());
        WaypointerConfig effective = WaypointStyles.resolve(config, group, point);
        assertFalse(WaypointWorldRenderer.withinRouteDistance(effective, point, center));
        assertTrue(WaypointWorldRenderer.withinRouteDistance(effective, point, center.add(2, 0, 0)));
        assertTrue(WaypointWorldRenderer.withinRouteDistance(effective, point, center.add(10, 0, 0)));
        assertFalse(WaypointWorldRenderer.withinRouteDistance(effective, point, center.add(10.1, 0, 0)));
        assertNotEquals(renderer.worldVisibilityFingerprint(List.of(group), center, center),
                renderer.worldVisibilityFingerprint(List.of(group), center.add(5, 0, 0), center));
        assertEquals(0, config.beaconOpacity());
    }

    @Test
    void sharedGeometryUsesConnectorControlsAndMarkerOpacityWithoutChangingDefaults() {
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointOutlineThickness(3);
        Recording vertices = new Recording();
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO, new Vec3(2, 0, 0), config);
        assertEquals(List.of(0f, 2f), vertices.x);
        assertEquals(3, vertices.width);
        assertEquals((int) (0.85f * 255), vertices.alpha);
        config.setRouteLineWidth(6);
        config.setRouteLineOpacity(0.5);
        config.setRouteLineDashed(true);
        vertices.x.clear();
        WaypointWorldRenderer.emitStyledConnector(vertices, new PoseStack(), Vec3.ZERO, new Vec3(2, 0, 0), config);
        assertEquals(List.of(0f, 0.6f, 1f, 1.6f), vertices.x);
        assertEquals(6, vertices.width);
        assertEquals((int) (0.425f * 255), vertices.alpha);
        config.setWaypointOutlineOpacity(0.5);
        WaypointWorldRenderer.emitStyledMarker(vertices, new PoseStack(), new double[]{0, 0, 0, 1, 1, 1},
                0xFFFFFF, 0.5f, config, true);
        assertEquals((int) (0.25f * 255), vertices.alpha);
    }

    @Test
    void stateDefaultsRemainUnchangedAndBeamSpeedDoesNotJumpAtFortyTicks() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup group = WaypointGroup.create("Styles", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        for (WaypointWorldRenderer.State state : WaypointWorldRenderer.State.values()) {
            assertEquals(WaypointWorldRenderer.roleAlpha(group, state, config.dimSequenceContextWaypoints()),
                    WaypointWorldRenderer.styleStateAlpha(config, group, state));
        }
        config.setUpcomingWaypointOpacity(0.8);
        config.setSequenceContextOpacity(0.6);
        config.setDimSequenceContextWaypoints(true);
        assertEquals(0.6f, WaypointWorldRenderer.styleStateAlpha(config, group, WaypointWorldRenderer.State.UPCOMING));
        assertEquals(19.875f, WaypointWorldRenderer.beaconAnimationTime(39, 0.75f, 0.5));
        assertEquals(20f, WaypointWorldRenderer.beaconAnimationTime(40, 0, 0.5));
        assertEquals(0, WaypointWorldRenderer.beaconAnimationTime(100, 0.5f, 0));
    }

    @Test
    void explicitDungeonRouteStyleOverridesTheInheritedDungeonLineToggle() {
        WaypointerConfig config = new WaypointerConfig();
        DungeonConfig dungeon = new DungeonConfig();
        dungeon.setShowDungeonRouteLines(false);
        WaypointGroup group = WaypointGroup.create("Dungeon", "dungeon");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        assertFalse(WaypointWorldRenderer.routeLinesEnabled(group, config, dungeon));
        group.setStyleOverride(WaypointStyle.EMPTY.with("showRouteLines", true));
        assertTrue(WaypointWorldRenderer.routeLinesEnabled(group, config, dungeon));
    }

    private static final class Recording implements VertexConsumer {
        final List<Float> x = new ArrayList<>();
        int alpha;
        float width;
        public VertexConsumer addVertex(float x, float y, float z) { this.x.add(x); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { alpha = a; return this; }
        public VertexConsumer setColor(int argb) { alpha = argb >>> 24; return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
        public VertexConsumer setLineWidth(float width) { this.width = width; return this; }
    }
}
