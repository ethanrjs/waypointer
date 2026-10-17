package com.babbur.waypointer.core;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddTargetResolutionTest {

    @Test
    void lastEditedRouteWinsOverInsertionOrder() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        manager.add(first);
        manager.add(second);

        assertSame(first, manager.getOrCreateActiveGroup());

        manager.noteRouteEdited(second);

        assertSame(second, manager.getOrCreateActiveGroup());
        assertSame(second, manager.activeRouteGroupOrNull());
    }

    @Test
    void masterModeFloorsUseTheirNormalFloorsRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup boss = WaypointGroup.create("Boss", "dungeon_f7");
        boss.add(new Waypoint(1, 2, 3, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        WaypointGroup other = WaypointGroup.create("Floor 6", "dungeon_f6");
        other.add(new Waypoint(1, 2, 3, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        manager.add(boss);
        manager.add(other);

        manager.onZoneChanged(Zone.fromId("dungeon_m7"));

        assertEquals(java.util.List.of(boss), manager.activeGroups());
        assertSame(boss, manager.activeRouteGroupOrNull());
    }

    @Test
    void lastEditedRouteIsRememberedPerZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hubFirst = WaypointGroup.create("Hub first", "hub");
        WaypointGroup hubSecond = WaypointGroup.create("Hub second", "hub");
        WaypointGroup garden = WaypointGroup.create("Garden", "garden");
        manager.add(hubFirst);
        manager.add(hubSecond);
        manager.add(garden);
        manager.noteRouteEdited(hubSecond);
        manager.noteRouteEdited(garden);

        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertSame(hubSecond, manager.getOrCreateActiveGroup());
    }

    @Test
    void disabledLastEditedRouteFallsBackToTheNextCandidate() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        manager.add(first);
        manager.add(second);
        manager.noteRouteEdited(second);
        second.setEnabled(false);

        assertSame(first, manager.getOrCreateActiveGroup());
    }

    @Test
    void pinnedRecordingTargetBeatsLastEdited() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup pinned = WaypointGroup.create("Pinned", "hub");
        WaypointGroup edited = WaypointGroup.create("Edited", "hub");
        manager.add(pinned);
        manager.add(edited);
        manager.setRecordingTarget(pinned);
        manager.noteRouteEdited(edited);

        assertSame(pinned, manager.getOrCreateActiveGroup());
    }

    @Test
    void dungeonRoomAddsGoToTheRouteBeingDrawn() {
        assertNotNull(DungeonRoomData.entry("spider"));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));
        WaypointGroup hidden = storedRoomRoute("Hidden", "spider");
        WaypointGroup drawn = storedRoomRoute("Drawn", "spider");
        manager.add(hidden);
        manager.add(drawn);
        WaypointGroup mirror = new WaypointGroup("dungeon:auto:spider", "Drawn", "spider");
        mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(drawn.id());
        mirror.add(Waypoint.at(0, 0, 0));
        manager.add(mirror);

        assertSame(drawn, manager.getOrCreateActiveGroup());
    }

    @Test
    void editingTheProjectedCopyRemembersItsStoredSource() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));
        WaypointGroup first = storedRoomRoute("First", "spider");
        WaypointGroup source = storedRoomRoute("Source", "spider");
        manager.add(first);
        manager.add(source);
        WaypointGroup mirror = new WaypointGroup("dungeon:auto:spider", "Source", "spider");
        mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(source.id());
        manager.add(mirror);

        manager.noteRouteEdited(mirror);

        assertSame(source, manager.lastEditedRoute("spider"));
    }

    @Test
    void routeCreatedInsideADungeonRoomIsADungeonRoute() {
        assertNotNull(DungeonRoomData.entry("spider"));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));

        WaypointGroup created = manager.getOrCreateActiveGroup();

        assertEquals(WaypointGroup.RouteKind.DUNGEON, created.routeKind());
        assertEquals("spider", created.zoneId());
        assertSame(created, manager.lastEditedRoute("spider"));
    }

    @Test
    void routeCreatedOutsideDungeonsStaysRegular() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(WaypointGroup.RouteKind.REGULAR, manager.getOrCreateActiveGroup().routeKind());
    }

    @Test
    void removedLastEditedRouteIsForgotten() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        manager.add(route);
        manager.noteRouteEdited(route);

        manager.remove(route.id());

        assertNull(manager.lastEditedRoute("hub"));
    }

    private static WaypointGroup storedRoomRoute(String name, String roomId) {
        WaypointGroup group = WaypointGroup.create(name, roomId);
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.add(Waypoint.at(1, 70, 1));
        return group;
    }
}
