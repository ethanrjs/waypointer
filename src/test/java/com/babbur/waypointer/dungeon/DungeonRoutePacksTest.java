package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RoutePack;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoutePacksTest {

    private static final RoutePack OLD = new RoutePack("old", "SecretRoutes · Oct 1");
    private static final RoutePack NEW = new RoutePack("new", "FlameOfWar");

    @Test
    void packsListNewestFirstWithUntaggedRoutesLast() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup handMade = room("hand", "altar", null);
        WaypointGroup oldAltar = room("old-altar", "altar", OLD);
        WaypointGroup oldBoss = floor("old-boss", "dungeon_f7", OLD);
        WaypointGroup newAltar = room("new-altar", "altar", NEW);
        WaypointGroup regular = new WaypointGroup("regular", "regular", "hub");
        regular.add(Waypoint.at(0, 70, 0));
        manager.addAll(List.of(handMade, oldAltar, oldBoss, newAltar, regular));

        List<DungeonRoutePacks.Pack> packs = DungeonRoutePacks.list(manager.allGroups());

        assertEquals(List.of("new", "old", DungeonRoutePacks.UNLABELED_KEY),
                packs.stream().map(DungeonRoutePacks.Pack::key).toList());
        assertEquals("FlameOfWar", packs.getFirst().name());
        assertEquals(List.of(oldAltar, oldBoss), packs.get(1).routes());
        assertEquals(1, packs.get(1).roomCount(), "floor routes are not rooms");
        assertTrue(packs.get(2).unlabeled());
    }

    @Test
    void usingAPackSwapsEveryRoomAndFloorOverToIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup oldAltar = room("old-altar", "altar", OLD);
        WaypointGroup oldBeams = room("old-beams", "creeper-beams", OLD);
        WaypointGroup oldBoss = floor("old-boss", "dungeon_f7", OLD);
        WaypointGroup newAltar = room("new-altar", "altar", NEW);
        WaypointGroup newAltarVariant = room("new-altar-2", "altar", NEW);
        WaypointGroup newBoss = floor("new-boss", "dungeon_f7", NEW);
        newAltar.setEnabled(false);
        newAltarVariant.setEnabled(false);
        newBoss.setEnabled(false);
        manager.addAll(List.of(oldAltar, oldBeams, oldBoss, newAltar, newAltarVariant, newBoss));

        DungeonRoutePacks.Pack fresh = pack(manager, "new");
        assertEquals(DungeonRoutePacks.State.HIDDEN, fresh.state());
        DungeonRoutePacks.activate(manager, fresh);

        assertTrue(newAltar.enabled());
        assertFalse(newAltarVariant.enabled(), "one route per room");
        assertTrue(newBoss.enabled());
        assertFalse(oldAltar.enabled());
        assertFalse(oldBoss.enabled());
        assertTrue(oldBeams.enabled(), "rooms the new pack lacks keep the old route");
        assertEquals(DungeonRoutePacks.State.ACTIVE, pack(manager, "new").state());
        DungeonRoutePacks.Pack old = pack(manager, "old");
        assertEquals(DungeonRoutePacks.State.PARTLY_ACTIVE, old.state());
        assertEquals(1, old.activeRoomCount());
    }

    @Test
    void usingAPackKeepsTheVariantAlreadyInUse() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = room("first", "altar", NEW);
        WaypointGroup second = room("second", "altar", NEW);
        first.setEnabled(false);
        manager.addAll(List.of(first, second));

        DungeonRoutePacks.activate(manager, pack(manager, "new"));

        assertFalse(first.enabled());
        assertTrue(second.enabled());
    }

    @Test
    void hideAndDeleteOnlyTouchThatPack() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup oldAltar = room("old-altar", "altar", OLD);
        WaypointGroup newBeams = room("new-beams", "creeper-beams", NEW);
        WaypointGroup newBoss = floor("new-boss", "dungeon_f7", NEW);
        manager.addAll(List.of(oldAltar, newBeams, newBoss));

        assertEquals(2, DungeonRoutePacks.hide(manager, pack(manager, "new")));
        assertFalse(newBeams.enabled());
        assertFalse(newBoss.enabled());
        assertTrue(oldAltar.enabled());

        assertEquals(2, DungeonRoutePacks.delete(manager, pack(manager, "new")));
        assertNull(manager.get("new-beams"));
        assertNull(manager.get("new-boss"));
        assertEquals(oldAltar, manager.get("old-altar"));
    }

    @Test
    void installTagsEveryImportedRouteWithThePack() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup existing = room("existing", "altar", null);
        manager.add(existing);
        WaypointGroup imported = room("imported", "altar", null);
        WaypointGroup boss = floor("boss", "dungeon_f7", null);

        DungeonRoomRouteLibrary.installRoutes(manager, List.of(imported, boss), NEW);

        assertEquals(NEW, imported.routePack());
        assertEquals(NEW, boss.routePack());
        assertNull(existing.routePack());
        assertFalse(existing.enabled());
    }

    @Test
    void packNamesAreCleanedAndIdsRequired() {
        assertEquals("Pack", new RoutePack("id", "​Pack\n").name());
        assertEquals(RoutePack.MAX_NAME_LENGTH, new RoutePack("id", "x".repeat(200)).name().length());
        assertEquals("catalog:abc", RoutePack.catalog("abc", "Title").id());
        assertThrows(IllegalArgumentException.class, () -> new RoutePack(" ", "name"));
    }

    private static DungeonRoutePacks.Pack pack(ActiveGroupManager manager, String key) {
        return DungeonRoutePacks.list(manager.allGroups()).stream()
                .filter(pack -> pack.key().equals(key)).findFirst().orElseThrow();
    }

    private static WaypointGroup room(String id, String room, RoutePack pack) {
        WaypointGroup group = new WaypointGroup(id, id, room);
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.add(Waypoint.at(0, 70, 0));
        group.setRoutePack(pack);
        return group;
    }

    private static WaypointGroup floor(String id, String floor, RoutePack pack) {
        WaypointGroup group = new WaypointGroup(id, id, floor);
        group.add(Waypoint.at(0, 70, 0).withFlags(Waypoint.FLAG_SKIP_ON_INTERACT));
        group.setRoutePack(pack);
        return group;
    }
}
