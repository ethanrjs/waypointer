package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointGroupTest {

    private static Waypoint wp(int x, int y, int z) {
        return Waypoint.at(x, y, z);
    }

    private static WaypointGroup route() {
        WaypointGroup g = WaypointGroup.create("route", "dungeon_f7");
        g.add(wp(0, 70, 0));
        g.add(wp(10, 70, 0));
        g.add(wp(20, 70, 0));
        g.add(wp(30, 70, 0));
        return g;
    }

    @Test
    void insert_shiftsCurrentIndexWhenInsertingBeforeIt() {
        WaypointGroup g = route();
        g.advancePast(1);                 // currentIndex = 2
        g.insert(0, wp(-10, 70, 0));      // insert at 0 shifts everything right
        assertEquals(3, g.currentIndex());
        assertEquals(5, g.size());
    }

    @Test
    void remove_decrementsCurrentWhenRemovingBeforeIt() {
        WaypointGroup g = route();
        g.advancePast(2);                 // currentIndex = 3
        g.remove(0);
        assertEquals(2, g.currentIndex());
        assertEquals(3, g.size());
    }

    @Test
    void remove_clampsToEndWhenRemovingTail() {
        WaypointGroup g = route();
        g.advancePast(2);                 // currentIndex = 3 (the last one)
        g.remove(3);                      // remove the current one
        assertEquals(3, g.currentIndex()); // clamped to size()
        assertTrue(g.isComplete());
    }

    @Test
    void move_currentIndexFollowsMovedWaypoint() {
        WaypointGroup g = route();
        g.advancePast(0);                 // currentIndex = 1 -> points at (10,70,0)
        g.move(1, 3);                     // that waypoint moves to end
        assertEquals(3, g.currentIndex());
        Waypoint now = g.current();
        assertNotNull(now);
        assertEquals(10, now.x());
    }

    @Test
    void move_currentIndexAdjustsWhenJumpingOverIt() {
        WaypointGroup g = route();
        g.advancePast(1);                 // currentIndex = 2
        g.move(0, 3);                     // moves first element past current
        assertEquals(1, g.currentIndex()); // current waypoint is now at position 1
    }

    @Test
    void advancePast_monotonic() {
        WaypointGroup g = route();
        g.advancePast(2);
        g.advancePast(1);                 // should NOT go backward
        assertEquals(3, g.currentIndex());
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget_movesBackOneMainWaypoint
Purpose:
Verify route progress can move back from a later main waypoint to the immediately previous main waypoint.
Why this exists:
The Previous Waypoint keybind depends on WaypointGroup.retreatToPreviousTarget for the normal inverse of skipping a current waypoint.
When to use:
Run with core waypoint group tests whenever route progress, skip, or keybind backtracking behavior changes.
Inputs:
No runtime inputs; builds an in-memory four-waypoint route and advances it to index 2.
Outputs:
Assertions pass when retreat returns true, currentIndex becomes 1, and no active subwaypoint hold remains.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if retreat is a no-op from a middle route step, skips too far backward, or leaves stale subwaypoint hold state.
Important invariants:
Main-waypoint retreat walks by main waypoint, not by physical list mutation or proximity state.
Internal logic:
Create a route, advance past index 1 so currentIndex is 2, retreat once, then assert the new progress target.
Pseudocode:
g = route
g.advancePast(1)
moved = g.retreatToPreviousTarget
assert moved is true
assert currentIndex equals 1
assert activeSubwaypointParentIndex equals -1
Implementation notes:
This is intentionally narrow because subwaypoint and completion variants are covered by separate tests.
AI self-check:
Verify the test exercises the same group method the keybind calls.
]]*/
    @Test
    void retreatToPreviousTarget_movesBackOneMainWaypoint() {
        WaypointGroup g = route();
        g.advancePast(1);

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(1, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget_returnsFalseAtFirstWaypoint
Purpose:
Verify retreat reports a no-op when the route is already at its first waypoint.
Why this exists:
The keybind needs a reliable false return so it can show "nothing to go back to" feedback instead of saving unchanged route data.
When to use:
Run with core route progress tests after changing retreat guards or first-waypoint normalization.
Inputs:
No runtime inputs; builds a fresh in-memory route at its initial currentIndex.
Outputs:
Assertions pass when retreat returns false and currentIndex remains 0.
Side effects:
Mutates only the local test route if the implementation is wrong; the expected behavior has no mutation.
Failure modes:
Fails if retreat underflows progress or claims a successful move at the route start.
Important invariants:
The first waypoint has no previous target.
Internal logic:
Create a route, call retreat immediately, then assert false and unchanged progress.
Pseudocode:
g = route
moved = g.retreatToPreviousTarget
assert moved is false
assert currentIndex equals 0
Implementation notes:
This protects the keybind from firing manager.fireDataChanged for no-op presses at the start of a route.
AI self-check:
Verify the test does not depend on any active manager state.
]]*/
    @Test
    void retreatToPreviousTarget_returnsFalseAtFirstWaypoint() {
        WaypointGroup g = route();

        assertFalse(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget_fromCompleteRouteTargetsLastMainWaypoint
Purpose:
Verify a route that has been skipped or advanced past its end can move back to the final main waypoint.
Why this exists:
Previous Waypoint should feel like an inverse of Skip Waypoint even after Skip Waypoint completes the route.
When to use:
Run with route completion and keybind progress tests when changing completion, restart, or retreat behavior.
Inputs:
No runtime inputs; builds a route and advances past the last waypoint.
Outputs:
Assertions pass when the route starts complete, retreat returns true, the route is no longer complete, and currentIndex points at the final waypoint.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if completed routes are treated as unable to retreat or retreat to the wrong target.
Important invariants:
Completion is represented by currentIndex == size, and retreat from that state should choose the last main waypoint.
Internal logic:
Create a route, advance past the final index, assert completion, retreat, then assert the final waypoint is current.
Pseudocode:
g = route
g.advancePast(3)
assert g is complete
moved = g.retreatToPreviousTarget
assert moved is true
assert g is not complete
assert currentIndex equals 3
Implementation notes:
The keybind intentionally does not filter complete groups before calling retreat, and this test locks in why.
AI self-check:
Verify restartRouteWhenComplete is not involved in this test.
]]*/
    @Test
    void retreatToPreviousTarget_fromCompleteRouteTargetsLastMainWaypoint() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());

        assertTrue(g.retreatToPreviousTarget());

        assertFalse(g.isComplete());
        assertEquals(3, g.currentIndex());
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget_walksExplicitSubwaypointTargetsBackward
Purpose:
Verify exact subwaypoint targets retreat through sibling children and then back to their parent.
Why this exists:
Skip-to commands can make currentIndex point at subwaypoints directly, and the keybind should reverse that exact target sequence instead of normalizing straight to main-waypoint progress.
When to use:
Run with subwaypoint route progress tests whenever advancePast, setCurrentTargetIndex, or retreat behavior changes.
Inputs:
No runtime inputs; builds an in-memory route and turns indices 1 and 2 into subwaypoints under index 0.
Outputs:
Assertions pass when retreat from child 2 targets child 1 exactly with parent lookup intact, then retreat from child 1 targets parent 0 and clears visible hold.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if retreat normalizes subwaypoints too early, skips sibling children, loses parent lookup for the child, or leaves an incorrect visible hold on the parent.
Important invariants:
Exact subwaypoint current targets are allowed only through setCurrentTargetIndex-style progress, and retreat must preserve that exactness for child-to-child movement.
Internal logic:
Create a route, mark two subwaypoints, target the second child exactly, retreat once and assert child 1, retreat again and assert parent 0.
Pseudocode:
g = route
toggle indices 1 and 2 to subwaypoints
set exact current target to index 2
assert retreat returns true
assert currentIndex equals 1, current equals waypoint 1, parentMainIndex equals 0, and visible active parent accessor equals -1
assert retreat returns true
assert currentIndex equals 0 and active parent equals -1
Implementation notes:
This test uses setCurrentTargetIndex because normal setCurrentIndex intentionally canonicalizes subwaypoints back to their parent.
AI self-check:
Verify the route's first waypoint remains a main waypoint.
]]*/
    @Test
    void retreatToPreviousTarget_walksExplicitSubwaypointTargetsBackward() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.setCurrentTargetIndex(2);

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(1, g.currentIndex());
        assertEquals(g.get(1), g.current());
        assertEquals(0, g.parentMainIndex(g.currentIndex()));
        assertEquals(-1, g.activeSubwaypointParentIndex());

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    /*[[AI-FN-DOC
Function:
retreatToPreviousTarget_fromHeldSubwaypointParentReturnsToThatParent
Purpose:
Verify retreat from the visual-hold state created by skipping a parent with subwaypoints returns to that held parent.
Why this exists:
Skipping a main waypoint with subwaypoints advances the tracer to the next main while keeping the skipped parent visually active, and Previous Waypoint should undo that exact user-visible skip.
When to use:
Run with route rendering/progress tests when changing activeSubwaypointParentIndex, sequence visibility, or retreat behavior.
Inputs:
No runtime inputs; builds a sequence-mode route with two subwaypoints under the first parent.
Outputs:
Assertions pass when advancePast creates the hold and retreat targets the held parent while clearing the hold.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if retreat ignores activeSubwaypointParentIndex and returns to some other previous target.
Important invariants:
The held parent is a visual context for the current next-main target and is the correct inverse target for a back key press.
Internal logic:
Create a sequence route, add subwaypoints, advance past the parent, assert the hold, retreat, then assert currentIndex is the parent and hold is cleared.
Pseudocode:
g = route
set load mode to sequence
toggle indices 1 and 2 to subwaypoints
advance past index 0
assert currentIndex equals 3 and active parent equals 0
assert retreat returns true
assert currentIndex equals 0
assert active parent equals -1
Implementation notes:
The visible-index assertion is covered elsewhere; this test focuses on progress state returned by the group method.
AI self-check:
Verify this covers the state produced by Skip Waypoint on a subwaypoint parent.
]]*/
    @Test
    void retreatToPreviousTarget_fromHeldSubwaypointParentReturnsToThatParent() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.advancePast(0);
        assertEquals(3, g.currentIndex());
        assertEquals(0, g.activeSubwaypointParentIndex());

        assertTrue(g.retreatToPreviousTarget());

        assertEquals(0, g.currentIndex());
        assertEquals(-1, g.activeSubwaypointParentIndex());
    }

    @Test
    void focusNewWaypoint_setsCurrentAndSuppressesProximity() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.focusNewWaypoint(2);

        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    @Test
    void moveWaypointTo_focusesMovedWaypointAndClearsStaticReachState() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.moveWaypointTo(2, 25, 71, -4);

        Waypoint moved = g.get(2);
        assertEquals(25, moved.x());
        assertEquals(71, moved.y());
        assertEquals(-4, moved.z());
        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    /*[[AI-FN-DOC
Function:
moveWaypointToPrecise_focusesMovedWaypointAndStoresSixteenths.
Purpose:
Verify precise waypoint movement stores sixteenth-block centers while preserving the same focus/static-reach refresh behavior as block moves.
Why this exists:
Small waypoint repositioning commits through WaypointGroup.moveWaypointToPrecise, and regressions there would make the UI preview disagree with saved route state.
When to use:
Run with core waypoint group tests whenever precise position, move, focus, or static reach logic changes.
Inputs:
No runtime inputs; builds an in-memory route and moves one waypoint to explicit precise sixteenths.
Outputs:
Assertions pass when the moved waypoint has the expected precise fields, derived block coordinates, current index, suppression, and static reach reset.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if precise movement resets to block center, does not derive negative block coordinates correctly, or misses the same refresh side effects as moveWaypointTo.
Important invariants:
Precise coordinates are absolute sixteenths, and x/y/z are the floor block coordinates that contain those precise centers.
Internal logic:
Create a route, mark static reached state, move index 2 to precise sixteenths, then assert precision, center values, derived block coordinates, focus, suppression, and static state.
Pseudocode:
g = route
mark static waypoint 0 reached
move index 2 to precise 403, 1144, -21
moved = g.get(2)
assert precise fields equal target
assert centers equal 25.1875, 71.5, -1.3125
assert x/y/z equal 25, 71, -2
assert current index 2 and suppression true
assert static reached state cleared
Implementation notes:
The negative Z target exercises Math.floorDiv behavior in Waypoint's canonical constructor.
AI self-check:
Verify this test does not depend on client rendering or Minecraft classes.
]]*/
    @Test
    void moveWaypointToPrecise_focusesMovedWaypointAndStoresSixteenths() {
        WaypointGroup g = route();
        g.markStaticWaypointReached(0);

        g.moveWaypointToPrecise(2, 403, 1144, -21);

        Waypoint moved = g.get(2);
        assertEquals(403, moved.preciseX());
        assertEquals(1144, moved.preciseY());
        assertEquals(-21, moved.preciseZ());
        assertEquals(25.1875, moved.centerX());
        assertEquals(71.5, moved.centerY());
        assertEquals(-1.3125, moved.centerZ());
        assertEquals(25, moved.x());
        assertEquals(71, moved.y());
        assertEquals(-2, moved.z());
        assertEquals(2, g.currentIndex());
        assertTrue(g.isProximitySuppressed(2));
        assertFalse(g.isStaticWaypointReached(0));
    }

    @Test
    void restartIfRouteCompleted_wraps_when_enabled() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(true);
        assertEquals(0, g.currentIndex());
        assertFalse(g.isComplete());
    }

    @Test
    void restartIfRouteCompleted_noop_when_disabled() {
        WaypointGroup g = route();
        g.advancePast(3);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(false);
        assertTrue(g.isComplete());
    }

    @Test
    void effectiveRadius_prefersWaypointOverrideOverGroupDefault() {
        WaypointGroup g = route();
        g.setDefaultRadius(3.0);
        Waypoint wide = wp(0, 0, 0).withRadius(8.5);
        Waypoint thin = wp(0, 0, 0);
        assertEquals(8.5, g.effectiveRadius(wide));
        assertEquals(3.0, g.effectiveRadius(thin));
    }

    @Test
    void gradientAuto_assignsDistinctColors() {
        WaypointGroup g = route();
        int c0 = g.get(0).color();
        int cN = g.get(g.size() - 1).color();
        assertNotEquals(c0, cN, "first and last waypoints should differ in AUTO gradient");
    }

        @Test
    void staticColorModeRecolorsExistingWaypointsAndFutureAdditions() {
        WaypointGroup g = route();

        g.setStaticColor(0x123456);
        g.setGradientMode(WaypointGroup.GradientMode.STATIC);

        for (Waypoint waypoint : g.waypoints()) {
            assertEquals(0x123456, waypoint.color());
        }

        g.add(Waypoint.at(40, 70, 0).withColor(0xABCDEF));

        assertEquals(0x123456, g.get(g.size() - 1).color());
    }

        @Test
    void manualColorModePreservesIndividualWaypointColors() {
        WaypointGroup g = WaypointGroup.create("manual", "hub");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(Waypoint.at(0, 70, 0).withColor(0x111111));
        g.add(Waypoint.at(1, 70, 0).withColor(0x222222));

        g.setStaticColor(0x00FF00);
        g.setGradientStartColor(0xAAAAAA);
        g.setGradientEndColor(0xBBBBBB);

        assertEquals(0x111111, g.get(0).color());
        assertEquals(0x222222, g.get(1).color());
    }

    @Test
    void gradientLockedColor_isNotOverwritten() {
        WaypointGroup g = WaypointGroup.create("x", "z");
        int lockedColor = 0xFF00FF;
        g.add(Waypoint.at(0, 0, 0).withColor(lockedColor).withFlags(Waypoint.FLAG_LOCKED_COLOR));
        g.add(Waypoint.at(1, 0, 0));
        g.add(Waypoint.at(2, 0, 0));
        assertEquals(lockedColor, g.get(0).color(), "locked waypoint keeps its color through gradient");
    }

    @Test
    void forEachVisibleIndex_staticMode_returnsEverythingInOrder() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visible,
                "STATIC should surface every index so shared routes are fully rendered");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_atStart_showsCurrentAndNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(0);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 0, 1 }, visible,
                "SEQUENCE at index 0 has no previous; should show current + next only");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_middle_showsPrevCurrentNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(2);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 1, 2, 3 }, visible,
                "SEQUENCE in the middle should show the prev/current/next triple");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_atEnd_showsPrevAndCurrent() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(3);

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { 2, 3 }, visible,
                "SEQUENCE at the last index has no next; should show prev + current only");
    }

    @Test
    void forEachVisibleIndex_sequenceMode_afterCompletion_fallsBackToLastPoint() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(g.size()); // past the end -> isComplete()

        int[] visible = visibleIndices(g);
        assertArrayEquals(new int[] { g.size() - 1 }, visible,
                "completed SEQUENCE routes should still render the final point as a 'made it' marker");
    }

    @Test
    void restartIfRouteCompleted_keepsLastParentSubwaypointsVisibleWhileLoopingToStart() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(3);
        g.setCurrentIndex(2);

        g.advancePast(2);
        assertTrue(g.isComplete());
        g.restartIfRouteCompleted(true);

        assertEquals(0, g.currentIndex(), "tracer target should wrap back to the first main waypoint");
        assertEquals(2, g.activeSubwaypointParentIndex(),
                "the reached final parent should stay visually active after the wrap");
        assertArrayEquals(new int[] { 2, 3, 0, 1 }, visibleIndices(g),
                "final parent subwaypoints should remain visible while the route points back to #1");
    }

    @Test
    void forEachVisibleIndex_emptyGroup_returnsEmpty() {
        WaypointGroup g = WaypointGroup.create("empty", "dungeon_f7");
        assertEquals(0, visibleIndices(g).length);

        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        assertEquals(0, visibleIndices(g).length,
                "empty groups never render, regardless of load mode");
    }

    @Test
    void subwaypoint_toggle_rejectsFirstWaypointAndInfersNearestMainParent() {
        WaypointGroup g = route();

        assertFalse(g.toggleSubwaypoint(0), "the first row cannot become a child");
        assertTrue(g.toggleSubwaypoint(1));
        assertTrue(g.toggleSubwaypoint(2));

        assertTrue(g.isSubwaypoint(1));
        assertTrue(g.isSubwaypoint(2));
        assertEquals(0, g.parentMainIndex(1));
        assertEquals(0, g.parentMainIndex(2),
                "a child after another child still belongs to the nearest previous main waypoint");
        assertEquals("#1.1", g.displayIndexLabel(1));
        assertEquals("#1.2", g.displayIndexLabel(2));
    }

    @Test
    void subwaypoint_toggle_promotesChildBackToMain() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);

        assertTrue(g.toggleSubwaypoint(1));

        assertFalse(g.isSubwaypoint(1));
        assertEquals(4, g.mainWaypointCount());
    }

    /*[[AI-FN-DOC
Function:
subwaypoint_toggle_clearsSubwaypointOnlyStyleFlagsWhenPromoted
Purpose:
Verify small and filled subwaypoint style flags are removed when a subwaypoint becomes a main waypoint again.
Why this exists:
Small and filled controls are only meaningful for subwaypoints, and stale style bits on main waypoints would be confusing if the row later became a subwaypoint again.
When to use:
Run with core route-structure tests after changing Waypoint.withSubwaypoint or WaypointGroup subwaypoint normalization.
Inputs:
No parameters. Builds an in-memory route and mutates one waypoint's flags.
Outputs:
No return value. Assertions fail if style flags remain after promotion.
Side effects:
Mutates only the local test route.
Failure modes:
Fails if withSubwaypoint(false) stops clearing SUBWAYPOINT_STYLE_FLAGS or toggleSubwaypoint bypasses that helper.
Important invariants:
Promoting back to main clears FLAG_SUBWAYPOINT, FLAG_SMALL_SUBWAYPOINT, and FLAG_FILLED_SUBWAYPOINT together.
Internal logic:
Create a route, make index 1 a subwaypoint, add both style flags, assert they exist, toggle it back to main, and assert all subwaypoint-only flags are gone.
Pseudocode:
g = route
toggle index 1 to subwaypoint
styled = waypoint flags OR small OR filled
set waypoint with styled flags
assert small and filled flags exist
toggle index 1 to main
assert not subwaypoint
assert small and filled flags absent
Implementation notes:
This tests through WaypointGroup because that is the same mutation path the GUI uses for structural toggles.
AI self-check:
Verify the test does not depend on rendering or GUI classes.
]]*/
    @Test
    void subwaypoint_toggle_clearsSubwaypointOnlyStyleFlagsWhenPromoted() {
        WaypointGroup g = route();
        assertTrue(g.toggleSubwaypoint(1));

        Waypoint styled = g.get(1).withFlags(g.get(1).flags()
                | Waypoint.FLAG_SMALL_SUBWAYPOINT
                | Waypoint.FLAG_FILLED_SUBWAYPOINT);
        g.set(1, styled);
        assertTrue(g.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertTrue(g.get(1).hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));

        assertTrue(g.toggleSubwaypoint(1));

        assertFalse(g.isSubwaypoint(1));
        assertFalse(g.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertFalse(g.get(1).hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT));
    }

    @Test
    void forEachVisibleIndex_sequenceMode_showsCurrentMainChildrenAndAdjacentMains() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);
        g.setCurrentIndex(0);

        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g),
                "current parent should render its subwaypoints plus the next main waypoint");

        g.setCurrentIndex(3);

        assertArrayEquals(new int[] { 0, 3 }, visibleIndices(g),
                "children only render while their parent main waypoint is current");
    }

    @Test
    void advancePast_skipsSubwaypointsAndKeepsCurrentOnMainWaypoints() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.advancePast(0);

        assertEquals(3, g.currentIndex());
        assertEquals(2, g.currentMainOrdinal());
    }

    @Test
    void advancePast_parentWithSubwaypoints_keepsParentChildrenVisibleWhileTargetingNextMain() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.advancePast(0);

        assertEquals(3, g.currentIndex(), "tracer target should advance to the next main waypoint");
        assertEquals(0, g.activeSubwaypointParentIndex(),
                "reached parent should stay visually active while targeting the next main");
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visibleIndices(g),
                "reached parent subwaypoints should remain visible until the next main is reached");

        g.advancePast(3);

        assertEquals(-1, g.activeSubwaypointParentIndex());
        assertArrayEquals(new int[] { 3 }, visibleIndices(g));
    }

    @Test
    void visibleIndices_afterSubwaypointParentAdvance_doNotShowNextParentsChildrenEarly() {
        WaypointGroup g = WaypointGroup.create("route", "test_zone");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        for (int i = 0; i < 5; i++) {
            g.add(Waypoint.at(i * 10, 0, 0));
        }
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(3);

        g.advancePast(0);

        assertEquals(2, g.currentIndex());
        assertArrayEquals(new int[] { 0, 1, 2, 4 }, visibleIndices(g),
                "the next parent's subwaypoints should wait until that parent is reached");

        g.advancePast(2);

        assertEquals(4, g.currentIndex());
        assertArrayEquals(new int[] { 2, 3, 4 }, visibleIndices(g),
                "the visual hold should move to the newly reached subwaypoint parent");
    }

    @Test
    void setCurrentIndex_onSubwaypointTargetsItsParent() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);

        g.setCurrentIndex(1);

        assertEquals(0, g.currentIndex());
        assertEquals(g.get(0), g.current());
    }

    @Test
    void remove_parentPromotesItsSubwaypointsToMainWaypoints() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        g.remove(0);

        assertEquals(3, g.size());
        assertFalse(g.isSubwaypoint(0));
        assertFalse(g.isSubwaypoint(1));
        assertEquals(3, g.mainWaypointCount());
    }

    @Test
    void moveBy_parentCarriesSubwaypointBlock() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        int movedTo = g.moveBy(0, 1);

        assertEquals(1, movedTo);
        assertEquals(30, g.get(0).x());
        assertEquals(0, g.get(1).x());
        assertTrue(g.isSubwaypoint(2));
        assertTrue(g.isSubwaypoint(3));
        assertEquals(1, g.parentMainIndex(2));
        assertEquals(1, g.parentMainIndex(3));
    }

    @Test
    void moveBy_subwaypointReordersWithinSiblingBlockOnly() {
        WaypointGroup g = route();
        g.toggleSubwaypoint(1);
        g.toggleSubwaypoint(2);

        int movedTo = g.moveBy(1, 1);

        assertEquals(2, movedTo);
        assertEquals(20, g.get(1).x());
        assertEquals(10, g.get(2).x());
        assertTrue(g.isSubwaypoint(1));
        assertTrue(g.isSubwaypoint(2));
        assertEquals(0, g.parentMainIndex(1));
        assertEquals(0, g.parentMainIndex(2));
    }

    @Test
    void loadMode_defaultsToSequence() {
        WaypointGroup g = WaypointGroup.create("r", "z");
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, g.loadMode(),
                "loaded routes default to SEQUENCE so the intended order is visible");
    }

    @Test
    void create_canSeedSkipAheadFromGlobalDefault() {
        WaypointGroup enabled = WaypointGroup.create("on", "hub", true);
        WaypointGroup disabled = WaypointGroup.create("off", "hub", false);

        assertTrue(enabled.skipAheadEnabled());
        assertFalse(disabled.skipAheadEnabled());
    }

    @Test
    void manager_getOrCreateActiveGroupSeedsSkipAheadDefault() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup route = manager.getOrCreateActiveGroup(false);

        assertFalse(route.skipAheadEnabled());
    }

    @Test
    void manager_addTempWaypointCreatesStaticLeaveScopedTempBucketForCurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup bucket = manager.addTempWaypoint(100, 64, -200);

        assertEquals("temp::dungeon_f7", bucket.id());
        assertEquals("Temporary", bucket.name());
        assertTrue(bucket.temp());
        assertEquals(WaypointGroup.LoadMode.STATIC, bucket.loadMode());
        assertEquals(1, bucket.size());
        assertEquals(bucket, manager.firstActiveGroup());

        Waypoint waypoint = bucket.get(0);
        assertEquals(100, waypoint.x());
        assertEquals(64, waypoint.y());
        assertEquals(-200, waypoint.z());
        assertTrue(waypoint.isTemp());
        assertEquals(Waypoint.TEMP_UNTIL_LEAVE, waypoint.tempMode());
    }

    @Test
    void manager_addTempWaypointCanUseConfiguredTimedExpiry() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup bucket = manager.addTempWaypoint(
                100, 64, -200, "", Waypoint.TEMP_TIME, 123_456L);

        Waypoint waypoint = bucket.get(0);
        assertEquals(Waypoint.TEMP_TIME, waypoint.tempMode());
        assertEquals(123_456L, waypoint.expiresAtMillis());
    }

    @Test
    void manager_getOrCreateActiveGroupDoesNotReuseTempBucket() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);

        WaypointGroup route = manager.getOrCreateActiveGroup();

        assertNotSame(bucket, route);
        assertFalse(route.temp());
        assertEquals("hub", route.zoneId());
    }

    @Test
    void manager_chatTempWaypointIncludesSenderInGroupAndWaypointName() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup bucket = manager.addTempWaypoint(1, 2, 3, "\u00A7eFrom \u00A7dBabbur");

        assertEquals("Temporary", bucket.name());
        assertEquals(1, bucket.size());
        assertEquals("\u00A7eFrom \u00A7dBabbur", bucket.get(0).name());
        assertTrue(bucket.get(0).isTemp());
    }

    @Test
    void manager_addTempWaypointReusesBucketAndInvalidatesActiveCache() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        WaypointGroup firstBucket = manager.addTempWaypoint(1, 70, 2);
        assertEquals(1, manager.activeGroups().size());

        WaypointGroup secondBucket = manager.addTempWaypoint(3, 71, 4);

        assertSame(firstBucket, secondBucket);
        assertEquals(2, manager.activeGroups().get(0).size(),
                "second temp waypoint should be visible through refreshed active cache");
    }

    @Test
    void manager_tempWaypointFocusHidesOtherActiveGroupsUntilCleared() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route();
        route.setZoneId("hub");
        manager.add(route);

        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);
        bucket.add(Waypoint.at(3, 71, 4).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.focusTempWaypoint(bucket, 1);

        assertEquals(List.of(bucket), manager.activeGroups());
        assertArrayEquals(new int[] { 1 }, visibleIndices(bucket),
                "focus should render only the newly-added temp waypoint");
        assertEquals(bucket.get(1), bucket.current(),
                "the tracer target follows the focused temporary waypoint");

        manager.clearTempWaypointFocus();

        assertEquals(List.of(route, bucket), manager.activeGroups());
        assertArrayEquals(new int[] { 0, 1 }, visibleIndices(bucket));
    }

    @Test
    void manager_tempWaypointFocusClearsWhenFocusedTempIsRemoved() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route();
        route.setZoneId("hub");
        manager.add(route);

        WaypointGroup bucket = manager.addTempWaypoint(1, 70, 2);
        manager.focusTempWaypoint(bucket, 0);
        bucket.remove(0);

        assertEquals(List.of(route, bucket), manager.activeGroups());
    }

    private static int[] visibleIndices(WaypointGroup group) {
        List<Integer> indices = new ArrayList<>();
        group.forEachVisibleIndex(indices::add);
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }
}
