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
        assertFalse(advanceIfReached(g, 5.0, 0.0, 0.0));
        assertEquals(0, g.currentIndex());
    }

    @Test
    void reaching_current_advances_one() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.currentIndex());
    }

    @Test
    void reaching_a_future_waypoint_skips_ahead() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));
        assertEquals(3, g.currentIndex()); // jumped from 0 straight past index 2
    }

    @Test
    void reaching_last_marks_complete() {
        WaypointGroup g = line();
        assertTrue(advanceIfReached(g, 30.5, 0.5, 0.5));
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
    void reaching_last_parentWithSubwaypoints_loopsTracerButKeepsVisualHold() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(3);

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, true));

        assertEquals(0, g.currentIndex(), "tracer target should wrap back to waypoint 1");
        assertEquals(2, g.activeSubwaypointParentIndex(),
                "last waypoint and its subwaypoints should stay visible after the wrap");
    }

    @Test
    void ignores_waypoints_before_current() {
        WaypointGroup g = line();
        g.setCurrentIndex(2);
        assertFalse(advanceIfReached(g, 0.5, 0.5, 0.5)); // index 0 should not count
        assertEquals(2, g.currentIndex());
    }

    @Test
    void respects_custom_radius_on_waypoint() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setDefaultRadius(1.0);
        g.add(Waypoint.at(0, 0, 0).withRadius(10.0));
        assertTrue(advanceIfReached(g, 7.0, 0.0, 0.0));
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

    /*[[AI-FN-DOC
Function:
skipAheadVisibleOnlyDoesNotJumpToHiddenFutureWaypoint
Purpose:
Verify automatic skip-ahead respects the visible route-context cap when enabled.
Why this exists:
The default skip behavior should not advance to far-future waypoints that are not currently visible in contextual sequence rendering.
When to use:
Run with progression tests after changing skip-ahead visibility filtering or WaypointGroup.forEachVisibleIndex behavior.
Inputs:
No parameters. Builds a four-point line route with default sequence visibility.
Outputs:
No return value. Assertions fail if hidden future waypoints can still be skipped to under visible-only mode.
Side effects:
Mutates only the test route's in-memory currentIndex.
Failure modes:
Fails if visible-only filtering is ignored or if current index changes unexpectedly.
Important invariants:
Index 2 is reachable by distance but not visible from current index 0 in a simple sequence route, so it must not count.
Internal logic:
Create route, stand near index 2, call advanceIfReached with skip-ahead and visible-only both true, then assert no advance happened.
Pseudocode:
g = line route
advanced = advanceIfReached at waypoint 3 position with restart false, allow skip true, visible only true
assert advanced is false
assert currentIndex remains 0
Implementation notes:
This isolates the new cap from the legacy skip-ahead test, which still expects far-future jumping when visible-only is false.
AI self-check:
Confirm the coordinates are inside index 2 radius and outside index 0/1 radius.
]]*/
    @Test
    void skipAheadVisibleOnlyDoesNotJumpToHiddenFutureWaypoint() {
        WaypointGroup g = line();

        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5,
                false, true, true));

        assertEquals(0, g.currentIndex());
    }

    /*[[AI-FN-DOC
Function:
skipAheadVisibleOnlyCanAdvanceToVisibleNextWaypoint
Purpose:
Verify the visible-only skip cap still allows automatic progression to a visible next waypoint.
Why this exists:
The setting should prevent invisible far-future jumps without breaking the normal contextual one-ahead route flow.
When to use:
Run with progression tests after changing visibility filtering or sequence route advancement.
Inputs:
No parameters. Builds a four-point route and starts at the first point.
Outputs:
No return value. Assertions fail if visible next waypoint progression stops working.
Side effects:
Mutates the test route currentIndex.
Failure modes:
Fails if the visible mask omits the next main waypoint or if skip-ahead ignores valid visible candidates.
Important invariants:
From current index 0, index 1 is visible and should be eligible for skip-ahead when reached.
Internal logic:
Create route, stand near index 1, run advance with visible-only skip enabled, and assert the route advances past index 1.
Pseudocode:
g = line route
advanced = advanceIfReached near waypoint 2 with allow skip true and visible only true
assert advanced true
assert currentIndex equals 2
Implementation notes:
This catches overly strict filtering that would reduce skip-ahead to current-only behavior.
AI self-check:
Confirm index 1 is the visible next waypoint in WaypointGroup.forEachVisibleIndex.
]]*/
    @Test
    void skipAheadVisibleOnlyCanAdvanceToVisibleNextWaypoint() {
        WaypointGroup g = line();

        assertTrue(ProximityTracker.advanceIfReached(g, 10.5, 0.5, 0.5,
                false, true, true));

        assertEquals(2, g.currentIndex());
    }

    /*[[AI-FN-DOC
Function:
explicitSubwaypointTargetAdvancesWhenReached
Purpose:
Verify a subwaypoint selected by an explicit command-style target can become the current route target and advance when reached.
Why this exists:
/wp skipto supports decimal labels like 1.1, so progression must honor that exact child target instead of normalizing back to the parent.
When to use:
Run with progression tests after changing WaypointGroup current target handling or ProximityTracker current waypoint checks.
Inputs:
No parameters. Builds a route where index 1 is a subwaypoint under index 0.
Outputs:
No return value. Assertions fail if current targeting or child advancement regresses.
Side effects:
Mutates the test route currentIndex and active subwaypoint parent state.
Failure modes:
Fails if setCurrentTargetIndex normalizes children away, if currentReachedIndex rejects subwaypoints, or if advancePast skips incorrectly.
Important invariants:
Explicit child targets are allowed, but automatic far-future child skip-ahead remains separate.
Internal logic:
Create route, convert index 1 to a subwaypoint, set current target to index 1, stand inside its radius, then assert route advances to the next main waypoint.
Pseudocode:
g = line route
toggle index 1 into subwaypoint
set current target index 1
assert currentIndex is 1
advance near index 1 with skip-ahead enabled
assert advanced true
assert currentIndex is 2
Implementation notes:
This models /wp skipto 1.1 without depending on Brigadier command plumbing.
AI self-check:
Confirm the test checks both target preservation and advancement.
]]*/
    @Test
    void explicitSubwaypointTargetAdvancesWhenReached() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);
        g.setCurrentTargetIndex(1);

        assertEquals(1, g.currentIndex());
        assertTrue(ProximityTracker.advanceIfReached(g, 10.5, 0.5, 0.5,
                false, true, true));

        assertEquals(2, g.currentIndex());
    }

    @Test
    void subwaypoints_doNotAdvanceRouteProgress() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertFalse(advanceIfReached(g, 10.5, 0.5, 0.5));

        assertEquals(0, g.currentIndex());
    }

    @Test
    void reaching_parentWithSubwaypoints_advancesTracerTargetButLeavesVisualHold() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(2, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));
        assertEquals(3, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void strictProgression_parentWithSubwaypoints_advancesTracerTarget() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(ProximityTracker.advanceIfReached(g, 0.5, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(3, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void skipAhead_ignoresSubwaypointsButStillFindsFutureMainWaypoints() {
        WaypointGroup g = line();
        g.toggleSubwaypoint(1);

        assertTrue(advanceIfReached(g, 20.5, 0.5, 0.5));

        assertEquals(3, g.currentIndex());
    }

    @Test
    void staticReachTracking_ignoresSubwaypoints() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.toggleSubwaypoint(1);

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 10.5, 0.5, 0.5));

        assertFalse(g.isStaticWaypointReached(1));
        assertEquals(0, g.currentIndex());
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

        assertTrue(advanceIfReached(g, 0.5, 0.5, 0.5));
        assertEquals(1, g.size()); // temp was removed
        // After the temp was removed, currentIndex was originally advanced to 1
        // but the list shrank; the remaining waypoint should now be index 0.
        assertEquals(Waypoint.TEMP_NONE, g.get(0).tempMode());
    }

    @Test
    void freshly_focused_waypoint_is_not_immediately_advanced() {
        WaypointGroup g = line();
        g.focusNewWaypoint(2);

        assertFalse(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());

        assertFalse(ProximityTracker.advanceIfReached(g, 25.0, 0.5, 0.5, false, false));
        assertEquals(2, g.currentIndex());

        assertTrue(ProximityTracker.advanceIfReached(g, 20.5, 0.5, 0.5, false, false));
        assertEquals(3, g.currentIndex());
    }

    @Test
    void static_reach_tracking_hides_visited_waypoints_without_advancing_route() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));

        assertEquals(0, g.currentIndex());
        assertFalse(g.isStaticWaypointReached(0));
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void static_progress_update_does_not_run_sequence_advancement() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        ProximityTracker.updateGroupProgress(g, 20.5, 0.5, 0.5,
                false, true, true);

        assertEquals(0, g.currentIndex());
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void freshly_focused_static_waypoint_is_not_hidden_until_player_leaves_and_returns() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.focusNewWaypoint(2);

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertFalse(g.isStaticWaypointReached(2));

        assertFalse(ProximityTracker.markReachedStaticWaypoints(g, 25.0, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertTrue(g.isStaticWaypointReached(2));
    }

    @Test
    void static_reach_tracking_resets_after_every_waypoint_is_reached() {
        WaypointGroup g = line();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 0.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 10.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 20.5, 0.5, 0.5));
        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 30.5, 0.5, 0.5));

        for (int i = 0; i < g.size(); i++) {
            assertFalse(g.isStaticWaypointReached(i));
        }
    }

    @Test
    void static_cycle_reset_does_not_remark_later_indices_same_tick() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);
        g.setDefaultRadius(6.0);
        g.add(Waypoint.at(0, 0, 0));
        g.add(Waypoint.at(10, 0, 0));
        g.add(Waypoint.at(20, 0, 0));
        g.add(Waypoint.at(30, 0, 0));
        g.add(Waypoint.at(40, 0, 0));

        g.markStaticWaypointReached(0);
        g.markStaticWaypointReached(1);
        g.markStaticWaypointReached(2);

        assertTrue(ProximityTracker.markReachedStaticWaypoints(g, 35.5, 0.5, 0.5));

        for (int i = 0; i < g.size(); i++) {
            assertFalse(g.isStaticWaypointReached(i), "index " + i);
        }
    }

    private static boolean advanceIfReached(WaypointGroup g, double px, double py, double pz) {
        return ProximityTracker.advanceIfReached(g, px, py, pz);
    }
}
