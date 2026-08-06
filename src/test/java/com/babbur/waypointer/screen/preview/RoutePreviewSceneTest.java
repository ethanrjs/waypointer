package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class RoutePreviewSceneTest {

    @Test
    void centersUnionInDoublePrecisionAtLargeCoordinates() {
        WaypointGroup group = WaypointGroup.create("Large", "hub");
        group.add(Waypoint.at(-100_000_000, 64, 25));
        group.add(Waypoint.at(100_000_000, 70, 31));

        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);

        assertEquals(0.5, scene.centerX(), 0.0);
        assertEquals(67.5, scene.centerY(), 0.0);
        assertEquals(28.5, scene.centerZ(), 0.0);
        RoutePreviewScene.Box first = scene.markers().getFirst().box();
        assertEquals(-100_000_000.5, first.minX(), 0.0);
        assertEquals(1.0, first.width(), 0.0);
    }

    @Test
    void keepsNormalAndSmallWaypointDimensionsExact() {
        WaypointGroup group = WaypointGroup.create("Sizes", "hub");
        group.add(Waypoint.at(1, 2, 3));
        group.add(new Waypoint(0, 0, 0, "Tiny", 0x123456,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                0.0, Waypoint.TEMP_NONE, 0L, 25, 41, -7));

        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);

        assertEquals(1.0, scene.markers().get(0).box().width(), 0.0);
        assertEquals(1.0 / 16.0, scene.markers().get(1).box().width(), 0.0);
        assertEquals(1.0 / 16.0, scene.markers().get(1).box().height(), 0.0);
        assertEquals(1.0 / 16.0, scene.markers().get(1).box().depth(), 0.0);
        assertEquals("(1.5625, 2.5625, -0.4375)",
                scene.markers().get(1).coordinateText());
    }

    @Test
    void connectsOnlyMainWaypointsInStoredOrder() {
        WaypointGroup group = WaypointGroup.create("Ordered", "hub");
        group.add(Waypoint.at(0, 0, 0).withName("A"));
        group.add(Waypoint.at(9, 9, 9).withName("child")
                .withFlags(Waypoint.FLAG_SUBWAYPOINT));
        group.add(Waypoint.at(2, 0, 0).withName("B"));
        group.add(Waypoint.at(2, 0, 3).withName("C"));

        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);

        assertEquals(2, scene.connectors().size());
        assertEquals(2.0, scene.connectors().get(0).x2()
                - scene.connectors().get(0).x1(), 0.0);
        assertEquals(3.0, scene.connectors().get(1).z2()
                - scene.connectors().get(1).z1(), 0.0);
        assertEquals("#1.1", scene.markers().get(1).displayIndex());
        assertEquals("Step 2 of 3", scene.markers().get(2).sequenceText());
        assertEquals("Substep 1 of step 1", scene.markers().get(1).sequenceText());
    }

    @Test
    void countsExactDuplicatePositions() {
        WaypointGroup group = WaypointGroup.create("Duplicates", "hub");
        group.add(Waypoint.at(4, 5, 6));
        group.add(Waypoint.at(4, 5, 6));
        group.add(Waypoint.at(4, 6, 6));

        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);

        assertEquals(2, scene.markers().get(0).duplicateCount());
        assertEquals(2, scene.markers().get(1).duplicateCount());
        assertEquals(1, scene.markers().get(2).duplicateCount());
    }

    @Test
    void simplifiedBoundaryIsStrictlyAboveOneThousand() {
        assertFalse(RoutePreviewScene.build(groupWithPoints(1_000),
                new WaypointerConfig(), null).simplified());
        assertTrue(RoutePreviewScene.build(groupWithPoints(1_001),
                new WaypointerConfig(), null).simplified());
    }

    @Test
    void sceneConstructionForOneThousandWaypointsStaysWithinBudget() {
        WaypointGroup group = groupWithPoints(1_000);
        WaypointerConfig config = new WaypointerConfig();
        RoutePreviewScene.build(group, config, null); // warm class initialization and JIT paths

        assertTimeout(Duration.ofMillis(50),
                () -> RoutePreviewScene.build(group, config, null));
    }

    @Test
    void emptyRouteProducesFiniteEmptyScene() {
        RoutePreviewScene scene = RoutePreviewScene.build(
                WaypointGroup.create("Empty", "hub"), new WaypointerConfig(), null);
        assertTrue(scene.markers().isEmpty());
        assertTrue(scene.connectors().isEmpty());
        assertEquals(1.0, RoutePreviewProjection.rotationSafeScale(scene, 300, 200));
    }

    private static WaypointGroup groupWithPoints(int count) {
        WaypointGroup group = WaypointGroup.create("Many", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        List<Waypoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) points.add(Waypoint.at(i, i % 7, i % 13));
        group.addAll(points);
        return group;
    }
}
