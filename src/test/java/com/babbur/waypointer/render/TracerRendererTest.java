package com.babbur.waypointer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.crystal.MetalDetectorController;
import com.babbur.waypointer.progression.ProximityTracker;
import org.junit.jupiter.api.Test;

class TracerRendererTest {

    @Test
    void metalDetectorTracesOnlyConfirmedTreasureAndKeepsItUntilCollected() {
        var config = new WaypointerConfig();
        config.setShowTracer(true);
        var group = new WaypointGroup(
                MetalDetectorController.GROUP_ID, "Treasure", "crystal_hollows");
        group.setRuntimeOnly(true);
        group.add(Waypoint.at(10, 80, 20));
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        assertFalse(TracerRenderer.tracersEnabled(group, config, null));
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        assertTrue(TracerRenderer.tracersEnabled(group, config, null));
        assertFalse(ProximityTracker.updateGroupProgress(
                group, 10.5, 80.5, 20.5, false, true, true));
        assertEquals(0, group.currentIndex());
        config.setShowTracer(false);
        assertFalse(TracerRenderer.tracersEnabled(group, config, null));
        config.setShowTracer(true);
        group.add(Waypoint.at(12, 80, 20));
        assertFalse(TracerRenderer.tracersEnabled(group, config, null));
    }

    @Test
    void directlyBehindAndTinyCameraNoisePointToBottomEdge() {
        double[] point = new double[2];
        for (double offset : new double[]{0.0, -0.00001, 0.00001}) {
            TracerRenderer.projectOffscreenDirection(offset, -offset, 100.0, 800, 600, point);
            assertEquals(400.0, point[0]);
            assertEquals(600.0, point[1]);
        }
    }

    @Test
    void rearAndSideDirectionsReachViewportBoundaryWithoutChangingDirection() {
        double[] point = new double[2];
        double[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {4, 3}, {-4, -3}, {1, 100}};
        for (double[] direction : directions) {
            TracerRenderer.projectOffscreenDirection(direction[0], direction[1], 200.0,
                    800, 600, point);
            assertTrue(TracerRenderer.insideViewport(point[0], point[1], 800, 600));
            assertTrue(point[0] == 0 || point[0] == 800 || point[1] == 0 || point[1] == 600);
            assertEquals(0.0, (point[0] - 400) * direction[1]
                    - (point[1] - 300) * direction[0], 1.0e-8);
        }
    }

    @Test
    void nearPlaneProjectionCannotSendHugeCoordinatesToHudRasterizer() {
        double[] point = new double[2];
        TracerRenderer.projectDirectionToEdge(Double.MAX_VALUE, Double.MAX_VALUE,
                800, 600, point);
        assertEquals(700.0, point[0]);
        assertEquals(600.0, point[1]);
        TracerRenderer.projectDirectionToEdge(Double.NaN, 0, 800, 600, point);
        assertEquals(400.0, point[0]);
        assertEquals(600.0, point[1]);
        assertTrue(TracerRenderer.insideViewport(400, 300, 800, 600));
        assertFalse(TracerRenderer.insideViewport(801, 300, 800, 600));
        assertFalse(TracerRenderer.insideViewport(Double.NaN, 300, 800, 600));
    }

}
