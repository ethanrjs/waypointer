package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ActiveGroupManagerTest {

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
}
