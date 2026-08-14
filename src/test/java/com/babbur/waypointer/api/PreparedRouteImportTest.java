package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedRouteImportTest {

    @Test
    void preparedImportDetachesAndCommitsAsOneDataChange() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        try (WaypointerHandle ignored = api.onDataChanged(changes::incrementAndGet)) {
            WaypointGroup group = WaypointGroup.create("Catalog route", "hub");
            group.add(new Waypoint(1, 64, 2, "Start", 0x44AA66, 0, 0.0));
            WaypointImporter.ImportResult prepared = new WaypointImporter.ImportResult(
                    WaypointImporter.Source.WAYPOINTER, List.of(group), "Catalog route");

            ImportSummary summary = api.importPreparedRoutes(
                    prepared, ImportOptions.defaults());

            assertEquals(1, changes.get());
            assertEquals(1, summary.groupCount());
            assertEquals(1, summary.waypointCount());
            assertEquals(List.of(group.id()), summary.groupIds());
            group.setName("Changed after commit");
            assertEquals("Catalog route", api.savedRoutes().getFirst().name());
        }
    }

    @Test
    void preparedImportIsRevalidatedBeforeMutation() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        try (WaypointerHandle ignored = api.onDataChanged(changes::incrementAndGet)) {
            WaypointImporter.ImportResult empty = new WaypointImporter.ImportResult(
                    WaypointImporter.Source.WAYPOINTER, List.of(), "Empty");

            assertThrows(IllegalArgumentException.class, () ->
                    api.importPreparedRoutes(empty, ImportOptions.defaults()));
            assertEquals(0, changes.get());
            assertEquals(List.of(), api.savedRoutes());
        }
    }
}
