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
        int[] before = colours(group);
        WaypointColorPickerState state = WaypointColorPickerState.forTarget(group, 0);

        assertTrue(state.applyColor(group, 0x8844CC));

        assertEquals(WaypointGroup.GradientMode.AUTO, group.gradientMode(), "a gradient route stays a gradient");
        assertEquals(0x8844CC, group.get(0).color());
        assertEquals(before[1], group.get(1).color());
        assertEquals(before[2], group.get(2).color());
        assertEquals(before[3], group.get(3).color());
        assertTrue(group.get(0).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        assertFalse(group.get(1).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
    }

    @Test
    void checkedOptionChangesAndLocksEveryOwnedSubwaypoint() {
        WaypointGroup group = routeWithSubwaypoints();
        int[] before = colours(group);
        WaypointColorPickerState state = WaypointColorPickerState.forTarget(group, 0);
        state.setApplyToSubwaypoints(true);

        assertTrue(state.applyColor(group, 0x1188EE));

        for (int index = 0; index <= 2; index++) {
            assertEquals(0x1188EE, group.get(index).color());
            assertTrue(group.get(index).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        }
        assertEquals(before[3], group.get(3).color());
    }

    @Test
    void optionIsHiddenForAChildOrMainWaypointWithoutChildren() {
        WaypointGroup group = routeWithSubwaypoints();
        int[] before = colours(group);
        WaypointColorPickerState child = WaypointColorPickerState.forTarget(group, 1);
        WaypointColorPickerState childlessMain = WaypointColorPickerState.forTarget(group, 3);

        child.setApplyToSubwaypoints(true);
        childlessMain.setApplyToSubwaypoints(true);

        assertFalse(child.applyToSubwaypointsVisible());
        assertFalse(child.applyToSubwaypoints());
        assertFalse(childlessMain.applyToSubwaypointsVisible());
        assertFalse(childlessMain.applyToSubwaypoints());

        assertTrue(child.applyColor(group, 0xCC2277));
        assertEquals(before[0], group.get(0).color());
        assertEquals(0xCC2277, group.get(1).color());
        assertEquals(before[2], group.get(2).color());
    }

    @Test
    void unlockingRestoresTheExactGradientColour() {
        WaypointGroup group = WaypointGroup.create("Gradient", "hub");
        for (int i = 0; i < 5; i++) group.add(Waypoint.at(i, 70, 0));
        group.setGradientStartColor(0x22CC44);
        group.setGradientEndColor(0xFF8800);
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        int gradientColour = group.get(2).color();

        WaypointColorPickerState.forTarget(group, 2).applyColor(group, 0x0000FF);
        group.add(Waypoint.at(9, 70, 0));
        assertEquals(0x0000FF, group.get(2).color(), "structure edits keep the locked colour");

        group.unlockWaypointColor(2);

        assertEquals(WaypointGroup.GradientMode.AUTO, group.gradientMode());
        assertFalse(group.get(2).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        WaypointGroup reference = WaypointGroup.create("Reference", "hub");
        for (int i = 0; i < 6; i++) reference.add(Waypoint.at(i, 70, 0));
        reference.setGradientStartColor(0x22CC44);
        reference.setGradientEndColor(0xFF8800);
        reference.setGradientMode(WaypointGroup.GradientMode.AUTO);
        assertEquals(reference.get(2).color(), group.get(2).color());
        assertTrue(gradientColour != 0x0000FF);
    }

    @Test
    void singleColourRouteBecomesManualSoTheLockedColourSurvives() {
        WaypointGroup group = WaypointGroup.create("One", "hub");
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(1, 70, 0));
        group.setStaticColor(0x123456);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);

        WaypointColorPickerState.forTarget(group, 1).applyColor(group, 0xABCDEF);

        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(0x123456, group.get(0).color());
        assertEquals(0xABCDEF, group.get(1).color());
    }

    private static int[] colours(WaypointGroup group) {
        int[] colours = new int[group.size()];
        for (int i = 0; i < colours.length; i++) colours[i] = group.get(i).color();
        return colours;
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
