package dev.ethan.waypointer.debug;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStressTest {

    @Test
    void performanceStatsHandlesLargeStaticRenderBudget() {
        int count = PerformanceStressProbe.DEFAULT_WAYPOINT_COUNT;
        PerformanceStressProbe.Result result = PerformanceStressProbe.run(count);
        PerformanceStats stats = result.worstCaseStats;

        assertEquals(count, result.waypointCount);
        assertTrue(result.scenarios.size() >= 3);
        assertEquals(count, stats.activeWaypoints());
        assertEquals(count, stats.activeVisibleWaypoints());
        assertEquals(count, stats.activeLabelCandidates());
        assertEquals(count * 24, stats.estimatedLineBoxVertices());
    }

    @Test
    void performanceStressProbeShowsLocalizedProximitySavings() {
        PerformanceStressProbe.Result result = PerformanceStressProbe.runDefault();

        int worstVisits = result.worstCaseStats.estimatedProximityIndexVisitsPerTick();
        int localizedVisits = result.localizedStats.estimatedProximityIndexVisitsPerTick();

        assertEquals(result.waypointCount, worstVisits);
        assertTrue(localizedVisits < 5);
    }

    @Test
    void performanceStressProbeIncludesDenseAndSequenceScenarios() {
        PerformanceStressProbe.Result result = PerformanceStressProbe.run(512);
        boolean foundStatic = false;
        boolean foundDense = false;
        boolean foundSequence = false;
        int sequenceRenderable = -1;

        for (PerformanceStressProbe.ScenarioResult scenario : result.scenarios) {
            if ("Static line".equals(scenario.name)) {
                foundStatic = true;
            }
            if ("Dense grid".equals(scenario.name)) {
                foundDense = true;
            }
            if ("Sequence line".equals(scenario.name)) {
                foundSequence = true;
                sequenceRenderable = scenario.worstCaseStats.activeVisibleWaypoints();
            }
        }

        assertTrue(foundStatic);
        assertTrue(foundDense);
        assertTrue(foundSequence);
        assertTrue(sequenceRenderable > 0);
        assertTrue(sequenceRenderable < result.waypointCount);
    }

    @Test
    void liveOverlayInstallsTemporaryRenderableRouteAndClearsIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        Zone zone = new Zone("hub", "Hub");
        manager.onZoneChanged(zone);

        PerformanceStressProbe.LiveOverlayResult installed =
                PerformanceStressProbe.installLiveOverlay(manager, zone, 10, 65, 10, 128);
        WaypointGroup group = manager.get(installed.groupId);
        assertNotNull(group);
        assertTrue(group.temp());
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        assertEquals(128, group.size());

        PerformanceStats stats = PerformanceStats.capture(manager, new WaypointerConfig(),
                10.5, 65.5, 10.5);
        assertEquals(128, stats.activeVisibleWaypoints());

        PerformanceStressProbe.LiveOverlayResult replaced =
                PerformanceStressProbe.installLiveOverlay(manager, zone, 10, 65, 10, 64);
        assertEquals(128, replaced.replacedWaypointCount);
        assertEquals(64, manager.get(installed.groupId).size());

        PerformanceStressProbe.ClearOverlayResult cleared =
                PerformanceStressProbe.clearLiveOverlays(manager);
        assertEquals(1, cleared.groupsRemoved);
        assertEquals(64, cleared.waypointsRemoved);
        assertNull(manager.get(installed.groupId));
    }

    @Test
    void clearLiveOverlaysDoesNotRemoveNonTempGroupsWithDebugPrefix() {
        ActiveGroupManager manager = new ActiveGroupManager();
        Zone zone = new Zone("hub", "Hub");
        manager.onZoneChanged(zone);
        WaypointGroup userGroup = new WaypointGroup(
                "debug-stress-live::hub", "User Route", "hub");
        userGroup.add(dev.ethan.waypointer.core.Waypoint.at(1, 65, 1));
        manager.add(userGroup);

        PerformanceStressProbe.ClearOverlayResult cleared =
                PerformanceStressProbe.clearLiveOverlays(manager);

        assertEquals(0, cleared.groupsRemoved);
        assertEquals(0, cleared.waypointsRemoved);
        assertNotNull(manager.get("debug-stress-live::hub"));
    }
}
