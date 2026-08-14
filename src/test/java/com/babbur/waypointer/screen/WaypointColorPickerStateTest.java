package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointColorPickerStateTest {

    @Test
    void mainWaypointWithChildrenShowsUncheckedApplyOption() {
        WaypointGroup group = routeWithSubwaypoints();

        WaypointColorPickerState state = WaypointColorPickerState.forTarget(group, 0);

        assertTrue(state.applyToSubwaypointsVisible());
        assertFalse(state.applyToSubwaypoints());
        assertEquals(280, ColorPickerScreen.panelHeight(true));
        assertEquals(252, ColorPickerScreen.panelHeight(false));
    }

    @Test
    void uncheckedOptionChangesOnlyTheSelectedMainWaypoint() {
        WaypointGroup group = routeWithSubwaypoints();
        WaypointColorPickerState state = WaypointColorPickerState.forTarget(group, 0);

        assertTrue(state.applyColor(group, 0x8844CC));

        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(0x8844CC, group.get(0).color());
        assertEquals(0x22AA44, group.get(1).color());
        assertEquals(0x33BB55, group.get(2).color());
        assertEquals(0x445566, group.get(3).color());
        assertTrue(group.get(0).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        assertFalse(group.get(1).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
    }

    @Test
    void checkedOptionChangesAndLocksEveryOwnedSubwaypoint() {
        WaypointGroup group = routeWithSubwaypoints();
        WaypointColorPickerState state = WaypointColorPickerState.forTarget(group, 0);
        state.setApplyToSubwaypoints(true);

        assertTrue(state.applyColor(group, 0x1188EE));

        for (int index = 0; index <= 2; index++) {
            assertEquals(0x1188EE, group.get(index).color());
            assertTrue(group.get(index).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        }
        assertEquals(0x445566, group.get(3).color());
    }

    @Test
    void optionIsHiddenForAChildOrMainWaypointWithoutChildren() {
        WaypointGroup group = routeWithSubwaypoints();
        WaypointColorPickerState child = WaypointColorPickerState.forTarget(group, 1);
        WaypointColorPickerState childlessMain = WaypointColorPickerState.forTarget(group, 3);

        child.setApplyToSubwaypoints(true);
        childlessMain.setApplyToSubwaypoints(true);

        assertFalse(child.applyToSubwaypointsVisible());
        assertFalse(child.applyToSubwaypoints());
        assertFalse(childlessMain.applyToSubwaypointsVisible());
        assertFalse(childlessMain.applyToSubwaypoints());

        assertTrue(child.applyColor(group, 0xCC2277));
        assertEquals(0x116633, group.get(0).color());
        assertEquals(0xCC2277, group.get(1).color());
        assertEquals(0x33BB55, group.get(2).color());
    }

    private static WaypointGroup routeWithSubwaypoints() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 70, 0).withColor(0x116633));
        group.add(Waypoint.at(1, 70, 0).withColor(0x22AA44));
        group.add(Waypoint.at(2, 70, 0).withColor(0x33BB55));
        group.add(Waypoint.at(3, 70, 0).withColor(0x445566));
        assertTrue(group.toggleSubwaypoint(1));
        assertTrue(group.toggleSubwaypoint(2));
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        return group;
    }
}
