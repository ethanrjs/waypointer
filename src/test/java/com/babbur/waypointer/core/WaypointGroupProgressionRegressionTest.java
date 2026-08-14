package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointGroupProgressionRegressionTest {
    @Test
    void retreatAcrossDisabledChildNeverNormalizesForward() {
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.add(Waypoint.at(0, 64, 0));
        group.add(Waypoint.at(1, 64, 0));
        group.add(Waypoint.at(2, 64, 0));
        group.toggleSubwaypoint(1);
        group.toggleSubwaypoint(2);
        group.setWaypointDisabled(1, true);
        group.setCurrentTargetIndex(2);

        assertTrue(group.retreatToPreviousTarget());

        assertEquals(0, group.currentIndex());
    }

    @Test
    void dungeonRetreatSkipsRenderOnlyPearlTargets() {
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.add(Waypoint.at(0, 64, 0));
        group.add(Waypoint.at(1, 64, 0).withFlags(
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_DUNGEON_PEARL_TARGET));
        group.add(Waypoint.at(2, 64, 0).withFlags(
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SKIP_ON_INTERACT));
        group.add(Waypoint.at(3, 64, 0));
        group.setCurrentTargetIndex(2);

        assertTrue(group.retreatToPreviousTarget());

        assertEquals(0, group.currentIndex(),
                "render-only highlights cannot become progression targets");
    }

    @Test
    void disablingLastUnreachedStaticWaypointCompletesAndResetsCycle() {
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.add(Waypoint.at(0, 64, 0));
        group.add(Waypoint.at(1, 64, 0));
        group.add(Waypoint.at(2, 64, 0));
        group.markStaticWaypointReached(0);
        group.markStaticWaypointReached(1);

        assertTrue(group.setWaypointDisabled(2, true));

        assertTrue(group.consumeStaticCycleJustCompleted());
        assertFalse(group.isStaticWaypointReached(0));
        assertFalse(group.isStaticWaypointReached(1));
    }

    @Test
    void movingMainBlockUsesPhysicallyAdjacentDisabledMain() {
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.add(Waypoint.at(0, 64, 0).withName("A"));
        group.add(Waypoint.at(1, 64, 0).withName("B"));
        group.add(Waypoint.at(2, 64, 0).withName("C"));
        group.setWaypointDisabled(1, true);

        assertEquals(1, group.moveBy(0, 1));
        assertEquals(List.of("B", "A", "C"),
                group.waypoints().stream().map(Waypoint::name).toList());
    }
}
