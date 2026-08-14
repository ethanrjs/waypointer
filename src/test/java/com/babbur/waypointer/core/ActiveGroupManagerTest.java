package com.babbur.waypointer.core;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ActiveGroupManagerTest {

    @Test
    void addAllPublishesMultiGroupImportAsOneDataChange() {
        ActiveGroupManager manager = new ActiveGroupManager();
        AtomicInteger dataChanges = new AtomicInteger();
        manager.addDataListener(dataChanges::incrementAndGet);

        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        manager.addAll(List.of(first, second));

        assertEquals(1, dataChanges.get());
        assertEquals(List.of(first, second), List.copyOf(manager.allGroups()));
    }

    @Test
    void retiredDwarvenSurfaceZoneGroupsActivateInThePacketBackedZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = new WaypointGroup(
                "legacy-tunnels", "Legacy Tunnels", "glacite_tunnels");
        manager.add(route);
        manager.onZoneChanged(Zone.fromId("dwarven_mines"));

        assertEquals("dwarven_mines", route.zoneId());
        assertEquals(List.of(route), manager.activeGroups());
        assertEquals(List.of(route), manager.groupsForZone("glacite_tunnels"));
    }

    @Test
    void persistentListenersIgnoreTransientChangesWhileDataListenersSeeAllChanges() {
        ActiveGroupManager manager = new ActiveGroupManager();
        AtomicInteger allChanges = new AtomicInteger();
        AtomicInteger persistentChanges = new AtomicInteger();
        manager.addDataListener(allChanges::incrementAndGet);
        manager.addPersistentDataListener(persistentChanges::incrementAndGet);

        WaypointGroup temp = new WaypointGroup("temp", "Temp", "hub");
        temp.setTemp(true);
        WaypointGroup runtime = new WaypointGroup("runtime", "Runtime", "hub");
        runtime.setRuntimeOnly(true);
        manager.add(temp);
        manager.add(runtime);
        manager.removeAll(List.of(temp.id(), runtime.id()));

        assertEquals(3, allChanges.get());
        assertEquals(0, persistentChanges.get());

        manager.add(WaypointGroup.create("Persistent", "hub"));

        assertEquals(4, allChanges.get());
        assertEquals(1, persistentChanges.get());
    }

    @Test
    void dataListenersCanAddAndRemoveListenersDuringCallback() {
        ActiveGroupManager manager = new ActiveGroupManager();
        List<String> calls = new ArrayList<>();
        Runnable[] first = new Runnable[1];
        first[0] = () -> {
            calls.add("first");
            manager.removeDataListener(first[0]);
            manager.addDataListener(() -> calls.add("added"));
        };
        manager.addDataListener(first[0]);
        manager.addDataListener(() -> calls.add("second"));

        assertDoesNotThrow(manager::fireDataChanged);
        assertEquals(List.of("first", "second"), calls);

        manager.fireDataChanged();

        assertEquals(List.of("first", "second", "second", "added"), calls);
    }

    @Test
    void zoneListenersCanAddAndRemoveListenersDuringCallback() {
        ActiveGroupManager manager = new ActiveGroupManager();
        List<String> calls = new ArrayList<>();
        AtomicReference<Consumer<Zone>> first = new AtomicReference<>();
        Consumer<Zone> listener = zone -> {
            calls.add("first:" + zone.id());
            manager.removeZoneListener(first.get());
            manager.addZoneListener(next -> calls.add("added:" + next.id()));
        };
        first.set(listener);
        manager.addZoneListener(listener);
        manager.addZoneListener(zone -> calls.add("second:" + zone.id()));

        assertDoesNotThrow(() -> manager.onZoneChanged(new Zone("hub", "Hub")));
        assertEquals(List.of("first:hub", "second:hub"), calls);

        manager.onZoneChanged(new Zone("the_park", "The Park"));

        assertEquals(List.of("first:hub", "second:hub", "second:the_park", "added:the_park"),
                calls);
    }

    @Test
    void dataChangeInvalidatesActiveGroupCache() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        manager.add(group);

        List<WaypointGroup> cached = manager.activeGroups();
        assertEquals(1, cached.size());

        group.setEnabled(false);
        assertSame(cached, manager.activeGroups(), "group mutations need an explicit data change");

        manager.fireDataChanged();

        assertEquals(0, manager.activeGroups().size());
    }

    @Test
    void offlineAuthoringFocusSurfacesAndEditsOnlyTheSelectedRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup selected = WaypointGroup.create("Selected", "the_park");
        manager.addAll(List.of(first, selected));

        manager.focusRouteForAuthoring(selected);

        assertEquals(List.of(selected), manager.activeGroups());
        assertSame(selected, manager.getOrCreateActiveGroup());

        manager.focusRouteForAuthoring(null);

        assertTrue(manager.activeGroups().isEmpty());
    }

    @Test
    void detectedZoneTakesPriorityOverOfflineAuthoringFocus() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        WaypointGroup selected = WaypointGroup.create("Selected", "the_park");
        manager.addAll(List.of(hub, selected));
        manager.focusRouteForAuthoring(selected);

        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(List.of(hub), manager.activeGroups());
    }

    @Test
    void waypointPreviewRendersWithoutJoiningPersistedGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup preview = WaypointGroup.create("Preview", "unknown");
        preview.setRuntimeOnly(true);
        preview.setTemp(true);
        preview.add(Waypoint.at(1, 2, 3));

        manager.setWaypointPreview(preview);

        assertEquals(List.of(preview), manager.activeGroups());
        assertFalse(manager.allGroups().contains(preview));

        manager.clearWaypointPreview(preview);

        assertTrue(manager.activeGroups().isEmpty());
    }

    @Test
    void completedDungeonRoomRouteIsHiddenFromActiveGroups() {
        assertNotNull(DungeonRoomData.entry("spider"));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));
        // Runtime mirror: the projected form DungeonRoomRouteSync surfaces for
        // the current room placement. Stored room groups never surface (they
        // hold room-local coordinates); see the test below.
        WaypointGroup group = new WaypointGroup("dungeon:auto:spider", "Room Route", "spider");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setRuntimeOnly(true);
        group.add(Waypoint.at(0, 0, 0));
        manager.add(group);

        assertEquals(1, manager.activeGroups().size());

        group.advancePast(0);
        manager.fireDataChanged();

        assertEquals(0, manager.activeGroups().size());
        assertTrue(manager.allGroups().contains(group));
        assertEquals(List.of(group), manager.completedDungeonRoomGroupsInCurrentZone());
    }

    @Test
    void storedDungeonRoomRoutesActOnlyThroughTheirRuntimeMirror() {
        assertNotNull(DungeonRoomData.entry("spider"));
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));
        WaypointGroup stored = WaypointGroup.create("Room Route", "spider");
        stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        stored.add(Waypoint.at(0, 0, 0));
        manager.add(stored);

        assertEquals(0, manager.activeGroups().size(),
                "room-local stored groups must not render at raw local coordinates");
        assertTrue(manager.allGroups().contains(stored));
    }

    @Test
    void clearTemporaryWaypointsWipesOnlyTempBucketsAndFocus() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3));
        manager.add(route);

        WaypointGroup temp = manager.addTempWaypoint(4, 5, 6, "From Someone");
        manager.focusTempWaypoint(temp, 0);
        assertTrue(manager.tempWaypointFocusActive());

        assertEquals(1, manager.clearTemporaryWaypoints());

        assertEquals(1, route.size(), "permanent route data should be left alone");
        assertEquals(0, temp.size(), "temporary menu bucket should be emptied");
        assertFalse(manager.tempWaypointFocusActive(), "focused temp render mode should be cleared too");
    }

    @Test
    void tempFocusInvalidatesActiveGroupCache() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3));
        manager.add(route);
        WaypointGroup temp = manager.addTempWaypoint(4, 5, 6, "From Someone");

        List<WaypointGroup> cachedRoute = manager.activeGroups();
        assertEquals(List.of(route, temp), cachedRoute);

        manager.focusTempWaypoint(temp, 0);

        assertEquals(List.of(temp), manager.activeGroups());

        manager.clearTempWaypointFocus();

        assertEquals(List.of(route, temp), manager.activeGroups());
    }

    @Test
    void addTempWaypointUsesCallerSuppliedColor() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup temp = manager.addTempWaypoint(4, 5, 6, "From Someone",
                Waypoint.TEMP_TIME, 123_456L, 0xAA112233);

        assertEquals(1, temp.size());
        assertEquals(0x112233, temp.get(0).color());
        assertEquals(Waypoint.TEMP_TIME, temp.get(0).tempMode());
    }

    @Test
    void canFindAndRemoveTempWaypointsByFormattedSender() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));

        WaypointGroup temp = manager.addTempWaypoint(6, 1, 1,
                "\u00A7eFrom \u00A76[MVP\u00A7d++\u00A76] Babbur");
        manager.addTempWaypoint(7, 2, 3, "\u00A7eFrom SomeoneElse");

        ActiveGroupManager.TempWaypointSelection selection =
                manager.findTempWaypoint(6, 1, 1, "babbur");

        assertNotNull(selection);
        assertSame(temp, selection.group());
        assertEquals(0, selection.index());

        assertEquals(1, manager.removeTempWaypointsFromSender("BABBUR"));
        assertEquals(1, temp.size());
        assertEquals("\u00A7eFrom SomeoneElse", temp.get(0).name());
    }

    @Test
    void tempWaypointLookupStaysInTheCurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        manager.addTempWaypoint(6, 1, 1, "From Babbur");
        manager.onZoneChanged(Zone.fromId("the_park"));
        WaypointGroup park = manager.addTempWaypoint(6, 1, 1, "From Babbur");

        ActiveGroupManager.TempWaypointSelection selection =
                manager.findTempWaypoint(6, 1, 1, "Babbur");

        assertNotNull(selection);
        assertSame(park, selection.group());
    }

    @Test
    void foldersOwnLocalMembershipAndReorderOnlyWithinTheirContainer() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = new WaypointGroup("first", "First", "hub");
        WaypointGroup second = new WaypointGroup("second", "Second", "hub");
        WaypointGroup third = new WaypointGroup("third", "Third", "hub");
        manager.addAll(List.of(first, second, third));
        RouteFolder folder = new RouteFolder("folder", "Mining", "hub", false);

        manager.addFolder(folder, List.of(first.id(), second.id()));

        assertEquals(folder, manager.folderForGroup(first.id()));
        assertEquals(List.of(first.id(), second.id()), manager.groupIdsInFolder(folder.id()));
        assertTrue(manager.moveGroupBy(second.id(), -1));
        assertEquals(List.of(second, first, third), manager.allGroupsList());
        assertFalse(manager.moveGroupBy(first.id(), 1),
                "a route cannot cross from its folder into the unfiled list");

        assertTrue(manager.deleteFolder(folder.id()));
        assertNull(manager.folderForGroup(first.id()));
        assertEquals(List.of(second, first, third), manager.allGroupsList(),
                "deleting a folder must not delete or reorder its routes");
    }

    @Test
    void folderColorAndDragMovesPublishOneCanonicalChange() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup filedFirst = new WaypointGroup("filed-first", "Filed First", "hub");
        WaypointGroup filedSecond = new WaypointGroup("filed-second", "Filed Second", "hub");
        WaypointGroup unfiledFirst = new WaypointGroup("free-first", "Free First", "hub");
        WaypointGroup unfiledSecond = new WaypointGroup("free-second", "Free Second", "hub");
        manager.addAll(List.of(filedFirst, filedSecond, unfiledFirst, unfiledSecond));
        RouteFolder folder = new RouteFolder("folder", "Mining", "hub", false);
        manager.addFolder(folder, List.of(filedFirst.id(), filedSecond.id()));
        AtomicInteger persistentChanges = new AtomicInteger();
        manager.addPersistentDataListener(persistentChanges::incrementAndGet);

        assertTrue(manager.setFolderColor(folder.id(), 0xC46DFF));
        assertEquals(0xC46DFF, manager.folder(folder.id()).color());
        assertEquals(1, persistentChanges.get());

        assertTrue(manager.moveGroupToContainer(
                unfiledSecond.id(), folder.id(), filedSecond.id()));
        assertEquals(List.of(filedFirst.id(), unfiledSecond.id(), filedSecond.id()),
                manager.groupIdsInFolder(folder.id()));
        assertEquals(2, persistentChanges.get());

        assertTrue(manager.moveGroupToContainer(
                filedFirst.id(), null, unfiledFirst.id()));
        assertNull(manager.folderForGroup(filedFirst.id()));
        assertEquals(List.of(unfiledSecond, filedSecond, filedFirst, unfiledFirst),
                manager.allGroupsList());
        assertEquals(List.of(unfiledSecond.id(), filedSecond.id()),
                manager.groupIdsInFolder(folder.id()));
        assertEquals(3, persistentChanges.get());
    }

    @Test
    void dragMoveRejectsCrossZoneRuntimeAndMismatchedFolderTargets() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup source = new WaypointGroup("source", "Source", "hub");
        WaypointGroup otherZone = new WaypointGroup("other", "Other", "the_park");
        WaypointGroup runtime = new WaypointGroup("runtime", "Runtime", "hub");
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(source, otherZone, runtime));
        RouteFolder hubFolder = new RouteFolder("hub-folder", "Hub", "hub", false);
        RouteFolder parkFolder = new RouteFolder("park-folder", "Park", "the_park", false);
        manager.addFolder(hubFolder, List.of());
        manager.addFolder(parkFolder, List.of(otherZone.id()));

        assertFalse(manager.canMoveGroupToContainer(
                source.id(), parkFolder.id(), null));
        assertFalse(manager.moveGroupToContainer(
                source.id(), parkFolder.id(), null));
        assertFalse(manager.moveGroupToContainer(
                source.id(), null, otherZone.id()));
        assertFalse(manager.moveGroupToContainer(
                runtime.id(), hubFolder.id(), null));
        assertEquals(List.of(source, otherZone, runtime), manager.allGroupsList());
        assertNull(manager.folderForGroup(source.id()));
    }

    @Test
    void dungeonReorderUsesOnlyPersistedPeersInTheSameRoom() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup regularFirst = new WaypointGroup("regular-first", "First", "hub");
        WaypointGroup roomAFirst = dungeonGroup("room-a-first", "room-a");
        WaypointGroup runtimeProjection = dungeonGroup("runtime", "room-a");
        runtimeProjection.setRuntimeOnly(true);
        WaypointGroup roomBOnly = dungeonGroup("room-b", "room-b");
        WaypointGroup regularSecond = new WaypointGroup("regular-second", "Second", "hub");
        WaypointGroup roomASecond = dungeonGroup("room-a-second", "room-a");
        manager.addAll(List.of(regularFirst, roomAFirst, runtimeProjection,
                roomBOnly, regularSecond, roomASecond));
        RouteFolder folder = new RouteFolder("regular-folder", "Regular", "hub", false);
        manager.addFolder(folder, List.of(regularFirst.id(), regularSecond.id()));

        assertFalse(manager.canMoveGroupBy(roomAFirst.id(), -1));
        assertTrue(manager.canMoveGroupBy(roomAFirst.id(), 1));
        assertFalse(manager.canMoveGroupBy(runtimeProjection.id(), -1));
        assertFalse(manager.canMoveGroupBy(runtimeProjection.id(), 1));
        assertFalse(manager.canMoveGroupBy(roomBOnly.id(), -1));
        assertFalse(manager.canMoveGroupBy(roomBOnly.id(), 1));

        assertTrue(manager.moveGroupBy(roomASecond.id(), -1));
        assertEquals(List.of(regularFirst, roomASecond, runtimeProjection,
                        roomBOnly, regularSecond, roomAFirst),
                manager.allGroupsList());
        assertEquals(List.of(regularFirst.id(), regularSecond.id()),
                manager.groupIdsInFolder(folder.id()),
                "dungeon reordering must not change regular folder membership or order");
        assertFalse(manager.moveGroupBy(roomASecond.id(), -1));
        assertFalse(manager.moveGroupBy(roomAFirst.id(), 1));
    }

    @Test
    void atomicReplacementTransfersValidatedFolderMembershipInOneChange() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup original = new WaypointGroup("old", "Old", "hub");
        WaypointGroup neighbor = new WaypointGroup("neighbor", "Neighbor", "hub");
        manager.addAll(List.of(original, neighbor));
        RouteFolder folder = new RouteFolder("folder", "Catalog", "hub", false);
        manager.addFolder(folder, List.of(original.id(), neighbor.id()));
        AtomicInteger changes = new AtomicInteger();
        manager.addPersistentDataListener(changes::incrementAndGet);
        WaypointGroup replacement = new WaypointGroup("new", "New", "hub");

        manager.replaceGroupsAtomically(
                List.of(original.id()), List.of(replacement),
                Map.of(replacement.id(), folder.id()),
                Map.of(original.id(), replacement.id()));

        assertEquals(1, changes.get());
        assertNull(manager.get(original.id()));
        assertEquals(folder, manager.folderForGroup(replacement.id()));
        assertEquals(List.of(replacement, neighbor), manager.allGroupsList());
        assertEquals(List.of(replacement.id(), neighbor.id()),
                manager.groupIdsInFolder(folder.id()));

        WaypointGroup wrongZone = new WaypointGroup("wrong-zone", "Wrong", "the_park");
        assertThrows(IllegalArgumentException.class, () -> manager.replaceGroupsAtomically(
                List.of(replacement.id()), List.of(wrongZone),
                Map.of(wrongZone.id(), folder.id()),
                Map.of(replacement.id(), wrongZone.id())));
        assertSame(replacement, manager.get(replacement.id()),
                "failed validation must not mutate the live manager");
        assertEquals(folder, manager.folderForGroup(replacement.id()));
    }

    private static WaypointGroup dungeonGroup(String id, String roomId) {
        WaypointGroup group = new WaypointGroup(id, id, roomId);
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        return group;
    }
}
