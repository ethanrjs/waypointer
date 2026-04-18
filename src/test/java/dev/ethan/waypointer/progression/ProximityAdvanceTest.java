package dev.ethan.waypointer.progression;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused on the progression edge cases that are easy to regress:
 * skipping ahead, staying put when out of range, and not walking off the end.
 */
class ProximityAdvanceTest {

    private static WaypointGroup line() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(2.0);
        g.add(Waypoint.at(0, 0, 0));
        g.add(Waypoint.at(10, 0, 0));
        g.add(Waypoint.at(20, 0, 0));
        g.add(Waypoint.at(30, 0, 0));
        return g;
    }

    @Test
    void out_of_range_does_not_advance() {
        WaypointGroup g = line();
        assertFalse(ProximityAdvanceTester(g, 5.0, 0.0, 0.0));
        assertEquals(0, g.currentIndex());
    }

    @Test
    void reaching_current_advances_one() {
        WaypointGroup g = line();
        assertTrue(ProximityAdvanceTester(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void reaching_a_future_waypoint_skips_ahead() {
        WaypointGroup g = line();
        assertTrue(ProximityAdvanceTester(g, 20.5, 0.5, 0.5));
        assertEquals(3, g.currentIndex()); // jumped from 0 straight past index 2
    }

    @Test
    void reaching_last_marks_complete() {
        WaypointGroup g = line();
        assertTrue(ProximityAdvanceTester(g, 30.5, 0.5, 0.5));
        assertTrue(g.isComplete());
    }

    @Test
    void reaching_last_loops_to_start_when_restart_enabled() {
        WaypointGroup g = line();
        assertTrue(ProximityTracker.advanceIfReached(g, 30.5, 0.5, 0.5, true));
        assertEquals(0, g.currentIndex());
        assertFalse(g.isComplete());
    }

    @Test
    void ignores_waypoints_before_current() {
        WaypointGroup g = line();
        g.setCurrentIndex(2);
        assertFalse(ProximityAdvanceTester(g, 0.5, 0.5, 0.5)); // index 0 should not count
        assertEquals(2, g.currentIndex());
    }

    @Test
    void respects_custom_radius_on_waypoint() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(1.0);
        g.add(Waypoint.at(0, 0, 0).withRadius(10.0));
        assertTrue(ProximityAdvanceTester(g, 7.0, 0.0, 0.0));
        assertTrue(g.isComplete());
    }

    @Test
    void skip_ahead_disabled_only_advances_current() {
        // With proximity skip-ahead off, standing next to a far-future waypoint
        // must NOT jump progression. The only legal advance is when the player is
        // within range of the waypoint they're actually on (index 0 here).
        WaypointGroup g = line();
        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(0, g.currentIndex());

        // Walking up to the current waypoint still advances by one, same as the
        // enabled case -- disabling skip-ahead must not also disable normal
        // progression.
        assertTrue(ProximityTracker.advanceIfReached(g, 0.5, 0.5, 0.5, false, false));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void reach_mode_temp_is_removed_on_advance() {
        // TEMP_UNTIL_REACHED waypoints should vanish the moment the proximity
        // tracker advances past them -- anything else would leave a stale temp
        // entry hanging around the list after its lifecycle ended.
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(2.0);
        g.add(Waypoint.at(0, 0, 0).withTemp(Waypoint.TEMP_UNTIL_REACHED, 0L));
        g.add(Waypoint.at(10, 0, 0));

        assertTrue(ProximityAdvanceTester(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.size()); // temp was removed
        // After the temp was removed, currentIndex was originally advanced to 1
        // but the list shrank; the remaining waypoint should now be index 0.
        assertEquals(Waypoint.TEMP_NONE, g.get(0).tempMode());
    }

    private static boolean ProximityAdvanceTester(WaypointGroup g, double px, double py, double pz) {
        return ProximityTracker.advanceIfReached(g, px, py, pz);
    }
}
