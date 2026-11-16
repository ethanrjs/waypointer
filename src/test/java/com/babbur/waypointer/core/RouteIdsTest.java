package com.babbur.waypointer.core;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteIdsTest {

    @Test
    void everyZoneAndDungeonRoomHasItsOwnShortName() {
        Set<String> names = new HashSet<>();
        for (Zone zone : Zone.knownZones()) {
            assertTrue(RouteIds.shortNames().containsKey(zone.id()), "no short name for " + zone.id());
            assertTrue(names.add(RouteIds.shortName(zone.id())), "duplicate " + RouteIds.shortName(zone.id()));
        }
        for (var room : DungeonRoomData.allEntries()) {
            assertTrue(RouteIds.roomShortNames().containsKey(room.id()), "no short name for room " + room.id());
            assertTrue(names.add(RouteIds.shortName(room.id())), "duplicate " + RouteIds.shortName(room.id()));
        }
        for (String name : RouteIds.shortNames().values()) {
            assertTrue(name.matches("[a-z0-9]{2,8}"), name);
            assertEquals(name, RouteIds.shortName(RouteIds.zoneId(name)), "round trip " + name);
        }
    }

    @Test
    void shortNamesAndFullZoneIdsBothWork() {
        assertEquals("mod", RouteIds.shortName("crystal_hollows_structure_mines_of_divan"));
        assertEquals("crystal_hollows_structure_mines_of_divan", RouteIds.zoneId("MOD"));
        assertEquals("crystal_hollows", RouteIds.zoneId("crystal_hollows"));
        assertEquals("some_new_island", RouteIds.shortName("some_new_island"));
        assertEquals("spi", RouteIds.shortName("spider"));
        assertEquals("f7", RouteIds.shortName("dungeon_f7"));
    }

    @Test
    void parsesRouteAndWaypointIds() {
        assertEquals(new RouteIds.Reference("crystal_hollows_structure_mines_of_divan", 1, null),
                RouteIds.parse("@mod:1"));
        assertEquals(new RouteIds.Reference("crystal_hollows_structure_mines_of_divan", 1, "15"),
                RouteIds.parse(" @MOD:1#15 "));
        assertEquals("2.1", RouteIds.parse("@mod:12#2.1").waypointLabel());
        assertEquals("hub", RouteIds.parse("@hub:3").zoneId());
        for (String bad : List.of("mod:1", "@mod", "@mod:", "@mod:0", "@mod:1#", "@mod:1#x", "@:1", "@mod:1 2")) {
            assertNull(RouteIds.parse(bad), bad);
        }
    }

    @Test
    void idsUseTheZoneShortNameAndTheEditorsWaypointLabels() {
        WaypointGroup route = WaypointGroup.create("Divan loop", "crystal_hollows_structure_mines_of_divan");
        for (int i = 0; i < 3; i++) route.add(Waypoint.at(i, 70, i));
        assertNull(RouteIds.routeId(route), "no ID before the route is numbered");
        route.setRouteNumber(1);
        route.toggleSubwaypoint(1);

        assertEquals("@mod:1", RouteIds.routeId(route));
        assertEquals("@mod:1#1", RouteIds.waypointId(route, 0));
        assertEquals("@mod:1#1.1", RouteIds.waypointId(route, 1));
        assertEquals("@mod:1#2", RouteIds.waypointId(route, 2));
        assertNull(RouteIds.waypointId(route, 3));
    }

    @Test
    void savedRoutesAreNumberedPerZoneAndNumbersAreNotReused() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        WaypointGroup garden = WaypointGroup.create("Garden", "garden");
        manager.add(first);
        manager.add(second);
        manager.add(garden);
        WaypointGroup temp = manager.getOrCreateTempGroup();

        assertEquals(1, first.routeNumber());
        assertEquals(2, second.routeNumber());
        assertEquals(1, garden.routeNumber());
        assertEquals(0, temp.routeNumber(), "temporary waypoints have no route ID");

        manager.remove(second.id());
        WaypointGroup third = WaypointGroup.create("Third", "hub");
        manager.add(third);
        assertEquals(3, third.routeNumber(), "deleted route numbers stay reserved");
        manager.remove(third.id());
        manager.add(second);
        WaypointGroup fourth = WaypointGroup.create("Fourth", "hub");
        manager.add(fourth);
        assertEquals(4, fourth.routeNumber());

        garden.setZoneId("hub");
        manager.fireDataChanged();
        assertEquals(5, garden.routeNumber(), "a route moved into a zone gets a new number");
        assertEquals(1, first.routeNumber());

        assertSame(fourth, manager.findRoute(RouteIds.parse("@hub:4")));
        assertNull(manager.findRoute(RouteIds.parse("@hub:3")), "deleted IDs do not name another route");
        assertNull(manager.findRoute(RouteIds.parse("@hub:9")));
    }

    @Test
    void masterFloorIdsFindTheirNormalFloorsRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup boss = WaypointGroup.create("Boss", "dungeon_f7");
        manager.add(boss);

        assertSame(boss, manager.findRoute(RouteIds.parse("@f7:1")));
        assertSame(boss, manager.findRoute(RouteIds.parse("@m7:1")));
        assertFalse(RouteIds.parse("@m6:1") == null);
        assertNull(manager.findRoute(RouteIds.parse("@m6:1")));
    }
}
