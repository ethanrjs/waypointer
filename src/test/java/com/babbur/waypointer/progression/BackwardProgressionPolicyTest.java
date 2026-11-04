package com.babbur.waypointer.progression;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackwardProgressionPolicyTest {

    @Test
    void disabledPolicyLeavesForwardProgressUntouched() {
        WaypointGroup group = line();
        group.setCurrentTargetIndex(2);

        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, false, 10.5, 0.5, 0.5));
        assertFalse(BackwardProgressionPolicy.retreatIfReached(
                group, false, 10.5, 0.5, 0.5));
        assertEquals(2, group.currentIndex());
    }

    @Test
    void enabledPolicyMovesToHighestReachedEarlierMainWaypoint() {
        WaypointGroup group = line();
        group.setDefaultRadius(12.0);
        group.setCurrentTargetIndex(3);

        assertEquals(1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 5.5, 0.5, 0.5));
        assertTrue(BackwardProgressionPolicy.retreatIfReached(
                group, true, 5.5, 0.5, 0.5));
        assertEquals(1, group.currentIndex());
        assertEquals(1, group.proximitySuppressedIndex());
        assertFalse(BackwardProgressionPolicy.retreatIfReached(
                group, true, 5.5, 0.5, 0.5));
    }

    @Test
    void disabledSubwaypointAndDungeonEventTargetsAreExcluded() {
        WaypointGroup disabled = line();
        disabled.setWaypointDisabled(1, true);
        disabled.setCurrentTargetIndex(2);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                disabled, true, 10.5, 0.5, 0.5));

        WaypointGroup subway = WaypointGroup.create("subway", "hub");
        subway.setDefaultRadius(2.0);
        subway.add(Waypoint.at(0, 0, 0));
        subway.add(Waypoint.at(10, 0, 0).withSubwaypoint(true));
        subway.add(Waypoint.at(20, 0, 0));
        subway.setCurrentTargetIndex(2);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                subway, true, 10.5, 0.5, 0.5));

        WaypointGroup dungeon = WaypointGroup.create("dungeon", "room");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        dungeon.setDefaultRadius(2.0);
        dungeon.add(Waypoint.at(0, 0, 0)
                .withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));
        dungeon.add(Waypoint.at(10, 0, 0));
        dungeon.setCurrentTargetIndex(1);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                dungeon, true, 0.5, 0.5, 0.5));
    }

    @Test
    void onlyEnabledPersistentSequenceGroupsCanStepBack() {
        WaypointGroup group = line();
        group.setCurrentTargetIndex(2);

        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 10.5, 0.5, 0.5));
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setTemp(true);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 10.5, 0.5, 0.5));
        group.setTemp(false);
        group.setEnabled(false);
        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 10.5, 0.5, 0.5));
    }

    @Test
    void customRadiusAndCompletedRoutesUseTheSameReachPolicy() {
        WaypointGroup group = line();
        group.setCurrentTargetIndex(2);
        group.set(1, group.get(1).withRadius(0.5));

        assertEquals(-1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 11.1, 0.5, 0.5));
        assertEquals(1, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 10.5, 0.5, 0.5));

        group.setCurrentTargetIndex(group.size());
        assertEquals(2, BackwardProgressionPolicy.reachedEarlierIndex(
                group, true, 20.5, 0.5, 0.5));
    }

    private static WaypointGroup line() {
        WaypointGroup group = WaypointGroup.create("route", "hub");
        group.setDefaultRadius(2.0);
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(10, 0, 0));
        group.add(Waypoint.at(20, 0, 0));
        return group;
    }
}
