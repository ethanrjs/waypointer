package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WaypointAppearanceUndoTest {
    @Test void undoFollowsReorderedPointsAndPreservesLaterEdits() {
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.add(Waypoint.at(1, 2, 3)); group.add(Waypoint.at(4, 5, 6));
        List<Waypoint> before = List.copyOf(group.waypoints());
        WaypointStyle style = WaypointStyle.EMPTY.with("labelOpacity", 0.5);
        group.set(0, group.get(0).withStyleOverride(style));
        group.set(1, group.get(1).withStyleOverride(style));
        WaypointAppearanceUndo undo = WaypointAppearanceUndo.between(before, group.waypoints());
        group.move(1, 0);
        group.moveWaypointTo(0, 10, 11, 12);
        Waypoint editedSecond = group.get(0);
        Waypoint expectedFirst = group.get(1).withStyleOverride(before.get(0).styleOverride());
        assertEquals(1, undo.undo(group));
        assertSame(editedSecond, group.get(0));
        assertEquals(expectedFirst, group.get(1));
        assertEquals(0, undo.undo(group));
    }
    @Test void undoSkipsAmbiguousDuplicatesAndLaterStyleChanges() {
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.add(Waypoint.at(1, 2, 3)); group.add(Waypoint.at(1, 2, 3));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.set(1, group.get(1).withColor(group.get(0).color()));
        List<Waypoint> before = List.copyOf(group.waypoints());
        WaypointStyle style = WaypointStyle.EMPTY.with("labelOpacity", 0.5);
        group.set(0, group.get(0).withStyleOverride(style));
        group.set(1, group.get(1).withStyleOverride(style));
        WaypointAppearanceUndo undo = WaypointAppearanceUndo.between(before, group.waypoints());
        assertEquals(0, undo.undo(group));
        group.moveWaypointTo(1, 4, 5, 6);
        group.set(0, group.get(0).withStyleOverride(style.with("labelOpacity", 0.7)));
        assertEquals(0, undo.undo(group));
    }
    @Test void ordinaryNameAndColorEditsKeepReachedStateButPositionChangesResetIt() {
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(1, 2, 3)); group.add(Waypoint.at(4, 5, 6));
        assertTrue(group.markStaticWaypointReached(0));
        group.set(0, group.get(0).withName("Renamed").withColor(0xABCDEF));
        assertTrue(group.isStaticWaypointReached(0));
        assertEquals(0xABCDEF, group.manualColorSnapshot().get(0));
        group.set(0, group.get(0).withPos(7, 8, 9));
        assertFalse(group.isStaticWaypointReached(0));
    }

    @Test void stylingAndUndoPreserveStaticProgress() {
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.add(Waypoint.at(1, 2, 3)); group.add(Waypoint.at(4, 5, 6));
        assertTrue(group.markStaticWaypointReached(0));
        List<Waypoint> before = List.copyOf(group.waypoints());
        group.setWaypointStyle(0, WaypointStyle.EMPTY.with("labelOpacity", 0.5));
        assertTrue(group.isStaticWaypointReached(0));
        WaypointAppearanceUndo undo = WaypointAppearanceUndo.between(before, group.waypoints());
        assertEquals(1, undo.undo(group));
        assertTrue(group.isStaticWaypointReached(0));
    }

}
