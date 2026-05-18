package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointVisibilityTest {

    @Test
    void nearPlayerVisibilityUsesWaypointCenterAndConfiguredRadius() {
        Waypoint waypoint = Waypoint.at(10, 64, -4);
        double radiusSq = WaypointVisibility.squaredRadius(5.0);

        assertTrue(WaypointVisibility.isHiddenNearPlayer(
                waypoint, 10.5, 64.5, -3.5, radiusSq));
        assertTrue(WaypointVisibility.isHiddenNearPlayer(
                waypoint, 15.5, 64.5, -3.5, radiusSq));
        assertFalse(WaypointVisibility.isHiddenNearPlayer(
                waypoint, 15.6, 64.5, -3.5, radiusSq));
    }

    @Test
    void disabledOrInvalidRadiusNeverHides() {
        Waypoint waypoint = Waypoint.at(0, 0, 0);

        assertFalse(WaypointVisibility.isHiddenNearPlayer(
                waypoint, 0.5, 0.5, 0.5, WaypointVisibility.squaredRadius(0.0)));
        assertFalse(WaypointVisibility.isHiddenNearPlayer(
                waypoint, 0.5, 0.5, 0.5, WaypointVisibility.squaredRadius(Double.NaN)));
    }
}
