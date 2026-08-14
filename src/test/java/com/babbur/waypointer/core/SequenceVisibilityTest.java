package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceVisibilityTest {
    @Test
    void supportsIndependentPreviousCurrentAndNextCounts() {
        WaypointGroup group = route(6);
        group.setCurrentIndex(3);
        List<Integer> visible = new ArrayList<>();

        group.forEachVisibleIndex(new SequenceVisibility(2, false, 2), visible::add);

        assertEquals(List.of(1, 2, 4, 5), visible);
    }

    @Test
    void countsOnlyEnabledFutureMains() {
        WaypointGroup group = route(5);
        group.setWaypointDisabled(1, true);
        group.setWaypointDisabled(3, true);
        List<Integer> finite = new ArrayList<>();

        group.forEachVisibleIndex(new SequenceVisibility(0, true, 2), finite::add);

        assertEquals(List.of(0, 2, 4), finite);
    }

    @Test
    void allPreviousDoesNotTruncateRoutesPastFiniteLimit() {
        WaypointGroup group = route(80);
        group.setCurrentIndex(79);
        List<Integer> visible = new ArrayList<>();

        group.forEachVisibleIndex(new SequenceVisibility(
                SequenceVisibility.ALL, true, 32), visible::add);

        assertEquals(80, visible.size());
        assertEquals(0, visible.get(0));
        assertEquals(79, visible.get(visible.size() - 1));
    }

    @Test
    void nextCountNeverExceedsThirtyTwo() {
        SequenceVisibility visibility = new SequenceVisibility(
                0, true, SequenceVisibility.ALL);

        assertEquals(SequenceVisibility.MAX_CONTEXT_WAYPOINTS, visibility.next());
    }

    private static WaypointGroup route(int size) {
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        for (int i = 0; i < size; i++) group.add(Waypoint.at(i, 64, 0));
        return group;
    }
}
