package com.babbur.waypointer.debug;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteIdDiagnosticsTest {

    @Test
    void reportsZonesNextNumbersAndRoutesWithoutIdsWithoutNames() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_m7", "Master Mode M7"));
        WaypointGroup first = WaypointGroup.create("Secret name", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        WaypointGroup odd = WaypointGroup.create("Odd", "my island!");
        manager.add(first);
        manager.add(second);
        manager.add(odd);
        manager.remove(second.id());

        var report = RouteIdDiagnostics.report(manager);
        String all = String.join("\n", report.summary()) + "\n" + String.join("\n", report.zones())
                + "\n" + String.join("\n", report.problems());
        assertTrue(all.contains("@1 means @m7:1") && all.contains("also finds dungeon_f7"), all);
        assertTrue(all.contains("hub: 1 in use (@hub1), next @hub3, retired 1"), all);
        assertTrue(all.contains("its zone ID cannot be typed"), all);
        assertTrue(!all.contains("Secret name"), "route names stay out of the report");
        assertEquals(1, report.problems().size(), all);
    }
}
