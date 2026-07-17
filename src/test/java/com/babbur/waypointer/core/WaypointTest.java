package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointTest {

    @Test
    void snapToPreciseSixteenthsUsesContainingTextureCell() {
        assertEquals(0, Waypoint.snapToPreciseSixteenths(0.0));
        assertEquals(0, Waypoint.snapToPreciseSixteenths(1.0 / 32.0));
        assertEquals(1, Waypoint.snapToPreciseSixteenths(1.0 / 16.0));
        assertEquals(-1, Waypoint.snapToPreciseSixteenths(-0.001));
    }

    @Test
    void tinySubwaypointCenterUsesTextureCellCenter() {
        int flags = Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT;
        Waypoint tiny = new Waypoint(10, 64, -5,
                "", Waypoint.DEFAULT_COLOR, flags, 0.0,
                Waypoint.TEMP_NONE, 0L,
                10 * Waypoint.PRECISE_SCALE,
                64 * Waypoint.PRECISE_SCALE,
                -5 * Waypoint.PRECISE_SCALE);
        Waypoint normal = new Waypoint(10, 64, -5,
                "", Waypoint.DEFAULT_COLOR, 0, 0.0);

        assertEquals(10.03125, tiny.centerX(), 0.0001);
        assertEquals(64.03125, tiny.centerY(), 0.0001);
        assertEquals(-4.96875, tiny.centerZ(), 0.0001);
        assertEquals(10.5, normal.centerX(), 0.0001);
        assertEquals(64.5, normal.centerY(), 0.0001);
        assertEquals(-4.5, normal.centerZ(), 0.0001);
    }

    @Test
    void customRadiusIsAlwaysFiniteAndBounded() {
        assertEquals(0.0, Waypoint.at(0, 0, 0).withRadius(Double.NaN).customRadius());
        assertEquals(0.0, Waypoint.at(0, 0, 0).withRadius(Double.POSITIVE_INFINITY).customRadius());
        assertEquals(0.0, Waypoint.at(0, 0, 0).withRadius(-1.0).customRadius());
        assertEquals(Waypoint.MAX_REACH_RADIUS,
                Waypoint.at(0, 0, 0).withRadius(1_000_000.0).customRadius());
    }
}
