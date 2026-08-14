package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteProgressTest {

    private static WaypointGroup route() {
        WaypointGroup group = WaypointGroup.create("route", "hub");
        for (int i = 0; i < 4; i++) {
            group.add(Waypoint.at(i * 10, 64, 0));
        }
        return group;
    }

    @Test
    void summaryHandlesAnEmptyRoute() {
        WaypointGroup group = WaypointGroup.create("route", "hub");

        assertEquals("0 pts", RouteProgress.summary(group));
    }

    @Test
    void summaryReportsCompletedMainWaypointsAndPercent() {
        WaypointGroup group = route();

        assertEquals("0/4 0.0%", RouteProgress.summary(group));

        group.advancePast(1);
        assertEquals("2/4 50.0%", RouteProgress.summary(group));

        group.advancePast(3);
        assertEquals("complete", RouteProgress.summary(group));
    }

    @Test
    void staticRouteProgressUsesSequenceAdvancementWithoutAReachChecklist() {
        WaypointGroup group = route();
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);

        group.advancePast(1);

        assertEquals("2/4 50.0%", RouteProgress.summary(group));
    }

    @Test
    void staticRouteProgressUsesTheReachChecklistWhenItIsActive() {
        WaypointGroup group = route();
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);

        group.markStaticWaypointReached(2);

        assertEquals("1/4 25.0%", RouteProgress.summary(group));
    }

    @Test
    void nextTargetLabelIncludesOrdinalAndPercent() {
        assertEquals("Next (1/4, 0.0%)", RouteProgress.nextTargetLabel(1, 4));
        assertEquals("Next (3/4, 50.0%)", RouteProgress.nextTargetLabel(3, 4));
    }

    @Test
    void disabledWaypointsDoNotCountAsRouteSteps() {
        WaypointGroup group = route();

        assertTrue(group.setWaypointDisabled(1, true));
        assertEquals("0/3 0.0%", RouteProgress.summary(group));

        group.advancePast(0);
        assertEquals(2, group.currentIndex());
        assertEquals("1/3 33.3%", RouteProgress.summary(group));
    }
}
