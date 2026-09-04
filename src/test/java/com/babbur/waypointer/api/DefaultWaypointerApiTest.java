package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.codec.RouteLibraryCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class DefaultWaypointerApiTest {

    @Test
    void public_export_defaults_are_full_fidelity() {
        ExportOptions defaults = ExportOptions.defaults();

        assertTrue(defaults.includeNames());
        assertTrue(defaults.includeColors());
        assertTrue(defaults.includeRadii());
        assertTrue(defaults.includeWaypointFlags());
        assertTrue(defaults.includeGroupMeta());
    }

    @Test
    void publicFlagsMatchCoreBits() {
        assertEquals(Waypoint.FLAG_HIDE_BEACON, WaypointFlags.HIDE_BEACON);
        assertEquals(Waypoint.FLAG_HIDE_NAME, WaypointFlags.HIDE_NAME);
        assertEquals(Waypoint.FLAG_THROUGH_WALL, WaypointFlags.THROUGH_WALL);
        assertEquals(Waypoint.FLAG_LOCKED_COLOR, WaypointFlags.LOCKED_COLOR);
        assertEquals(Waypoint.FLAG_SUBWAYPOINT, WaypointFlags.SUBWAYPOINT);
        assertEquals(Waypoint.FLAG_SMALL_SUBWAYPOINT, WaypointFlags.SMALL_SUBWAYPOINT);
        assertEquals(Waypoint.FLAG_FILLED_SUBWAYPOINT, WaypointFlags.FILLED_SUBWAYPOINT);
        assertEquals(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                WaypointFlags.HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED);
        assertEquals(Waypoint.FLAG_DEPTH_CHECKED, WaypointFlags.DEPTH_CHECKED);
        assertEquals(Waypoint.FLAG_SKIP_ON_STAND, WaypointFlags.SKIP_ON_STAND);
        assertEquals(Waypoint.FLAG_SKIP_ON_INTERACT, WaypointFlags.SKIP_ON_INTERACT);
        assertEquals(Waypoint.FLAG_DISABLED, WaypointFlags.DISABLED);
        assertEquals(Waypoint.SUBWAYPOINT_STYLE_FLAGS, WaypointFlags.SUBWAYPOINT_STYLE);
        assertEquals(Waypoint.STRUCTURAL_FLAGS, WaypointFlags.STRUCTURAL);

        int combined = WaypointFlags.of(WaypointFlags.THROUGH_WALL, WaypointFlags.SKIP_ON_INTERACT);
        assertTrue(WaypointFlags.contains(combined, WaypointFlags.THROUGH_WALL));
        assertFalse(WaypointFlags.contains(combined, WaypointFlags.HIDE_NAME));
    }

    @Test
    void publicImportSourcesExhaustivelyMirrorImporterSources() {
        assertEquals(
                java.util.Arrays.stream(WaypointImporter.Source.values()).map(Enum::name).toList(),
                java.util.Arrays.stream(ImportSource.values()).map(Enum::name).toList());
        for (WaypointImporter.Source source : WaypointImporter.Source.values()) {
            assertEquals(source.name(), ImportSource.from(source).name());
        }
    }

    @Test
    void publicSpecsBoundUnsafeReachRadii() {
        RouteSpec route = RouteSpec.builder()
                .defaultRadius(Double.POSITIVE_INFINITY)
                .waypoint(WaypointSpec.at(0, 64, 0).radius(1_000_000.0))
                .build();

        assertEquals(Waypoint.DEFAULT_REACH_RADIUS, route.defaultRadius());
        assertEquals(Waypoint.MAX_REACH_RADIUS, route.waypoints().get(0).radius());
    }

    @Test
    void publicSpecsExposeExactSixteenthBlockCoordinates() {
        WaypointSpec spec = WaypointSpec.atPreciseSixteenths(17, 34, -47)
                .name("exact");

        assertEquals(1, spec.x());
        assertEquals(2, spec.y());
        assertEquals(-3, spec.z());

        WaypointerApi api = new DefaultWaypointerApi(new ActiveGroupManager());
        api.createRoute(RouteSpec.builder().name("Route").waypoint(spec).build());
        WaypointSnapshot snapshot = api.savedRoutes().get(0).waypoints().get(0);

        assertEquals(17, snapshot.preciseX());
        assertEquals(34, snapshot.preciseY());
        assertEquals(-47, snapshot.preciseZ());
    }

    @Test
    void publicSpecsRejectNamesThatCannotBePersistedOrExported() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteSpec.builder().name("bad\nroute").build());
        assertThrows(IllegalArgumentException.class,
                () -> RouteSpec.builder()
                        .waypoint(WaypointSpec.at(0, 0, 0).name("\u00A7cformatted"))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> RouteSpec.builder()
                        .waypoint(WaypointSpec.at(0, 0, 0).name("x".repeat(257)))
                        .build());

        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String groupId = api.createRoute(RouteSpec.builder().name("safe").build());
        assertThrows(IllegalArgumentException.class,
                () -> api.addWaypoint(groupId,
                        WaypointSpec.at(0, 0, 0).name("bad\nwaypoint")));
        assertEquals(0, manager.get(groupId).size());
    }

    @Test
    void createRoute_returnsImmutableSnapshotData() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);

        String groupId = api.createRoute(RouteSpec.builder()
                .name("Mining")
                .zoneId("dwarven_mines")
                .loadMode(RouteLoadMode.SEQUENCE)
                .waypoint(WaypointSpec.at(1, 2, 3).name("start"))
                .build());
        WaypointGroupSnapshot snapshot = api.allGroups().get(0);

        manager.get(groupId).add(Waypoint.at(4, 5, 6));

        assertEquals(groupId, snapshot.id());
        assertEquals(RouteLoadMode.SEQUENCE, snapshot.loadMode());
        assertEquals(1, snapshot.waypoints().size(), "snapshot must not track later mutations");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.waypoints().add(WaypointSnapshot.from(Waypoint.at(0, 0, 0))));
    }

    @Test
    void routeMutationsFireDataChanged() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);

        String groupId = api.createRoute(RouteSpec.builder().name("Route").build());
        assertTrue(api.addWaypoint(groupId, WaypointSpec.at(1, 2, 3)));
        assertTrue(api.removeRoute(groupId));

        assertEquals(3, changes.get());
    }

    @Test
    void updateWaypointReplacesOneWaypointAndFiresDataChanged() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);

        String groupId = api.createRoute(RouteSpec.builder()
                .name("Route")
                .waypoint(WaypointSpec.at(1, 2, 3).name("old"))
                .waypoint(WaypointSpec.at(4, 5, 6))
                .build());

        assertTrue(api.updateWaypoint(groupId, 0, WaypointSpec.builder()
                .position(10, 11, 12)
                .name("new")
                .color(0x123456)
                .radius(4.5)
                .build()));
        assertFalse(api.updateWaypoint(groupId, -1, WaypointSpec.at(0, 0, 0)));
        assertFalse(api.updateWaypoint(groupId, 2, WaypointSpec.at(0, 0, 0)));
        assertFalse(api.updateWaypoint("missing", 0, WaypointSpec.at(0, 0, 0)));

        WaypointSnapshot updated = api.allGroups().get(0).waypoints().get(0);
        assertEquals(10, updated.x());
        assertEquals(11, updated.y());
        assertEquals(12, updated.z());
        assertEquals("new", updated.name());
        assertEquals(0x123456, updated.color());
        assertEquals(4.5, updated.radius());
        assertEquals(2, changes.get(), "invalid updates must not notify listeners");
    }

    @Test
    void optimisticWaypointReferencesFailSafelyAfterUserEdits() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);
        String groupId = api.createRoute(RouteSpec.builder()
                .name("Route")
                .waypoint(WaypointSpec.at(1, 2, 3).name("first"))
                .waypoint(WaypointSpec.at(4, 5, 6).name("second"))
                .build());
        WaypointReference stale = api.savedRoutes().get(0).waypointReferences().get(0);

        WaypointGroup live = manager.get(groupId);
        Waypoint first = live.get(0);
        live.remove(0);
        live.add(first);

        assertFalse(api.updateWaypoint(stale, WaypointSpec.at(9, 9, 9)));
        assertFalse(api.removeWaypoint(stale));
        assertEquals("second", live.get(0).name());
        assertEquals(1, changes.get(), "stale references must not notify listeners");
    }

    @Test
    void optimisticWaypointReferencesDetectPreciseEditsWithinTheSameBlock() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String groupId = api.createRoute(RouteSpec.builder()
                .name("Route")
                .waypoint(WaypointSpec.atPreciseSixteenths(17, 34, 51))
                .build());
        WaypointReference stale = api.savedRoutes().get(0).waypointReferences().get(0);

        WaypointGroup live = manager.get(groupId);
        live.set(0, live.get(0).withPreciseSixteenths(18, 34, 51));

        assertEquals(1, live.get(0).x(), "the edit must remain inside the same block");
        assertFalse(api.updateWaypoint(stale, WaypointSpec.at(9, 9, 9)));
        assertFalse(api.removeWaypoint(stale));
    }

    @Test
    void optimisticWaypointReferencesUpdateAndRemoveSavedData() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);
        api.createRoute(RouteSpec.builder()
                .name("Route")
                .waypoint(WaypointSpec.at(1, 2, 3).name("first"))
                .waypoint(WaypointSpec.at(4, 5, 6).name("second"))
                .build());

        WaypointReference first = api.savedRoutes().get(0).waypointReferences().get(0);
        assertTrue(api.updateWaypoint(first, WaypointSpec.at(7, 8, 9).name("updated")));
        WaypointReference second = api.savedRoutes().get(0).waypointReferences().get(1);
        assertTrue(api.removeWaypoint(second));

        assertEquals(List.of("updated"), api.savedRoutes().get(0).waypoints().stream()
                .map(WaypointSnapshot::name)
                .toList());
        assertEquals(3, changes.get());
    }

    @Test
    void savedRouteMutationsRejectTemporaryAndRuntimeGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        WaypointGroup temp = new WaypointGroup("temp", "Temp", "hub");
        temp.setTemp(true);
        temp.add(Waypoint.at(1, 2, 3).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        WaypointGroup runtime = new WaypointGroup("runtime", "Runtime", "hub");
        runtime.setRuntimeOnly(true);
        runtime.add(Waypoint.at(4, 5, 6));
        manager.add(temp);
        manager.add(runtime);

        assertTrue(api.savedRoutes().isEmpty());
        assertFalse(api.removeRoute(temp.id()));
        assertFalse(api.addWaypoint(runtime.id(), WaypointSpec.at(7, 8, 9)));
        assertFalse(api.updateWaypoint(temp.id(), 0, WaypointSpec.at(7, 8, 9)));
        assertFalse(api.removeWaypoint(api.allGroups().stream()
                .filter(group -> group.id().equals(runtime.id()))
                .findFirst().orElseThrow().waypointReferences().get(0)));
        assertEquals(2, api.allGroups().size());
    }

    @Test
    void addTempWaypointUsesSessionOnlyTempBucket() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerApi api = new DefaultWaypointerApi(manager);

        WaypointGroupSnapshot bucket = api.addTempWaypoint(WaypointSpec.builder()
                .position(100, 64, -20)
                .name("Burrow")
                .color(0xFFD166)
                .source("Example Mod")
                .build());

        assertEquals("Temporary", bucket.name());
        assertTrue(bucket.temporary());
        assertEquals(1, bucket.waypoints().size());
        assertEquals("Burrow", bucket.waypoints().get(0).name());
        assertTrue(bucket.waypoints().get(0).temporary());
        assertEquals(0xFFD166, bucket.waypoints().get(0).color());
    }

    @Test
    void addTempWaypointUsesSourceAsFallbackLabel() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerApi api = new DefaultWaypointerApi(manager);

        WaypointGroupSnapshot bucket = api.addTempWaypoint(WaypointSpec.builder()
                .position(100, 64, -20)
                .source("Example Mod")
                .build());

        assertEquals("Example Mod", bucket.waypoints().get(0).name());
        assertTrue(bucket.waypoints().get(0).temporary());
    }

    @Test
    void managedTempHandlesRemoveOnlyTheirExactWaypoint() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerApi api = new DefaultWaypointerApi(manager);
        WaypointSpec identical = WaypointSpec.at(10, 64, 10).name("Marker");

        WaypointerHandle first = api.showTempWaypoint(identical);
        WaypointerHandle second = api.showTempWaypoint(identical);
        assertEquals(2, manager.get("temp::hub").size());

        first.close();
        first.close();
        assertEquals(1, manager.get("temp::hub").size());

        second.close();
        assertEquals(0, manager.get("temp::hub").size());
    }

    @Test
    void routeOverlayCanBeClosedWithoutTouchingUserRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String userRouteId = api.createRoute(RouteSpec.builder().name("User Route").build());

        WaypointerHandle overlay = api.showRouteOverlay(RouteOverlaySpec.builder()
                .name("Overlay")
                .zoneId("hub")
                .waypoint(WaypointSpec.at(1, 2, 3))
                .build());

        assertEquals(2, api.allGroups().size());
        String overlayId = api.allGroups().stream()
                .filter(WaypointGroupSnapshot::temporary)
                .findFirst().orElseThrow().id();
        assertFalse(api.removeRoute(overlayId), "saved-route mutation must not remove an overlay");

        overlay.close();
        overlay.close();

        assertEquals(1, api.allGroups().size());
        assertEquals(userRouteId, api.allGroups().get(0).id());
    }

    @Test
    void importRoutesCanRetargetUnknownGroupsToCurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerApi api = new DefaultWaypointerApi(manager);

        ImportSummary summary = api.importRoutes(
                "[{\"x\":1,\"y\":2,\"z\":3,\"name\":\"p\"}]",
                ImportOptions.builder().targetCurrentZoneWhenUnknown(true).build());

        assertEquals(ImportSource.JSON, summary.source());
        assertEquals(1, summary.groupCount());
        assertEquals(1, summary.waypointCount());
        assertEquals("hub", api.allGroups().get(0).zoneId());
    }

    @Test
    void publicImportRetargetsUnknownMineshaftToTheResolvedCurrentLayout() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("mineshaft_ruby_1"));
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String skyblocker = "[Skyblocker-Waypoint-Data-V1]"
                + "H4sIAAAAAAAC/4uuVspLzE1VslIKzkhMK1Eoyi8tSVXSUcoszknMSwEK52bmpRaD"
                + "pICC5YmVBfmZeSXFSlbR1UoF+SDa0EDHzETHyCBWB2aQo1JtbG0sAA/1L6tZAAAA";

        ImportSummary summary = api.importRoutes(skyblocker,
                ImportOptions.builder().targetCurrentZoneWhenUnknown(true).build());

        assertEquals(ImportSource.SKYBLOCKER, summary.source());
        assertEquals("mineshaft_ruby_1", api.allGroups().getFirst().zoneId());
    }

    @Test
    void importRoutesBatchesDataChangeForMultipleGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);

        ImportSummary summary = api.importRoutes("""
                [
                  {"name":"A","island":"hub","waypoints":[{"x":1,"y":2,"z":3}]},
                  {"name":"B","island":"hub","waypoints":[{"x":4,"y":5,"z":6}]}
                ]
                """, ImportOptions.defaults());

        assertEquals(2, summary.groupCount());
        assertEquals(2, api.allGroups().size());
        assertEquals(1, changes.get(), "bulk import should notify after the whole import lands");
    }

    @Test
    void exportRoutesRoundTripsSelectedGroupsWithoutDataChange() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger changes = new AtomicInteger();
        api.onDataChanged(changes::incrementAndGet);
        String firstId = api.createRoute(RouteSpec.builder()
                .name("First")
                .zoneId("hub")
                .waypoint(WaypointSpec.at(1, 2, 3).name("Start"))
                .build());
        String secondId = api.createRoute(RouteSpec.builder()
                .name("Second")
                .zoneId("dwarven_mines")
                .waypoint(WaypointSpec.at(4, 5, 6).name("Stop"))
                .build());

        String payload = api.exportRoutes(List.of("missing", secondId, firstId),
                ExportOptions.builder()
                        .includeNames(true)
                        .label("API Export")
                        .build());
        WaypointImporter.ImportResult decoded = WaypointImporter.importAny(payload);

        assertEquals("API Export", decoded.label());
        assertEquals(2, decoded.groups().size());
        assertEquals("Second", decoded.groups().get(0).name());
        assertEquals("Stop", decoded.groups().get(0).get(0).name());
        assertEquals("First", decoded.groups().get(1).name());
        assertEquals("Start", decoded.groups().get(1).get(0).name());
        assertEquals(2, changes.get(), "export is read-only and must not notify listeners");
    }

    @Test
    void publicAllOffMultiRegularExportUsesTheBareRoutePack() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String firstId = api.createRoute(RouteSpec.builder()
                .name("Discarded First")
                .zoneId("hub")
                .waypoint(WaypointSpec.at(1, 2, 3).name("Discarded point"))
                .build());
        String secondId = api.createRoute(RouteSpec.builder()
                .name("Discarded Second")
                .zoneId("dwarven_mines")
                .waypoint(WaypointSpec.at(4, 5, 6).name("Discarded point"))
                .build());
        ExportOptions allOff = ExportOptions.builder()
                .includeNames(false)
                .includeColors(false)
                .includeRadii(false)
                .includeWaypointFlags(false)
                .includeGroupMeta(false)
                .includeZone(false)
                .build();

        String payload = api.exportRoutes(List.of(firstId, secondId), allOff);
        var debug = WaypointCodec.debugDecode(payload);
        List<WaypointGroup> decoded = WaypointCodec.decode(payload);

        assertEquals(10, debug.version());
        assertTrue(debug.groups().stream().allMatch(group ->
                group.coordMode().startsWith("V10_BARE_PACK_")));
        assertEquals(2, decoded.size());
        assertEquals("", decoded.get(0).name());
        assertEquals("", decoded.get(1).name());
        assertEquals(Waypoint.at(1, 2, 3), decoded.get(0).get(0));
        assertEquals(Waypoint.at(4, 5, 6), decoded.get(1).get(0));
    }

    @Test
    void exportRoutesCanUseSkytilsTarget() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        String groupId = api.createRoute(RouteSpec.builder()
                .name("Route")
                .zoneId("hub")
                .waypoint(WaypointSpec.at(1, 2, 3).name("Start"))
                .build());

        String payload = api.exportRoutes(List.of(groupId),
                ExportOptions.builder()
                        .target(ExportTarget.SKYTILS)
                        .includeNames(true)
                        .build());
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(payload);

        assertEquals(WaypointImporter.Source.SKYTILS, imported.source());
        assertEquals(1, imported.groups().size());
        assertEquals("Route", imported.groups().get(0).name());
        assertEquals("Start", imported.groups().get(0).get(0).name());
        assertFalse(payload.startsWith(WaypointCodec.MAGIC));
    }

    @Test
    void chunkLoggerExportRoundTripsThroughSecondPublicApiWithSubwaypointStructure() {
        ActiveGroupManager sourceManager = new ActiveGroupManager();
        WaypointerApi sourceApi = new DefaultWaypointerApi(sourceManager);
        String groupId = sourceApi.createRoute(RouteSpec.builder()
                .name("Route")
                .waypoint(WaypointSpec.at(10, 64, -2))
                .waypoint(WaypointSpec.at(11, 62, 1).flags(WaypointFlags.SUBWAYPOINT))
                .build());

        String payload = sourceApi.exportRoutes(List.of(groupId),
                ExportOptions.builder().target(ExportTarget.CHUNKLOGGER).build());
        ActiveGroupManager targetManager = new ActiveGroupManager();
        WaypointerApi targetApi = new DefaultWaypointerApi(targetManager);
        ImportSummary summary = targetApi.importRoutes(payload);

        assertTrue(payload.contains("\"coal\""));
        assertEquals(ImportSource.CHUNKLOGGER, summary.source());
        assertEquals(1, summary.groupCount());
        assertEquals(2, summary.waypointCount());

        List<WaypointSnapshot> imported = targetApi.savedRoutes().getFirst().waypoints();
        assertEquals(List.of(10, 64, -2),
                List.of(imported.get(0).x(), imported.get(0).y(), imported.get(0).z()));
        assertEquals(0, imported.get(0).flags());
        assertEquals(List.of(11, 62, 1),
                List.of(imported.get(1).x(), imported.get(1).y(), imported.get(1).z()));
        assertEquals(WaypointFlags.SUBWAYPOINT, imported.get(1).flags());
    }

    @Test
    void nativeApiExportAndImportPreserveRouteLibraryMetadata() {
        ActiveGroupManager sourceManager = new ActiveGroupManager();
        WaypointerApi sourceApi = new DefaultWaypointerApi(sourceManager);
        String groupId = sourceApi.createRoute(RouteSpec.builder()
                .name("Library Route")
                .zoneId("hub")
                .waypoint(WaypointSpec.at(1, 2, 3).color(0x112233))
                .build());
        WaypointGroup live = sourceManager.get(groupId);
        live.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        live.set(0, live.get(0).withColor(0xABCDEF));
        live.setGradientMode(WaypointGroup.GradientMode.STATIC);
        sourceManager.addFolder(new RouteFolder(
                "source-folder", "Library", "hub", true, 0x2468AC),
                List.of(groupId));

        String payload = sourceApi.exportRoutes(
                List.of(groupId), ExportOptions.defaults());
        ActiveGroupManager targetManager = new ActiveGroupManager();
        WaypointerApi targetApi = new DefaultWaypointerApi(targetManager);
        ImportSummary summary = targetApi.importRoutes(payload, ImportOptions.defaults());

        assertTrue(payload.startsWith(WaypointCodec.MAGIC),
                "library metadata rides inside the universal WP: share");
        assertFalse(payload.startsWith(RouteLibraryCodec.MAGIC));
        assertEquals(1, summary.groupIds().size());
        String importedId = summary.groupIds().getFirst();
        WaypointGroup imported = targetManager.get(importedId);
        assertEquals(0xABCDEF, imported.manualColorSnapshot().getFirst());
        RouteFolder folder = targetManager.folderForGroup(importedId);
        assertEquals("Library", folder.name());
        assertEquals(0x2468AC, folder.color());
        assertTrue(folder.collapsed());
        assertNotEquals("source-folder", folder.id());
    }

    @Test
    void listenerHandlesUnsubscribeCleanly() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger dataChanges = new AtomicInteger();
        AtomicInteger zoneChanges = new AtomicInteger();

        WaypointerHandle dataHandle = api.onDataChanged(dataChanges::incrementAndGet);
        WaypointerHandle zoneHandle = api.onZoneChanged(zone -> zoneChanges.incrementAndGet());
        dataHandle.close();
        dataHandle.close();
        zoneHandle.close();
        zoneHandle.close();

        api.createRoute(RouteSpec.builder().name("Route").build());
        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(0, dataChanges.get());
        assertEquals(0, zoneChanges.get());
    }

    @Test
    void optionalZoneListenerNeverReceivesNull() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        List<String> zones = new ArrayList<>();
        api.onZoneStateChanged(zone -> zones.add(zone.map(ZoneSnapshot::id).orElse("none")));

        manager.onZoneChanged(new Zone("hub", "Hub"));
        manager.onZoneChanged(null);

        assertEquals(List.of("hub", "none"), zones);
        assertTrue(api.currentZoneOptional().isEmpty());
    }

    @Test
    void rejectedImportIsAtomic() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        api.createRoute(RouteSpec.builder().name("Existing").build());

        assertThrows(IllegalArgumentException.class, () -> api.importRoutes("not waypoint data"));
        assertEquals(List.of("Existing"), api.savedRoutes().stream()
                .map(WaypointGroupSnapshot::name)
                .toList());
    }

    @Test
    void rejectedImportDoesNotDispatchParsingToTheClientExecutor() {
        AtomicInteger dispatches = new AtomicInteger();
        WaypointerApi api = new DefaultWaypointerApi(
                new ActiveGroupManager(),
                () -> false,
                action -> {
                    dispatches.incrementAndGet();
                    action.run();
                });

        assertThrows(IllegalArgumentException.class, () -> api.importRoutes("not waypoint data"));

        assertEquals(0, dispatches.get());
    }

    @Test
    void offThreadCallsRunSynchronouslyOnConfiguredClientExecutor() throws Exception {
        AtomicReference<Thread> clientThread = new AtomicReference<>();
        ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
        try {
            clientExecutor.submit(() -> clientThread.set(Thread.currentThread())).get();
            ActiveGroupManager manager = new ActiveGroupManager();
            WaypointerApi api = new DefaultWaypointerApi(
                    manager,
                    () -> Thread.currentThread() == clientThread.get(),
                    clientExecutor);
            AtomicReference<Thread> listenerThread = new AtomicReference<>();
            AtomicInteger changes = new AtomicInteger();
            WaypointerHandle handle = api.onDataChanged(() -> {
                listenerThread.set(Thread.currentThread());
                changes.incrementAndGet();
            });

            String routeId = api.createRoute(RouteSpec.builder().name("Threaded route").build());

            assertNotNull(routeId);
            assertSame(clientThread.get(), listenerThread.get());
            assertEquals(1, changes.get());
            assertEquals("Threaded route", api.allGroups().get(0).name());

            handle.close();
            api.createRoute(RouteSpec.builder().name("After close").build());
            assertEquals(1, changes.get(), "closing off-thread must remove on the client thread before returning");
        } finally {
            clientExecutor.shutdownNow();
        }
    }

    @Test
    void failingPublicListenersDoNotAbortOtherListenersOrInternalUpdates() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        AtomicInteger dataChanges = new AtomicInteger();
        AtomicInteger persistentChanges = new AtomicInteger();
        AtomicInteger zoneChanges = new AtomicInteger();
        api.onDataChanged(() -> {
            throw new IllegalStateException("broken data consumer");
        });
        api.onDataChanged(dataChanges::incrementAndGet);
        api.onZoneChanged(zone -> {
            throw new IllegalStateException("broken zone consumer");
        });
        api.onZoneChanged(zone -> zoneChanges.incrementAndGet());
        manager.addPersistentDataListener(persistentChanges::incrementAndGet);

        api.createRoute(RouteSpec.builder().name("Survives listener").build());
        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(1, dataChanges.get());
        assertEquals(1, persistentChanges.get());
        assertEquals(1, zoneChanges.get());
        assertEquals("Survives listener", api.allGroups().get(0).name());
    }

    @Test
    void entrypointInvokerContinuesAfterFailures() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerApi api = new DefaultWaypointerApi(manager);
        List<String> failures = new ArrayList<>();
        AtomicInteger successful = new AtomicInteger();

        int invoked = WaypointerApiEntrypoints.invokeEntrypoints(List.of(
                WaypointerApiEntrypoints.named("ok-a", ignored -> successful.incrementAndGet()),
                WaypointerApiEntrypoints.named("bad", ignored -> {
                    throw new IllegalStateException("boom");
                }),
                WaypointerApiEntrypoints.named("ok-b", ignored -> successful.incrementAndGet())
        ), api, (modId, error) -> failures.add(modId + ":" + error.getMessage()));

        assertEquals(2, invoked);
        assertEquals(2, successful.get());
        assertEquals(List.of("bad:boom"), failures);
    }
}
