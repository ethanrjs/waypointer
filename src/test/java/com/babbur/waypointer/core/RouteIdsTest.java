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
        RouteIds.Reference route = RouteIds.parse("@mod1");
        assertEquals("crystal_hollows_structure_mines_of_divan", route.zoneId());
        assertEquals(1, route.routeNumber());
        assertFalse(route.hasWaypoint());
        RouteIds.Reference waypoint = RouteIds.parse(" @MOD:1#15 ");
        assertEquals("crystal_hollows_structure_mines_of_divan", waypoint.zoneId());
        assertEquals("15", waypoint.waypointLabel());
        assertEquals("2.1", RouteIds.parse("@mod12#2.1").waypointLabel());
        assertEquals("hub", RouteIds.parse("@hub3").zoneId());
        for (String bad : List.of("mod:1", "@mod", "@mod:", "@mod0", "@mod1#", "@mod1#x", "@:1", "@mod1 2")) {
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

        assertEquals("@mod1", RouteIds.routeId(route));
        assertEquals("@mod1#1", RouteIds.waypointId(route, 0));
        assertEquals("@mod1#1.1", RouteIds.waypointId(route, 1));
        assertEquals("@mod1#2", RouteIds.waypointId(route, 2));
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

        assertSame(fourth, manager.findRoute(RouteIds.parse("@hub4")));
        assertNull(manager.findRoute(RouteIds.parse("@hub3")), "deleted IDs do not name another route");
        assertNull(manager.findRoute(RouteIds.parse("@hub9")));
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

    @Test
    void noZoneOrRoomIdIsAnotherOnesShortName() {
        // "@end1" must mean one zone whether "end" is read as a short name or as a full ID.
        RouteIds.shortNames().forEach((id, name) -> {
            String owner = RouteIds.zoneId(id);
            assertEquals(id, owner, id + " is also the short name of " + owner);
        });
    }

    @Test
    void anIdWithoutAZoneMeansTheZoneThePlayerIsIn() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hub = WaypointGroup.create("Hub loop", "hub");
        WaypointGroup garden = WaypointGroup.create("Garden", "garden");
        WaypointGroup gardenTwo = WaypointGroup.create("Garden two", "garden");
        manager.add(hub);
        manager.add(garden);
        manager.add(gardenTwo);

        RouteIds.Reference second = RouteIds.parse("@2#3");
        assertTrue(second.currentZone());
        assertEquals("3", second.waypointLabel());
        assertNull(manager.findRoute(second), "no zone yet");
        assertEquals(RouteIds.Miss.NO_CURRENT_ZONE, manager.explainMissingRoute(second));

        manager.onZoneChanged(new Zone("garden", "Garden"));
        assertSame(gardenTwo, manager.findRoute(second));
        assertSame(garden, manager.findRoute(RouteIds.parse("@1")));
        manager.onZoneChanged(new Zone("hub", "Hub"));
        assertSame(hub, manager.findRoute(RouteIds.parse("@1")));
        assertNull(manager.findRoute(second));
        assertEquals(RouteIds.Miss.NUMBER_NOT_IN_ZONE, manager.explainMissingRoute(second));
        assertEquals(RouteIds.Miss.ZONE_HAS_NO_ROUTES, manager.explainMissingRoute(RouteIds.parse("@rift1")));
        for (String bad : List.of("@", "@0", "@#1", "@1#", "@:1")) assertNull(RouteIds.parse(bad), bad);
    }

    @Test
    void aZoneWhoseOwnIdIsAShortNameIsStillReachable() {
        // An unlisted island could report itself as "mod"; its routes must not vanish behind Mines of Divan.
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup odd = WaypointGroup.create("Odd island", "mod");
        manager.add(odd);
        assertSame(odd, manager.findRoute(RouteIds.parse(RouteIds.routeId(odd))));

        WaypointGroup divan = WaypointGroup.create("Divan", "crystal_hollows_structure_mines_of_divan");
        manager.add(divan);
        assertSame(divan, manager.findRoute(RouteIds.parse("@mod1")), "the listed zone wins the short name");
    }

    @Test
    void aZoneIdThatCannotBeTypedHasNoId() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup odd = WaypointGroup.create("Odd", "my island!");
        manager.add(odd);
        assertTrue(odd.routeNumber() > 0);
        assertNull(RouteIds.routeId(odd), "an ID that cannot be parsed back is worse than none");
        assertNull(RouteIds.waypointId(odd, 0));
    }

    @Test
    void zonesEndingInALetterRunIntoTheNumberAndOthersTakeAColon() {
        assertEquals("@fg", RouteIds.routePrefixForZone("crystal_hollows_structure_fairy_grotto"));
        assertEquals("@f7:", RouteIds.routePrefixForZone("dungeon_f7"));
        assertEquals("@mstop1:", RouteIds.routePrefixForZone("mineshaft_topaz_1"));

        WaypointGroup grotto = WaypointGroup.create("Grotto", "crystal_hollows_structure_fairy_grotto");
        grotto.setRouteNumber(2);
        assertEquals("@fg2", RouteIds.routeId(grotto));
        WaypointGroup floor = WaypointGroup.create("Boss", "dungeon_f7");
        floor.setRouteNumber(12);
        assertEquals("@f7:12", RouteIds.routeId(floor));

        RouteIds.Reference compact = RouteIds.parse("@fg2#3.1");
        assertEquals("crystal_hollows_structure_fairy_grotto", compact.zoneId());
        assertEquals(2, compact.routeNumber());
        assertEquals("3.1", compact.waypointLabel());
        assertEquals(compact.zoneId(), RouteIds.parse("@fg:2").zoneId(), "the colon form still works");
        assertEquals("dungeon_f7", RouteIds.parse("@f7:12").zoneId());
        assertEquals(12, RouteIds.parse("@f7:12").routeNumber());
        assertEquals(12, RouteIds.parse("@12").routeNumber());
        assertNull(RouteIds.parse("@12").zoneId());
        assertNull(RouteIds.parse("@fg"));
        assertNull(RouteIds.parse("@fg#1"));
    }
}
