package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointGroupMainIndexTest {

    @Test
    void cachedMainWaypointLookupsMatchAFullScanThroughEveryKindOfEdit() {
        Random random = new Random(11);
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        for (int i = 0; i < 40; i++) group.add(Waypoint.at(i, 64, 0));

        for (int step = 0; step < 400; step++) {
            int size = group.size();
            switch (random.nextInt(7)) {
                case 0 -> group.toggleSubwaypoint(random.nextInt(size));
                case 1 -> { if (size > 2) group.remove(random.nextInt(size)); }
                case 2 -> group.insert(random.nextInt(size + 1), Waypoint.at(step, 70, 1));
                case 3 -> group.move(random.nextInt(size), random.nextInt(size));
                case 4 -> group.setWaypointDisabled(random.nextInt(size), random.nextBoolean());
                case 5 -> group.setWaypointStyle(random.nextInt(size), random.nextBoolean()
                        ? WaypointStyle.EMPTY : WaypointStyle.EMPTY.with("labelScale", 2));
                default -> group.add(Waypoint.at(step, 80, 2));
            }
            assertMatchesScan(group);
        }
    }

    private static void assertMatchesScan(WaypointGroup group) {
        int size = group.size();
        int mains = 0;
        for (int i = 0; i < size; i++) if (!group.get(i).isSubwaypoint()) mains++;
        assertEquals(mains, group.mainWaypointCount());
        assertEquals(mains < size, group.hasSubwaypoints());
        int anyFlags = 0;
        int everyFlags = ~0;
        int subwaypointFlags = 0;
        boolean styled = false;
        for (Waypoint waypoint : group.waypoints()) {
            anyFlags |= waypoint.flags();
            everyFlags &= waypoint.flags();
            if (waypoint.isSubwaypoint()) subwaypointFlags |= waypoint.flags();
            styled |= !waypoint.styleOverride().isEmpty();
        }
        assertEquals(styled, group.hasWaypointStyleOverrides());
        for (int flag : new int[]{Waypoint.FLAG_DISABLED, Waypoint.FLAG_SUBWAYPOINT}) {
            assertEquals((anyFlags & flag) != 0, group.anyWaypointHasFlag(flag));
            assertEquals((everyFlags & flag) == flag, group.everyWaypointHasFlag(flag));
            assertEquals((subwaypointFlags & flag) != 0, group.anySubwaypointHasFlag(flag));
        }
        for (int index = 0; index <= size + 1; index++) {
            int previous = -1;
            for (int i = Math.min(index - 1, size - 1); i >= 0; i--) {
                if (!group.get(i).isSubwaypoint()) { previous = i; break; }
            }
            assertEquals(previous, group.previousMainIndexBefore(index), "previous main before " + index);
            if (index >= size) continue;
            int main = group.get(index).isSubwaypoint() ? previous : index;
            int ordinal = 0;
            if (main >= 0) for (int i = 0; i <= main; i++) if (!group.get(i).isSubwaypoint()) ordinal++;
            assertEquals(ordinal, group.mainOrdinal(index), "ordinal of " + index);
        }
    }
}
