package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

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
    void gradientLockedColor_isNotOverwritten() {
        WaypointGroup g = WaypointGroup.create("x", "z");
        int lockedColor = 0xFF00FF;
        g.add(Waypoint.at(0, 0, 0).withColor(lockedColor).withFlags(Waypoint.FLAG_LOCKED_COLOR));
        g.add(Waypoint.at(1, 0, 0));
        g.add(Waypoint.at(2, 0, 0));
        assertEquals(lockedColor, g.get(0).color(), "locked waypoint keeps its color through gradient");
    }

    @Test
    void visibleIndices_staticMode_returnsEverythingInOrder() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.STATIC);

        int[] visible = g.visibleIndices();
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, visible,
                "STATIC should surface every index so shared routes are fully rendered");
    }

    @Test
    void visibleIndices_sequenceMode_atStart_showsCurrentAndNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(0);

        int[] visible = g.visibleIndices();
        assertArrayEquals(new int[] { 0, 1 }, visible,
                "SEQUENCE at index 0 has no previous; should show current + next only");
    }

    @Test
    void visibleIndices_sequenceMode_middle_showsPrevCurrentNext() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(2);

        int[] visible = g.visibleIndices();
        assertArrayEquals(new int[] { 1, 2, 3 }, visible,
                "SEQUENCE in the middle should show the prev/current/next triple");
    }

    @Test
    void visibleIndices_sequenceMode_atEnd_showsPrevAndCurrent() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(3);

        int[] visible = g.visibleIndices();
        assertArrayEquals(new int[] { 2, 3 }, visible,
                "SEQUENCE at the last index has no next; should show prev + current only");
    }

    @Test
    void visibleIndices_sequenceMode_afterCompletion_fallsBackToLastPoint() {
        WaypointGroup g = route();
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setCurrentIndex(g.size()); // past the end -> isComplete()

        int[] visible = g.visibleIndices();
        assertArrayEquals(new int[] { g.size() - 1 }, visible,
                "completed SEQUENCE routes should still render the final point as a 'made it' marker");
    }

    @Test
    void visibleIndices_emptyGroup_returnsEmpty() {
        WaypointGroup g = WaypointGroup.create("empty", "dungeon_f7");
        assertEquals(0, g.visibleIndices().length);

        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        assertEquals(0, g.visibleIndices().length,
                "empty groups never render, regardless of load mode");
    }

    @Test
    void loadMode_defaultsToStatic() {
        WaypointGroup g = WaypointGroup.create("r", "z");
        assertEquals(WaypointGroup.LoadMode.STATIC, g.loadMode(),
                "shared routes default to STATIC so imported groups stay visible");
    }
}
