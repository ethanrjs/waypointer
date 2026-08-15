package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The current waypoint must never rest on a disabled waypoint, no matter which
 * mutation produced the state (issue #113).
 */
class WaypointGroupDisabledCurrentTest {

    private static WaypointGroup route(int size) {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        for (int i = 0; i < size; i++) {
            group.add(Waypoint.at(i, 64, 0));
        }
        return group;
    }

    private static void assertCurrentNotDisabled(WaypointGroup group, String context) {
        int index = group.currentIndex();
        if (index >= 0 && index < group.size()) {
            assertFalse(group.get(index).isDisabled(),
                    context + ": currentIndex " + index + " rests on a disabled waypoint");
        }
    }

    @Test
    void settingCurrentToADisabledWaypointResetsProgressInstead() {
        WaypointGroup group = route(4);
        group.setWaypointDisabled(2, true);

        group.setCurrentIndex(1);
        group.setCurrentIndex(2);
        assertEquals(0, group.currentIndex(),
                "jumping to a disabled waypoint must reset progress");
        assertCurrentNotDisabled(group, "setCurrentIndex");

        group.setCurrentTargetIndex(1);
        group.setCurrentTargetIndex(2);
        assertEquals(0, group.currentIndex(),
                "targeting a disabled waypoint must reset progress");
        assertCurrentNotDisabled(group, "setCurrentTargetIndex");
    }

    @Test
    void disablingTheCurrentWaypointMovesCurrentOffIt() {
        WaypointGroup group = route(4);
        group.setCurrentIndex(2);
        group.setWaypointDisabled(2, true);
        assertCurrentNotDisabled(group, "setWaypointDisabled(current)");
    }

    @Test
    void removingTheCurrentWaypointSkipsADisabledSuccessor() {
        WaypointGroup group = route(4);
        group.setWaypointDisabled(2, true);
        group.setCurrentIndex(1);
        group.remove(1);
        assertCurrentNotDisabled(group, "remove(current)");
    }

    @Test
    void retreatingOverADisabledWaypointLandsOnAnEnabledOne() {
        WaypointGroup group = route(4);
        group.setWaypointDisabled(1, true);
        group.setCurrentIndex(2);
        assertTrue(group.retreatToPreviousTarget());
        assertEquals(0, group.currentIndex());
        assertCurrentNotDisabled(group, "retreatToPreviousTarget");
    }

    @Test
    void disablingEveryWaypointCompletesInsteadOfSticking() {
        WaypointGroup group = route(3);
        for (int i = 0; i < group.size(); i++) {
            group.setWaypointDisabled(i, true);
        }
        assertCurrentNotDisabled(group, "all disabled");
        assertEquals(group.size(), group.currentIndex());
    }

    @Test
    void reenablingAnEarlierWaypointAfterCompletionResetsCleanly() {
        WaypointGroup group = route(3);
        for (int i = 0; i < group.size(); i++) {
            group.setWaypointDisabled(i, true);
        }
        group.setWaypointDisabled(1, false);
        group.resetProgress();
        assertEquals(1, group.currentIndex());
        assertCurrentNotDisabled(group, "reset after re-enable");
    }

    @Test
    void moveKeepsCurrentOffDisabledWaypoints() {
        WaypointGroup group = route(4);
        group.setWaypointDisabled(3, true);
        group.setCurrentIndex(1);
        group.move(3, 1);
        assertCurrentNotDisabled(group, "move disabled onto current slot");
    }
}
