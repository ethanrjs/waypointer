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

    /*[[AI-FN-DOC
Function:
addTempWaypointUsesCallerSuppliedColor
Purpose:
Verify the config-aware temporary waypoint overload stores the caller's RGB color.
Why this exists:
Default waypoint color now applies to temp waypoint creation paths, and ActiveGroupManager is the shared non-UI insertion seam.
When to use:
Run with core manager tests after changing temp waypoint construction or default color plumbing.
Inputs:
No parameters. Creates an in-memory manager, zone, and temp waypoint.
Outputs:
No return value. Assertions fail if the color is not masked/stored on the new temp waypoint.
Side effects:
Mutates only the local manager and temp group.
Failure modes:
Fails if the overload ignores the supplied color, keeps alpha bits, or inserts into the wrong temp bucket.
Important invariants:
Temp lifecycle behavior must remain unchanged; this overload changes only the waypoint color.
Internal logic:
Create a manager in a known zone, add a temp waypoint with an ARGB value through the new overload, then assert the temp group and waypoint color.
Pseudocode:
manager = new ActiveGroupManager
set zone hub
temp = addTempWaypoint with TEMP_TIME and ARGB color
assert temp has one waypoint
assert waypoint color equals low RGB bits
assert waypoint temp mode remains TEMP_TIME
Implementation notes:
This avoids Minecraft client dependencies while still covering the shared creation method used by commands and chat/keybind flows.
AI self-check:
Verify the test does not depend on renderer or screen classes.
]]*/
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
}
