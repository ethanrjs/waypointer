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
    void centerGapStartsAlongDirectionAndNeverReversesShortTracers() {
        double[] start = new double[2];
        assertTrue(TracerRenderer.lineStart(400, 300, 403, 304, 2, start));
        assertEquals(401.2, start[0], 1e-9);
        assertEquals(301.6, start[1], 1e-9);
        assertFalse(TracerRenderer.lineStart(400, 300, 403, 304, 5, start));
        assertFalse(TracerRenderer.lineStart(400, 300, 403, 304, 20, start));
        assertFalse(TracerRenderer.lineStart(400, 300, 400, 300, 0, start));
        assertFalse(TracerRenderer.lineStart(400, 300, Double.NaN, 0, 2, start));
        assertFalse(TracerRenderer.lineStart(400, 300, 403, 304, Double.NaN, start));
        assertTrue(TracerRenderer.lineStart(400, 300, 403, 304, 0, start));
        assertEquals(400, start[0]);
        assertEquals(300, start[1]);
    }

    @Test
    void routeDistanceRulesKeepInclusiveBoundsAndUnlimitedDefault() {
        assertTrue(TracerRenderer.withinRouteDistance(3, 4, 0, 5, 5));
        assertFalse(TracerRenderer.withinRouteDistance(3, 4, 0, 6, 0));
        assertFalse(TracerRenderer.withinRouteDistance(3, 4, 0, 0, 4));
        assertTrue(TracerRenderer.withinRouteDistance(100000, 0, 0, 0, 0));
        assertFalse(TracerRenderer.withinRouteDistance(Double.NaN, 0, 0, 0, 0));
    }

    @Test
    void crystalHollowsTracerSwitchLeavesUserRoutesAndOtherTemporaryMarkersAlone() throws Exception {
        var config = new WaypointerConfig();
        config.setShowTracer(true);
        var generated = new WaypointGroup(
                com.babbur.waypointer.crystal.CrystalHollowsStructureFolder.GROUP_PREFIX + "mines_of_divan",
                "Mines of Divan", "crystal_hollows");
        generated.setRuntimeOnly(true);
        generated.add(Waypoint.at(606, 88, 265));
        assertTrue(TracerRenderer.tracersEnabled(generated, config, null));
        config.setCrystalHollowsTracers(false);
        assertFalse(TracerRenderer.tracersEnabled(generated, config, null));
        generated.setRuntimeOnly(false);
        assertTrue(TracerRenderer.tracersEnabled(generated, config, null));
        var marker = Waypoint.at(100, 80, 100);
        var markTarget = com.babbur.waypointer.crystal.CompassMarkerState.class
                .getDeclaredMethod("markTarget", Waypoint.class);
        markTarget.setAccessible(true);
        markTarget.invoke(null, marker);
        assertTrue(TracerRenderer.crystalHollowsTracerAllowed(generated, marker, config));
        config.setCrystalHollowsCompassTracers(false);
        assertFalse(TracerRenderer.crystalHollowsTracerAllowed(generated, marker, config));
        assertTrue(TracerRenderer.crystalHollowsTracerAllowed(generated, Waypoint.at(100, 80, 100), config));
        config.setCrystalHollowsTracers(true);
        assertFalse(TracerRenderer.crystalHollowsTracerAllowed(generated, marker, config));
        config.setCrystalHollowsCompassTracers(true);
        assertTrue(TracerRenderer.crystalHollowsTracerAllowed(generated, marker, config));
        config.setShowTracer(false);
        assertFalse(TracerRenderer.tracersEnabled(generated, config, null));
    }

    @Test
    void defocusedCoordinateHidesTracerUntilExplicitlyFocusedAgain() throws Exception {
        var config = new WaypointerConfig();
        config.setShowTracer(true);
        var manager = new com.babbur.waypointer.core.ActiveGroupManager();
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
        var group = manager.addTempWaypoint(300, 100, 300, "Coordinate");
        manager.focusTempWaypoint(group, 0);
        var tracker = new com.babbur.waypointer.crystal.CrystalHollowsTracker(manager, config, null);
        tracker.defocus();
        var renderer = new TracerRenderer(manager, config);
        var select = TracerRenderer.class.getDeclaredMethod("straightTracerTarget",
                WaypointGroup.class, boolean.class, net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.world.phys.Vec3.class, boolean.class, boolean.class);
        select.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(select.invoke(renderer, group, false, null,
                net.minecraft.world.phys.Vec3.ZERO, false, false));
        manager.focusTempWaypoint(group, 0);
        org.junit.jupiter.api.Assertions.assertSame(group.get(0), select.invoke(renderer, group, true, null,
                net.minecraft.world.phys.Vec3.ZERO, false, false));
    }

    @Test
    void regionExitedMarkerCannotTraceEvenWhenExplicitlyFocused() throws Exception {
        var config = new WaypointerConfig();
        config.setShowTracer(true);
        var group = new WaypointGroup("temp", "Temporary", "crystal_hollows");
        group.add(Waypoint.at(300, 100, 300));
        var marker = group.get(0);
        var markExited = com.babbur.waypointer.crystal.CompassMarkerState.class
                .getDeclaredMethod("markRegionExited", Waypoint.class);
        markExited.setAccessible(true);
        markExited.invoke(null, marker);
        var renderer = new TracerRenderer(new com.babbur.waypointer.core.ActiveGroupManager(), config);
        var select = TracerRenderer.class.getDeclaredMethod("straightTracerTarget",
                WaypointGroup.class, boolean.class, net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.world.phys.Vec3.class, boolean.class, boolean.class);
        select.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(select.invoke(renderer, group, true, null,
                net.minecraft.world.phys.Vec3.ZERO, false, false));
    }

    @Test
    void straightTracerUsesCameraForRouteDistanceEvenWithoutAPlayer() throws Exception {
        var config = new WaypointerConfig();
        config.setShowTracer(true);
        config.setRouteMinRenderDistance(5);
        config.setRouteMaxRenderDistance(10);
        var group = new WaypointGroup("route", "Route", "crystal_hollows");
        group.add(Waypoint.at(0, 0, 0));
        var renderer = new TracerRenderer(new com.babbur.waypointer.core.ActiveGroupManager(), config);
        var select = TracerRenderer.class.getDeclaredMethod("straightTracerTarget",
                WaypointGroup.class, boolean.class, net.minecraft.client.player.LocalPlayer.class,
                net.minecraft.world.phys.Vec3.class, boolean.class, boolean.class);
        select.setAccessible(true);
        var target = group.current();
        for (double distance : new double[]{4, 5, 10, 11}) {
            var camera = new net.minecraft.world.phys.Vec3(
                    target.centerX() + distance, target.centerY(), target.centerZ());
            assertEquals(distance >= 5 && distance <= 10 ? target : null,
                    select.invoke(renderer, group, true, null, camera, false, false));
        }
    }

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
        config.setCrystalHollowsTracers(false);
        assertFalse(TracerRenderer.tracersEnabled(group, config, null));
        config.setCrystalHollowsTracers(true);
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
