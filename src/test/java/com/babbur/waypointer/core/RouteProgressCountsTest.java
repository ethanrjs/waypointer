package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The prefix counts that replaced per-label route scans must match the scans they replaced. */
class RouteProgressCountsTest {

    @Test
    void prefixCountsMatchAFullScanOnRandomRoutes() {
        Random random = new Random(0x50C0);
        for (int trial = 0; trial < 200; trial++) {
            WaypointGroup group = WaypointGroup.create("Route " + trial, "hub");
            int size = random.nextInt(40);
            for (int i = 0; i < size; i++) {
                int flags = 0;
                if (i > 0 && random.nextInt(4) == 0) flags |= Waypoint.FLAG_SUBWAYPOINT;
                if (random.nextInt(5) == 0) flags |= Waypoint.FLAG_DISABLED;
                group.add(new Waypoint(i, 70, i, "", 0xFFFFFF, flags, 0.0));
            }
            group.setLoadMode(random.nextBoolean()
                    ? WaypointGroup.LoadMode.STATIC : WaypointGroup.LoadMode.SEQUENCE);
            for (int i = 0; i < size; i++) if (random.nextInt(3) == 0) group.markStaticWaypointReached(i);
            if (size > 0 && random.nextBoolean()) group.setWaypointDisabled(random.nextInt(size), random.nextBoolean());

            assertEquals(scanEnabledMains(group, size), group.enabledMainWaypointCount());
            for (int index = -1; index <= size + 1; index++) {
                assertEquals(scanEnabledMains(group, index), group.enabledMainsBefore(index), "before " + index);
            }
            assertEquals(scanReached(group), group.staticReachedMainCount());
            assertEquals(scanCurrentOrdinal(group), group.currentMainOrdinal());
        }
    }

    @Test
    void reachedCountFollowsNewReachesAndResets() {
        WaypointGroup group = WaypointGroup.create("Static", "hub");
        for (int i = 0; i < 4; i++) group.add(new Waypoint(i, 70, i, "", 0xFFFFFF, 0, 0.0));
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        assertEquals(0, group.staticReachedMainCount());
        group.markStaticWaypointReached(1);
        assertEquals(1, group.staticReachedMainCount());
        group.markStaticWaypointReached(2);
        assertEquals(2, group.staticReachedMainCount());
        group.resetStaticReachState();
        assertEquals(0, group.staticReachedMainCount());
        assertEquals(0, RouteProgress.snapshot(group).completed);
    }

    private static int scanEnabledMains(WaypointGroup group, int before) {
        int count = 0;
        for (int i = 0; i < Math.min(before, group.size()); i++) {
            if (!group.isSubwaypoint(i) && group.isWaypointEnabled(i)) count++;
        }
        return count;
    }

    private static int scanReached(WaypointGroup group) {
        int count = 0;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i) || group.isWaypointDisabled(i)) continue;
            if (group.isStaticWaypointReached(i)) count++;
        }
        return count;
    }

    private static int scanCurrentOrdinal(WaypointGroup group) {
        int current = group.currentMainIndex();
        if (current < 0) return scanEnabledMains(group, group.size());
        return scanEnabledMains(group, current + 1);
    }
}
