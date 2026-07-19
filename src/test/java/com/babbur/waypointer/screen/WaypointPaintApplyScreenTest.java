package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointPaintApplyScreenTest {

    @Test
    void eligibleGroupsPutCurrentActiveRoutesFirstAndExcludeRuntimeBuckets() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup otherShown = group("other", "The End", true);
        WaypointGroup active = group("active", "Hub Route", true);
        WaypointGroup hidden = group("hidden", "Hidden Hub", false);
        WaypointGroup temp = group("temp", "Temp", true);
        temp.setTemp(true);
        WaypointGroup runtime = group("runtime", "Runtime", true);
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(otherShown, hidden, temp, active, runtime));
        manager.onZoneChanged(Zone.fromId("hub"));

        assertEquals(List.of("active", "other", "hidden"),
                WaypointPaintApplyScreen.eligibleGroups(manager).stream()
                        .map(WaypointGroup::id).toList());
    }

    @Test
    void groupSearchMatchesNameZoneIdAndFriendlyZoneName() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.add(group("route", "Crystal Run", true));

        assertEquals(1, WaypointPaintApplyScreen.matchingGroups(manager, "crystal run").size());
        assertEquals(1, WaypointPaintApplyScreen.matchingGroups(manager, "the end").size());
        assertEquals(1, WaypointPaintApplyScreen.matchingGroups(manager, "the_end").size());
        assertEquals(0, WaypointPaintApplyScreen.matchingGroups(manager, "hub").size());
    }

    private static WaypointGroup group(String id, String name, boolean enabled) {
        WaypointGroup group = new WaypointGroup(id, name, "the_end");
        if ("active".equals(id) || "hidden".equals(id)) group.setZoneId("hub");
        group.setEnabled(enabled);
        return group;
    }
}
