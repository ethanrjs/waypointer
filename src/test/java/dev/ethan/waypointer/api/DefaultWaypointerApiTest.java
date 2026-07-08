package dev.ethan.waypointer.api;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultWaypointerApiTest {

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
        assertTrue(api.allGroups().stream().anyMatch(WaypointGroupSnapshot::temporary));

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

        assertEquals(WaypointImporter.Source.JSON, summary.source());
        assertEquals(1, summary.groupCount());
        assertEquals(1, summary.waypointCount());
        assertEquals("hub", api.allGroups().get(0).zoneId());
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
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(payload);

        assertEquals("API Export", decoded.label());
        assertEquals(2, decoded.groups().size());
        assertEquals("Second", decoded.groups().get(0).name());
        assertEquals("Stop", decoded.groups().get(0).get(0).name());
        assertEquals("First", decoded.groups().get(1).name());
        assertEquals("Start", decoded.groups().get(1).get(0).name());
        assertEquals(2, changes.get(), "export is read-only and must not notify listeners");
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
        String json = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"categories\""));
        assertTrue(json.contains("\"name\":\"Route\""));
        assertFalse(json.contains("\"name\":\"Start\""));
        assertFalse(payload.startsWith(WaypointCodec.MAGIC));
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
