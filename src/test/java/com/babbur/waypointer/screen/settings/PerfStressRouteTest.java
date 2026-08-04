package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfStressRouteTest {

    /** Near-hide radius cap; the baseline scenario hides waypoints at this distance. */
    private static final double NEAR_HIDE_CAP = 100.0;

    private static ActiveGroupManager managerInZone(String zoneId) {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone(zoneId, zoneId));
        return manager;
    }

    @Test
    void installBuildsTempStaticGridInTheCurrentZone() {
        ActiveGroupManager manager = managerInZone("hub");

        int installed = PerfStressRoute.install(manager, 10.0, 65.0, -20.0);

        assertEquals(PerfStressRoute.WAYPOINT_COUNT, installed);
        WaypointGroup group = manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub");
        assertNotNull(group);
        assertTrue(group.runtimeOnly(), "stress grid must never persist");
        assertFalse(group.temp(), "runtime-only keeps route-progress rendering in the test");
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        assertEquals(PerfStressRoute.WAYPOINT_COUNT, group.size());
    }

    @Test
    void everyGridPointStaysInsideTheNearHideCap() {
        // The baseline scenario hides waypoints via near-hide at the 100-block
        // cap; a grid point outside it would leak renders into the baseline.
        ActiveGroupManager manager = managerInZone("hub");
        double px = 1000.0;
        double pz = -500.0;
        PerfStressRoute.install(manager, px, 65.0, pz);

        WaypointGroup group = manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub");
        for (int i = 0; i < group.size(); i++) {
            Waypoint waypoint = group.get(i);
            double dx = waypoint.x() - px;
            double dz = waypoint.z() - pz;
            assertTrue(Math.sqrt(dx * dx + dz * dz) < NEAR_HIDE_CAP,
                    "waypoint " + i + " is outside the near-hide cap");
        }
    }

    @Test
    void installReplacesAPreviousGridInsteadOfStacking() {
        ActiveGroupManager manager = managerInZone("hub");
        PerfStressRoute.install(manager, 0.0, 65.0, 0.0);
        PerfStressRoute.install(manager, 500.0, 65.0, 500.0);

        int stressGroups = 0;
        for (WaypointGroup group : manager.allGroups()) {
            if (group.id().startsWith(PerfStressRoute.GROUP_ID_PREFIX)) stressGroups++;
        }
        assertEquals(1, stressGroups);
    }

    @Test
    void installWithoutAZoneIsANoOp() {
        ActiveGroupManager manager = new ActiveGroupManager();
        assertEquals(0, PerfStressRoute.install(manager, 0.0, 65.0, 0.0));
        assertEquals(0, manager.allGroups().size());
        assertEquals(0, PerfStressRoute.install(null, 0.0, 65.0, 0.0));
    }

    @Test
    void threeDimensionalProfileActuallyVariesHeightAndDepthMode() {
        ActiveGroupManager manager = managerInZone("hub");
        PerfStressRoute.Load load = new PerfStressRoute.Load(
                PerfStressRoute.Profile.GRID_3D, 256, 0);

        assertEquals(256, PerfStressRoute.install(manager, 0.0, 70.0, 0.0, load));
        WaypointGroup group = manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub");
        long heights = group.waypoints().stream().map(Waypoint::y).distinct().count();
        long depthChecked = group.waypoints().stream()
                .filter(point -> point.hasFlag(Waypoint.FLAG_DEPTH_CHECKED)).count();

        assertTrue(heights > 20, "3D profile should cover many vertical slices");
        assertTrue(depthChecked > 0 && depthChecked < group.size(),
                "profile should exercise both depth pipelines");
    }

    @Test
    void dungeonSecretProfileIncludesNamedSecretsAndSubwaypointHighlights() {
        ActiveGroupManager manager = managerInZone("hub");
        PerfStressRoute.Load load = new PerfStressRoute.Load(
                PerfStressRoute.Profile.DUNGEON_SECRETS, 12, 4);

        assertEquals(60, PerfStressRoute.install(manager, 0.0, 70.0, 0.0, load));
        WaypointGroup group = manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub");

        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
        assertEquals(12, group.mainWaypointCount());
        assertTrue(group.hasSubwaypoints());
        assertTrue(group.waypoints().stream().anyMatch(point -> point.name().contains("secret")));
    }

    @Test
    void denseSubwaypointProfileReachesTheRequestedTotal() {
        ActiveGroupManager manager = managerInZone("hub");
        PerfStressRoute.Load load = new PerfStressRoute.Load(
                PerfStressRoute.Profile.SUBWAYPOINTS_3D, 64, 31);

        assertEquals(2_048, load.totalWaypoints());
        assertEquals(2_048, PerfStressRoute.install(manager, 0.0, 70.0, 0.0, load));
        WaypointGroup group = manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub");
        assertEquals(WaypointGroup.LoadMode.STATIC, group.loadMode());
        assertEquals(64, group.mainWaypointCount());
    }

    @Test
    void totalWaypointCountChecksChildAdditionBeforeMultiplication() {
        PerfStressRoute.Load load = new PerfStressRoute.Load(
                PerfStressRoute.Profile.SUBWAYPOINTS_3D, 1, Integer.MAX_VALUE);

        assertThrows(ArithmeticException.class, load::totalWaypoints);
    }

    @Test
    void removeClearsTheGridButSparesNonTempGroupsWithThePrefixId() {
        ActiveGroupManager manager = managerInZone("hub");
        PerfStressRoute.install(manager, 0.0, 65.0, 0.0);

        WaypointGroup userGroup = new WaypointGroup(
                PerfStressRoute.GROUP_ID_PREFIX + "imposter", "User Route", "hub");
        userGroup.add(Waypoint.at(1, 65, 1));
        manager.add(userGroup);

        assertEquals(1, PerfStressRoute.remove(manager));
        assertNull(manager.get(PerfStressRoute.GROUP_ID_PREFIX + "hub"));
        assertNotNull(manager.get(PerfStressRoute.GROUP_ID_PREFIX + "imposter"));
        assertEquals(0, PerfStressRoute.remove(null));
    }
}
