package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaypointRouteOptimizerTest {

    private static Waypoint at(int x, int z) {
        return Waypoint.at(x, 64, z);
    }

    private static String coords(WaypointGroup group) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) out.append(';');
            Waypoint waypoint = group.get(i);
            out.append(waypoint.x()).append(',').append(waypoint.z());
        }
        return out.toString();
    }

    @Test
    void nearestNeighborReordersRouteFromSelectedStart() {
        WaypointGroup group = WaypointGroup.create("route", "hub");
        group.add(at(0, 0));
        group.add(at(100, 0));
        group.add(at(10, 0));
        group.add(at(20, 0));

        WaypointRouteOptimizer.Result result =
                WaypointRouteOptimizer.optimizeNearestNeighbor(group, 0);

        assertTrue(result.changed);
        assertEquals(0, result.selectedIndex);
        assertEquals("0,0;10,0;20,0;100,0", coords(group));
        assertEquals(0, group.currentIndex());
    }

    @Test
    void optimizerPreservesSubwaypointBlocks() {
        WaypointGroup group = WaypointGroup.create("route", "hub");
        group.add(at(0, 0));
        group.add(at(100, 0));
        group.add(at(101, 0));
        group.add(at(10, 0));
        assertTrue(group.toggleSubwaypoint(2));

        WaypointRouteOptimizer.Result result =
                WaypointRouteOptimizer.optimizeNearestNeighbor(group, 0);

        assertTrue(result.changed);
        assertEquals("0,0;10,0;100,0;101,0", coords(group));
        assertTrue(group.isSubwaypoint(3));
        assertEquals(2, group.parentMainIndex(3));
    }
}
