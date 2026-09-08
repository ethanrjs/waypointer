package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomRouteLibraryTest {

    @Test
    void secretRoomCountsIgnoreVariantsEmptyRoomsAndRuntimeCopies() {
        WaypointGroup first = route("first", "arrow-trap", true, true);
        WaypointGroup variant = route("variant", "arrow-trap", true, true);
        WaypointGroup second = route("second", "altar", true, true);
        WaypointGroup zeroSecrets = route("zero", "creeper-beams", true, true);
        WaypointGroup temporary = route("temp", "andesite", true, true);
        temporary.setTemp(true);
        WaypointGroup runtime = route("runtime", "archway", true, true);
        runtime.setRuntimeOnly(true);
        WaypointGroup empty = dungeon("empty", "atlas");
        WaypointGroup regular = route("regular", "banners", false, true);
        WaypointGroup unknown = route("unknown", "custom-room", true, true);

        assertEquals(0, DungeonRoomRouteLibrary.secretRoomCount(null));
        assertEquals(3, DungeonRoomRouteLibrary.secretRoomCount(
                List.of(first, variant, second, zeroSecrets, temporary, runtime, empty, regular, unknown)),
                "unknown room metadata must not be treated as a known zero");
    }

    @Test
    void storedRouteSelectionIgnoresNonDurableAndPrefersEnabled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup regular = route("regular", "room", false, true);
        WaypointGroup empty = dungeon("empty", "room");
        WaypointGroup temp = route("temp", "room", true, true);
        temp.setTemp(true);
        WaypointGroup runtime = route("runtime", "room", true, true);
        runtime.setRuntimeOnly(true);
        WaypointGroup disabled = route("disabled", "room", true, true);
        disabled.setEnabled(false);
        WaypointGroup enabled = route("enabled", "room", true, true);
        manager.addAll(List.of(regular, empty, temp, runtime, disabled, enabled));

        assertNull(DungeonRoomRouteLibrary.storedRouteForRoom(null, "room"));
        assertNull(DungeonRoomRouteLibrary.storedRouteForRoom(manager, null));
        assertSame(enabled, DungeonRoomRouteLibrary.storedRouteForRoom(manager, "room"));
        enabled.setEnabled(false);
        assertSame(disabled, DungeonRoomRouteLibrary.storedRouteForRoom(manager, "room"));
    }

    @Test
    void mirrorResolutionUsesExactSourceThenRoomFallback() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup stored = route("stored", "room", true, true);
        manager.add(stored);
        WaypointGroup mirror = generated("room", stored.id());

        assertSame(stored, DungeonRoomRouteLibrary.storedSourceForMirror(manager, mirror));
        assertSame(stored, DungeonRoomRouteLibrary.durableEditTarget(manager, mirror));
        assertSame(stored, DungeonRoomRouteLibrary.durableEditTarget(manager, stored));
        assertNull(DungeonRoomRouteLibrary.storedSourceForMirror(manager, stored));

        mirror.setRuntimeSourceGroupId("missing");
        assertSame(stored, DungeonRoomRouteLibrary.storedSourceForMirror(manager, mirror));
        stored.setRuntimeOnly(true);
        assertNull(DungeonRoomRouteLibrary.storedSourceForMirror(manager, mirror));
    }

    @Test
    void manualProgressAndEnabledStateStayInSync() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup stored = route("stored", "room", true, true);
        WaypointGroup mirror = generated("room", stored.id());
        mirror.add(Waypoint.at(2, 70, 2));
        manager.addAll(List.of(stored, mirror));

        DungeonRoomRouteLibrary.setManualCurrentIndex(manager, mirror, 1);
        assertEquals(1, stored.currentIndex());
        assertEquals(1, mirror.currentIndex());
        DungeonRoomRouteLibrary.resetManualProgress(manager, stored);
        assertEquals(0, stored.currentIndex());
        assertEquals(0, mirror.currentIndex());

        DungeonRoomRouteLibrary.setRouteEnabled(manager, null, mirror, false);
        assertFalse(stored.enabled());
        assertFalse(mirror.enabled());
        DungeonRoomRouteLibrary.setRouteEnabled(manager, null, stored, true);
        assertTrue(stored.enabled());
        assertTrue(mirror.enabled());
        DungeonRoomRouteLibrary.setRouteEnabled(manager, null, null, true);
        DungeonRoomRouteLibrary.setManualCurrentIndex(null, null, 1);
    }

    @Test
    void importDisablesOnlyExistingDurableRoutesInImportedRooms() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup existing = route("existing", "old", true, true);
        WaypointGroup sameRoom = route("same-room", "new", true, true);
        WaypointGroup regular = route("regular", "hub", false, true);
        WaypointGroup temp = route("temp", "old", true, true);
        temp.setTemp(true);
        WaypointGroup runtime = route("runtime", "old", true, true);
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(existing, sameRoom, regular, temp, runtime));
        WaypointGroup imported = route("imported", "new", false, true);

        assertTrue(DungeonRoomRouteLibrary.installRoutes(null, List.of(imported)).isEmpty());
        assertTrue(DungeonRoomRouteLibrary.installRoutes(manager, List.of()).isEmpty());
        assertEquals(List.of(imported), DungeonRoomRouteLibrary.installRoutes(
                manager, java.util.Arrays.asList(null, dungeon("empty-import", "new"), imported)));

        assertTrue(existing.enabled());
        assertFalse(sameRoom.enabled());
        assertTrue(regular.enabled());
        assertTrue(temp.enabled());
        assertTrue(runtime.enabled());
        assertEquals(WaypointGroup.RouteKind.DUNGEON, imported.routeKind());
    }

    @Test
    void legacyMigrationAddsMissingRoutesAndSkipsInvalidEntries() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup temp = route("temp", "room", false, true);
        temp.setTemp(true);
        manager.add(temp);
        WaypointGroup legacy = route("legacy", "room", false, true);

        assertEquals(0, DungeonRoomRouteLibrary.installMissingLegacyRoutes(null, List.of(legacy)));
        assertEquals(0, DungeonRoomRouteLibrary.installMissingLegacyRoutes(manager, null));
        assertEquals(1, DungeonRoomRouteLibrary.installMissingLegacyRoutes(
                manager, java.util.Arrays.asList(null, dungeon("empty", "room"), legacy)));
        assertEquals(WaypointGroup.RouteKind.DUNGEON, legacy.routeKind());
        assertEquals(0, DungeonRoomRouteLibrary.installMissingLegacyRoutes(
                manager, List.of(legacy)));
    }

    @Test
    void deleteAllRemovesOnlyDurableDungeonRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup dungeon = route("dungeon", "room", true, true);
        WaypointGroup regular = route("regular", "room", false, true);
        WaypointGroup temp = route("temp", "room", true, true);
        temp.setTemp(true);
        WaypointGroup runtime = route("runtime", "room", true, true);
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(dungeon, regular, temp, runtime));

        assertEquals(0, DungeonRoomRouteLibrary.deleteAllDungeonRoutes(null, null));
        assertEquals(1, DungeonRoomRouteLibrary.deleteAllDungeonRoutes(manager, null));
        assertNull(manager.get(dungeon.id()));
        assertSame(regular, manager.get(regular.id()));
        assertSame(temp, manager.get(temp.id()));
        assertSame(runtime, manager.get(runtime.id()));
    }

    @Test
    void progressCarryOverHandlesCompletionChildrenAndMovedIndexes() {
        WaypointGroup previous = route("previous", "room", true, true);
        WaypointGroup next = route("next", "room", true, true);
        previous.setCurrentTargetIndex(previous.size());
        DungeonRoomRouteSync.carryOverProgress(previous, next);
        assertTrue(next.isComplete());

        previous = routeWithChild("previous-child");
        next = routeWithChild("next-child");
        previous.advancePast(0);
        DungeonRoomRouteSync.carryOverProgress(previous, next);
        assertEquals(0, next.activeSubwaypointParentIndex());

        previous = route("previous-moved", "room", true, true);
        previous.setCurrentIndex(1);
        next = route("next-moved", "room", true, true);
        next.set(0, Waypoint.at(99, 70, 99));
        next.set(1, Waypoint.at(1, 70, 1));
        DungeonRoomRouteSync.carryOverProgress(previous, next);
        assertEquals(1, next.currentIndex());

        DungeonRoomRouteSync.carryOverProgress(null, next);
        next.setCurrentTargetIndex(next.size());
        DungeonRoomRouteSync.carryOverProgress(previous, next);
    }

    private static WaypointGroup route(String id, String zone, boolean dungeon, boolean populated) {
        WaypointGroup group = new WaypointGroup(id, id, zone);
        if (dungeon) group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        if (populated) {
            group.add(Waypoint.at(0, 70, 0));
            group.add(Waypoint.at(1, 70, 1));
        }
        return group;
    }

    private static WaypointGroup dungeon(String id, String zone) {
        return route(id, zone, true, false);
    }

    private static WaypointGroup generated(String room, String sourceId) {
        WaypointGroup mirror = route(
                DungeonRoomRouteProjection.generatedGroupId(room), room, true, true);
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(sourceId);
        return mirror;
    }

    private static WaypointGroup routeWithChild(String id) {
        WaypointGroup group = route(id, "room", true, false);
        group.add(Waypoint.at(0, 70, 0));
        group.add(Waypoint.at(1, 70, 1).withSubwaypoint(true));
        group.add(Waypoint.at(2, 70, 2));
        return group;
    }
}
