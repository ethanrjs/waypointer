package dev.ethan.waypointer.screen.settings;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(group.temp(), "stress grid must be temp so it never persists");
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
