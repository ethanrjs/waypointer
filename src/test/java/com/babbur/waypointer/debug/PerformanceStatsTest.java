package com.babbur.waypointer.debug;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceStatsTest {

    @Test
    void captureCountsActiveRenderAndTickWork() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("crystal_hollows", "Crystal Hollows"));

        WaypointGroup staticGroup = WaypointGroup.create("Static", "crystal_hollows");
        staticGroup.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        staticGroup.setLoadMode(WaypointGroup.LoadMode.STATIC);
        staticGroup.add(Waypoint.at(0, 64, 0));
        staticGroup.add(Waypoint.at(1, 64, 0));
        staticGroup.add(Waypoint.at(2, 64, 0));

        WaypointGroup sequenceGroup = WaypointGroup.create("Sequence", "crystal_hollows");
        sequenceGroup.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        sequenceGroup.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        for (int i = 0; i < 5; i++) {
            sequenceGroup.add(Waypoint.at(10 + i, 64, 0));
        }
        sequenceGroup.setCurrentIndex(2);

        WaypointGroup inactiveZone = WaypointGroup.create("Hub", "hub");
        inactiveZone.add(Waypoint.at(100, 64, 100));

        manager.add(staticGroup);
        manager.add(sequenceGroup);
        manager.add(inactiveZone);

        PerformanceStats stats = PerformanceStats.capture(manager, new WaypointerConfig());

        assertEquals(3, stats.totalGroups());
        assertEquals(9, stats.totalWaypoints());
        assertEquals(2, stats.activeGroups());
        assertEquals(8, stats.activeWaypoints());
        assertEquals(3, stats.activeStaticWaypoints());
        assertEquals(5, stats.activeSequenceWaypoints());
        assertEquals(6, stats.activeVisibleWaypoints());
        assertEquals(6, stats.activeLabelCandidates());
        assertEquals(6 * 24, stats.estimatedLineBoxVertices());
        assertEquals(0, stats.estimatedFillBoxVertices());
        assertEquals(0, stats.estimatedBeamVertices());
        assertEquals(6, stats.estimatedProximityIndexVisitsPerTick());
    }

    @Test
    void captureRespectsLabelAndRenderConfig() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup group = WaypointGroup.create("Static", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 64, 0));
        group.add(Waypoint.at(1, 64, 0));
        manager.add(group);

        WaypointerConfig config = new WaypointerConfig();
        config.setShowWaypointNames(false);
        config.setShowWaypointDistances(false);
        config.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);

        PerformanceStats stats = PerformanceStats.capture(manager, config);

        assertEquals(2, stats.activeVisibleWaypoints());
        assertEquals(0, stats.activeLabelCandidates());
        assertEquals(2 * 24, stats.estimatedLineBoxVertices());
        assertEquals(2 * 24, stats.estimatedFillBoxVertices());
        assertEquals(2 * 32, stats.estimatedBeamVertices());

        config.setUseBeaconBeamTextures(false);
        PerformanceStats flatBeamStats = PerformanceStats.capture(manager, config);
        assertEquals(2 * 16, flatBeamStats.estimatedBeamVertices());
    }

    @Test
    void captureWithPlayerPositionReportsNearbyProximityCandidates() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup group = WaypointGroup.create("Long Route", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(2.0);
        for (int i = 0; i < 100; i++) {
            group.add(Waypoint.at(i * 100, 64, 0));
        }
        manager.add(group);

        PerformanceStats worstCase = PerformanceStats.capture(manager, new WaypointerConfig());
        PerformanceStats positioned = PerformanceStats.capture(
                manager, new WaypointerConfig(), 500.5, 64.5, 0.5);

        assertEquals(100, worstCase.estimatedProximityIndexVisitsPerTick());
        assertEquals(1, positioned.estimatedProximityIndexVisitsPerTick());
    }
}
