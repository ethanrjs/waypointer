package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceRoleColorTest {

    @Test
    void resolvesPreviousCurrentAndNextColorsForSequentialRoutes() {
        WaypointGroup group = route();
        group.setCurrentIndex(1);

        assertEquals(SequenceRoleColor.Role.PREVIOUS, SequenceRoleColor.roleFor(group, 0));
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 1));
        assertEquals(SequenceRoleColor.Role.NEXT, SequenceRoleColor.roleFor(group, 2));
        assertEquals(0x112233, resolve(group, 0, true));
        assertEquals(0x445566, resolve(group, 1, true));
        assertEquals(0x778899, resolve(group, 2, true));
    }

    @Test
    void disabledRoleColorsAndStaticRoutesPreserveWaypointColor() {
        WaypointGroup group = route();

        assertEquals(0xABCDEF, resolve(group, 0, false));
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        assertEquals(SequenceRoleColor.Role.NONE, SequenceRoleColor.roleFor(group, 0));
        assertEquals(0xABCDEF, resolve(group, 0, true));
    }

    @Test
    void subwaypointRolesFollowTheActiveTargetAndHeldParent() {
        WaypointGroup group = route();
        group.toggleSubwaypoint(1);
        group.toggleSubwaypoint(2);
        group.setCurrentTargetIndex(2);

        assertEquals(SequenceRoleColor.Role.PREVIOUS, SequenceRoleColor.roleFor(group, 1));
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 2));
        assertEquals(SequenceRoleColor.Role.NEXT, SequenceRoleColor.roleFor(group, 3));

        group.setCurrentTargetIndex(0);
        group.advancePast(0);
        assertEquals(0, group.activeSubwaypointParentIndex());
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 1));
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 2));
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 0));
        assertEquals(SequenceRoleColor.Role.CURRENT, SequenceRoleColor.roleFor(group, 3));
    }

    @Test
    void completeRouteTreatsEveryWaypointAsPrevious() {
        WaypointGroup group = route();
        group.setCurrentIndex(group.size());

        for (int index = 0; index < group.size(); index++) {
            assertEquals(SequenceRoleColor.Role.PREVIOUS,
                    SequenceRoleColor.roleFor(group, index));
        }
    }

    private static int resolve(WaypointGroup group, int index, boolean enabled) {
        return SequenceRoleColor.resolve(
                group, index, enabled, 0x112233, 0x445566, 0x778899, 0xABCDEF);
    }

    private static WaypointGroup route() {
        WaypointGroup group = WaypointGroup.create("Route", "dungeon_f7");
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(10, 70, 0));
        group.add(Waypoint.at(20, 70, 0));
        group.add(Waypoint.at(30, 70, 0));
        return group;
    }
}
