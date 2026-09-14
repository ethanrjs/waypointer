package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditHistoryTest {
    @Test void dungeonEditsRestoreTheDurableSourceAndProjectedRouteTogether() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup stored = WaypointGroup.create("Stored", "room");
        stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        stored.add(Waypoint.at(1, 2, 3));
        manager.add(stored);
        WaypointGroup mirror = new WaypointGroup(
                com.babbur.waypointer.dungeon.DungeonRoomRouteProjection.generatedGroupId("room"), "Mirror", "room");
        mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(stored.id());
        mirror.add(Waypoint.at(10, 20, 30));
        var before = GroupEditScreen.RouteHistoryEntry.capture(manager, mirror);
        stored.add(Waypoint.at(4, 5, 6));
        mirror.setName("Changed");
        before.restore(mirror);
        assertEquals(1, stored.size());
        assertEquals("Mirror", mirror.name());
        assertEquals(before.visible().points().getFirst(), mirror.get(0));
        assertEquals(before, GroupEditScreen.RouteHistoryEntry.capture(manager, mirror));
    }

    @Test void boundsHistoryAndClearsRedoOnlyForAnActualNewEdit() {
        EditHistory<Integer> history = new EditHistory<>(0);
        for (int i = 1; i <= 70; i++) history.record(i);
        for (int i = 0; i < 50; i++) history.undo();
        assertFalse(history.canUndo());
        assertEquals(20, history.undo());
        history.record(20);
        assertTrue(history.canRedo());
        assertEquals(21, history.redo());
        history.record(100);
        assertFalse(history.canRedo());
        assertEquals(21, history.undo());
    }

    @Test void restoresCompleteRouteEditsWithoutReplacingTheGroupOrUsingACodec() {
        WaypointGroup group = WaypointGroup.create("Original", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(new Waypoint(1, 2, 3, "First", 0x123456, 0, 2));
        group.add(new Waypoint(4, 5, 6, "Second", 0xABCDEF, Waypoint.FLAG_LOCKED_COLOR, 3));
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        group.setCurrentIndex(1);
        group.markStaticWaypointReached(0);
        group.setPaint(WaypointPaint.solid(0x654321));
        group.setStyleOverride(WaypointStyle.EMPTY.with("showRouteLines", "false"));
        RouteEditState original = RouteEditState.capture(group);
        EditHistory<RouteEditState> history = new EditHistory<>(original);
        group.setName("Changed");
        group.setZoneId("private_island");
        group.setEnabled(false);
        group.remove(0);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        group.setDefaultRadius(7);
        group.setPaintEnabled(false);
        group.setStyleOverride(WaypointStyle.EMPTY);
        RouteEditState changed = RouteEditState.capture(group);
        history.record(changed);
        history.undo().restore(group);
        assertEquals(original, RouteEditState.capture(group));
        assertEquals("hub", group.zoneId());
        history.redo().restore(group);
        assertEquals(changed, RouteEditState.capture(group));
    }
}
