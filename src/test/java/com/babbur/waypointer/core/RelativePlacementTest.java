package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativePlacementTest {

    @Test
    void firstWaypointLandsOnTheBlockAndEveryRouteKeepsItsShape() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(100, 50, -20).withName("1"));
        first.add(Waypoint.at(104, 53, -18).withName("2"));
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        second.add(Waypoint.at(90, 60, -30));
        Waypoint precise = Waypoint.at(101, 50, -20).withPreciseSixteenths(101 * 16 + 3, 50 * 16 + 8, -20 * 16 + 15);
        first.add(precise);

        assertTrue(RelativePlacement.placeFirstWaypointAt(List.of(first, second), 7, 70, 7));

        assertEquals(List.of(7, 70, 7), List.of(first.get(0).x(), first.get(0).y(), first.get(0).z()));
        assertEquals(List.of(11, 73, 9), List.of(first.get(1).x(), first.get(1).y(), first.get(1).z()));
        assertEquals("2", first.get(1).name());
        assertEquals(List.of(-3, 80, -3), List.of(second.get(0).x(), second.get(0).y(), second.get(0).z()));
        Waypoint moved = first.get(2);
        assertEquals(List.of(8 * 16 + 3, 70 * 16 + 8, 7 * 16 + 15),
                List.of(moved.preciseX(), moved.preciseY(), moved.preciseZ()), "sub-block position kept");
    }

    @Test
    void routesWithoutWaypointsAreLeftAlone() {
        WaypointGroup empty = WaypointGroup.create("Empty", "hub");
        assertFalse(RelativePlacement.placeFirstWaypointAt(List.of(empty), 0, 0, 0));
    }
}
