package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRouteInstallerTest {
    private static final String API_ROOT = "https://catalog.example/api/";
    private static final String HASH_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Test
    void decodesPinnedCatalogV9PayloadWithoutMutatingLocalRoutes() {
        WaypointGroup group = WaypointGroup.create("Museum route", "hub");
        group.add(new Waypoint(12, 70, -4, "Start", 0x44AA66, 0, 0.0));
        String payload = WaypointCodec.encodeCatalog(List.of(group));
        CatalogRouteDetails details = new CatalogRouteDetails(summary(1, 1), payload);

        List<WaypointGroup> decoded = CatalogRouteInstaller.decodeForPreview(details);

        assertEquals(1, decoded.size());
        assertEquals("Museum route", decoded.getFirst().name());
        assertEquals(1, decoded.getFirst().size());
        assertEquals("Start", decoded.getFirst().waypoints().getFirst().name());
    }

    @Test
    void rejectsPayloadWhenCatalogCountsDoNotMatch() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(0, 64, 0, "Point", 0xFFFFFF, 0, 0.0));
        String payload = WaypointCodec.encodeCatalog(List.of(group));

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.decodeForPreview(
                         new CatalogRouteDetails(summary(2, 1), payload)));
    }

    @Test
    void rejectsLegacyNoncanonicalAndMislabeledCatalogPayloads() {
        WaypointGroup group = route("hub", 1);
        String v9 = WaypointCodec.encodeCatalog(List.of(group));

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(1, 1, "hub", 8), v9)));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(1, 1), v9 + " ")));
    }

    @Test
    void derivesAndChecksEmbeddedCatalogZones() {
        WaypointGroup hub = route("hub", 1);
        WaypointGroup park = route("the_park", 2);
        String payload = WaypointCodec.encodeCatalog(List.of(hub, park));

        CatalogRouteInstaller.PreparedRoute prepared = CatalogRouteInstaller.prepare(
                new CatalogRouteDetails(summary(3, 2, "multiple", 9), payload));
        assertEquals(2, prepared.previewGroups().size());

        assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(3, 2, "hub", 9), payload)));
    }

    @Test
    void enforcesRegularGroupAndWaypointLimits() {
        List<WaypointGroup> groups = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> route("hub", 1))
                .toList();
        String payload = WaypointCodec.encodeCatalog(groups);
        assertThrows(IllegalArgumentException.class, () -> CatalogRouteInstaller.prepare(
                new CatalogRouteDetails(summary(65, 65), payload)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(25_001, 2),
                        WaypointCodec.encodeCatalog(List.of(route("hub", 12_501), route("hub", 12_500))))));
        assertTrue(failure.getMessage().contains("install limits"));
    }

    @Test
    void dungeonPackInstallsEditableRoomsWithProvenanceAndUpdatesTogether() {
        List<WaypointGroup> rooms = java.util.stream.IntStream.range(0, 109).mapToObj(index -> {
            WaypointGroup room = route("room-" + (index % 108), 2);
            room.setName("Route " + index);
            room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            room.set(1, room.get(1).withFlags(Waypoint.FLAG_SUBWAYPOINT
                    | Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_FILLED_SUBWAYPOINT));
            return room;
        }).toList();
        CatalogRouteDetails first = new CatalogRouteDetails(summary(218, 109, "dungeon", 2),
                DungeonRoomShareCodec.encodeCatalog(rooms));
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup old = route("room-0", 1);
        old.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup unrelated = route("other-room", 1);
        unrelated.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        manager.addAll(List.of(old, unrelated));
        AtomicInteger changes = new AtomicInteger();
        manager.addPersistentDataListener(changes::incrementAndGet);

        var installed = CatalogRouteInstaller.install(manager, API_ROOT, first);

        assertEquals(1, changes.get());
        assertEquals(109, installed.groupCount());
        assertEquals(111, manager.allGroups().size());
        assertEquals(false, old.enabled());
        assertTrue(unrelated.enabled());
        for (int index = 0; index < rooms.size(); index++) {
            WaypointGroup copy = manager.get(installed.groupIds().get(index));
            assertEquals(WaypointGroup.RouteKind.DUNGEON, copy.routeKind());
            assertEquals(rooms.get(index).zoneId(), copy.zoneId());
            assertEquals(rooms.get(index).waypoints(), copy.waypoints());
            assertEquals(index, copy.catalogProvenance().groupIndex());
            assertEquals(109, copy.catalogProvenance().groupCount());
            assertEquals(false, copy.temp());
            assertEquals(false, copy.runtimeOnly());
        }
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(API_ROOT, first.summary(), manager.allGroups()).action());
        WaypointGroup edited = manager.get(installed.groupIds().get(1));
        edited.set(0, edited.get(0).withName("My edit").withColor(0x123456));
        assertEquals("My edit", edited.get(0).name());
        edited.setEnabled(false);

        CatalogRouteDetails second = new CatalogRouteDetails(summary(218, 109, "dungeon", 2, 2),
                first.payload());
        changes.set(0);
        var updated = CatalogRouteInstaller.install(manager, API_ROOT, second);
        assertEquals(1, changes.get());
        WaypointGroup replacement = manager.get(updated.groupIds().get(1));
        assertEquals(false, replacement.enabled());
        assertEquals(2, replacement.catalogProvenance().routeVersion());
        assertEquals(111, manager.allGroups().size());
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(API_ROOT, second.summary(), manager.allGroups()).action());
        assertThrows(IllegalArgumentException.class, () -> CatalogRouteInstaller.prepare(
                new CatalogRouteDetails(summary(218, 109, "dungeon", 9), first.payload())));
    }

    @Test
    void multiZoneBundleInstallsAndUpdatesEachDestinationAtomically() {
        ActiveGroupManager manager = new ActiveGroupManager();
        AtomicInteger changes = new AtomicInteger();
        manager.addPersistentDataListener(changes::incrementAndGet);
        CatalogRouteDetails first = new CatalogRouteDetails(
                summary(3, 2, "multiple", 9, 1),
                WaypointCodec.encodeCatalog(List.of(route("hub", 1), route("garden", 2))));

        var installed = CatalogRouteInstaller.install(manager, API_ROOT, first);

        assertEquals(1, changes.get());
        assertEquals(2, installed.groupCount());
        assertEquals(List.of("hub", "garden"), manager.allGroupsList().stream()
                .map(WaypointGroup::zoneId).toList());
        WaypointGroup garden = manager.get(installed.groupIds().get(1));
        garden.setEnabled(false);
        RouteFolder folder = manager.createFolder("Garden routes", "garden", List.of(garden.id()));
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(API_ROOT, first.summary(), manager.allGroups()).action());

        CatalogRouteDetails second = new CatalogRouteDetails(
                summary(5, 2, "multiple", 9, 2),
                WaypointCodec.encodeCatalog(List.of(route("hub", 2), route("garden", 3))));
        changes.set(0);
        var updated = CatalogRouteInstaller.install(manager, API_ROOT, second);

        assertEquals(1, changes.get());
        assertEquals(2, manager.allGroups().size());
        assertEquals(List.of("hub", "garden"), manager.allGroupsList().stream()
                .map(WaypointGroup::zoneId).toList());
        WaypointGroup updatedGarden = manager.get(updated.groupIds().get(1));
        assertEquals(false, updatedGarden.enabled());
        assertEquals(folder, manager.folderForGroup(updatedGarden.id()));
        assertEquals(3, updatedGarden.size());
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(API_ROOT, second.summary(), manager.allGroups()).action());
    }

    @Test
    void installAndUpdateUseOneManagerChangeAndDurableProvenance() {
        ActiveGroupManager manager = new ActiveGroupManager();
        AtomicInteger changes = new AtomicInteger();
        manager.addPersistentDataListener(changes::incrementAndGet);

        WaypointGroup first = route("hub", 1);
        CatalogRouteDetails versionOne = new CatalogRouteDetails(
                summary(1, 1, "hub", 9, 1),
                WaypointCodec.encodeCatalog(List.of(first)));
        var installed = CatalogRouteInstaller.install(
                manager, "https://catalog.example/api/", versionOne);

        assertEquals(1, changes.get());
        assertEquals(1, installed.groupCount());
        WaypointGroup oldGroup = manager.get(installed.groupIds().getFirst());
        assertEquals(1, oldGroup.catalogProvenance().routeVersion());
        assertEquals(CatalogInstallState.Action.INSTALLED,
                CatalogInstallState.inspect(
                        "https://catalog.example/api/", versionOne.summary(),
                        manager.allGroups()).action());
        oldGroup.setCurrentTargetIndex(oldGroup.size());
        oldGroup.setEnabled(false);
        oldGroup.setStylePresetId("personal-route");
        oldGroup.setStyleOverride(WaypointStyle.EMPTY.with("labelOpacity", 0.4));
        oldGroup.setWaypointStyle(0, WaypointStyle.EMPTY.with("labelOpacity", 0.7));
        RouteFolder folder = manager.createFolder(
                "Catalog routes", "hub", List.of(oldGroup.id()));
        WaypointGroup localNeighbor = route("hub", 1);
        manager.add(localNeighbor);

        WaypointGroup replacement = route("hub", 2);
        CatalogRouteDetails versionTwo = new CatalogRouteDetails(
                summary(2, 1, "hub", 9, 2),
                WaypointCodec.encodeCatalog(List.of(replacement)));
        changes.set(0);
        var updated = CatalogRouteInstaller.install(
                manager, "https://catalog.example/api/", versionTwo);

        assertEquals(1, changes.get());
        assertEquals(2, manager.allGroups().size());
        assertEquals(null, manager.get(oldGroup.id()));
        WaypointGroup updatedGroup = manager.get(updated.groupIds().getFirst());
        assertEquals(2, updatedGroup.catalogProvenance().routeVersion());
        assertEquals(false, updatedGroup.enabled());
        assertEquals(oldGroup.stylePresetId(), updatedGroup.stylePresetId());
        assertEquals(oldGroup.styleOverride(), updatedGroup.styleOverride());
        assertEquals(oldGroup.get(0).styleOverride(), updatedGroup.get(0).styleOverride());
        assertEquals(WaypointStyle.EMPTY, updatedGroup.get(1).styleOverride());
        assertEquals(0, updatedGroup.currentIndex(),
                "a changed catalog route must restart progress from its new first step");
        assertEquals(folder, manager.folderForGroup(updatedGroup.id()));
        assertEquals(List.of(updatedGroup, localNeighbor), manager.allGroupsList(),
                "catalog updates must keep the user's route order");
    }

    @Test
    void pointStylesFollowUniqueUnchangedWaypointsInsteadOfIndexes() {
        for (boolean duplicateNewPoint : List.of(false, true)) {
            ActiveGroupManager manager = new ActiveGroupManager();
            WaypointGroup original = route("hub", 4);
            original.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            original.set(2, original.get(1));
            CatalogRouteInstaller.install(manager, API_ROOT, new CatalogRouteDetails(
                    summary(4, 1), WaypointCodec.encodeCatalog(List.of(original))));
            WaypointGroup previous = manager.allGroupsList().getFirst();
            for (int index = 0; index < previous.size(); index++) {
                previous.setWaypointStyle(index,
                        WaypointStyle.EMPTY.with("labelOpacity", (index + 1) * 0.2));
            }
            WaypointGroup next = route("hub", 0);
            next.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            next.add(original.get(3));
            next.add(original.get(0));
            next.add(original.get(2).withName("Different step"));
            next.add(original.get(1));
            if (duplicateNewPoint) next.add(original.get(0));
            CatalogRouteInstaller.install(manager, API_ROOT, new CatalogRouteDetails(
                    summary(next.size(), 1, "hub", 9, 2),
                    WaypointCodec.encodeCatalog(List.of(next))));
            WaypointGroup updated = manager.allGroupsList().getFirst();
            assertEquals(previous.get(3).styleOverride(), updated.get(0).styleOverride());
            assertEquals(duplicateNewPoint ? WaypointStyle.EMPTY : previous.get(0).styleOverride(),
                    updated.get(1).styleOverride());
            assertEquals(WaypointStyle.EMPTY, updated.get(2).styleOverride());
            assertEquals(WaypointStyle.EMPTY, updated.get(3).styleOverride());
            if (duplicateNewPoint) assertEquals(WaypointStyle.EMPTY, updated.get(4).styleOverride());
        }
    }

    @Test
    void deletingOneCatalogGroupMakesTheRouteRepairable() {
        ActiveGroupManager manager = new ActiveGroupManager();
        List<WaypointGroup> groups = List.of(route("hub", 1), route("hub", 1));
        CatalogRouteDetails details = new CatalogRouteDetails(
                summary(2, 2), WaypointCodec.encodeCatalog(groups));
        var installed = CatalogRouteInstaller.install(
                manager, "https://catalog.example/api/", details);

        WaypointGroup retainedOrdinal = manager.get(installed.groupIds().get(1));
        retainedOrdinal.setEnabled(false);
        retainedOrdinal.setStylePresetId("repair-style");
        retainedOrdinal.setStyleOverride(WaypointStyle.EMPTY.with("labelOpacity", 0.3));
        retainedOrdinal.setWaypointStyle(0, WaypointStyle.EMPTY.with("labelOpacity", 0.8));
        RouteFolder folder = manager.createFolder(
                "Repair", "hub", List.of(retainedOrdinal.id()));
        manager.remove(installed.groupIds().getFirst());

        CatalogInstallState state = CatalogInstallState.inspect(
                "https://catalog.example/api/", details.summary(), manager.allGroups());
        assertEquals(CatalogInstallState.Action.REPAIR, state.action());
        assertTrue(state.canInstall());

        CatalogRouteInstaller.install(manager, API_ROOT, details);
        WaypointGroup repairedOrdinal = manager.allGroups().stream()
                .filter(group -> group.catalogProvenance().groupIndex() == 1)
                .findFirst().orElseThrow();
        assertEquals(false, repairedOrdinal.enabled());
        assertEquals(retainedOrdinal.stylePresetId(), repairedOrdinal.stylePresetId());
        assertEquals(retainedOrdinal.styleOverride(), repairedOrdinal.styleOverride());
        assertEquals(retainedOrdinal.get(0).styleOverride(), repairedOrdinal.get(0).styleOverride());
        assertEquals(folder, manager.folderForGroup(repairedOrdinal.id()));
    }

    @Test
    void extraStaleGroupsAndMixedPayloadHashesNeedRepair() {
        CatalogRouteSummary current = summary(2, 2, "hub", 9, 2);
        ActiveGroupManager staleManager = new ActiveGroupManager();
        staleManager.addAll(List.of(
                tagged(current, 0, 2, HASH_A),
                tagged(current, 1, 2, HASH_A),
                tagged(current, 0, 1, HASH_B)));

        assertEquals(CatalogInstallState.Action.REPAIR,
                CatalogInstallState.inspect(
                        API_ROOT, current, staleManager.allGroups()).action());

        ActiveGroupManager mixedHashManager = new ActiveGroupManager();
        mixedHashManager.addAll(List.of(
                tagged(current, 0, 2, HASH_A),
                tagged(current, 1, 2, HASH_B)));
        assertEquals(CatalogInstallState.Action.REPAIR,
                CatalogInstallState.inspect(
                        API_ROOT, current, mixedHashManager.allGroups()).action());
    }

    @Test
    void staleFirstDuplicateCannotStealCurrentLocalPreferencesDuringRepair() {
        CatalogRouteSummary current = summary(1, 1, "hub", 9, 2);
        CatalogRouteDetails details = new CatalogRouteDetails(
                current, WaypointCodec.encodeCatalog(List.of(route("hub", 1))));
        ActiveGroupManager manager = new ActiveGroupManager();
        CatalogRouteInstaller.install(manager, API_ROOT, details);
        WaypointGroup valid = manager.allGroups().iterator().next();
        valid.setEnabled(false);
        RouteFolder folder = new RouteFolder("folder", "Catalog", "hub", false);
        WaypointGroup stale = tagged(current, 0, 1, HASH_B);
        WaypointGroup between = route("hub", 1);
        manager.replaceAll(
                List.of(stale, between, valid), List.of(folder),
                Map.of(valid.id(), folder.id()));

        assertEquals(CatalogInstallState.Action.REPAIR,
                CatalogInstallState.inspect(API_ROOT, current, manager.allGroups()).action());
        CatalogRouteInstaller.install(manager, API_ROOT, details);

        List<WaypointGroup> repaired = manager.allGroupsList();
        assertEquals(2, repaired.size());
        assertEquals(between, repaired.getFirst());
        WaypointGroup replacement = repaired.get(1);
        assertEquals(false, replacement.enabled());
        assertEquals(folder, manager.folderForGroup(replacement.id()));
    }

    @Test
    void installerRejectsReinstallAndDowngradeAtItsOwnBoundary() {
        ActiveGroupManager manager = new ActiveGroupManager();
        CatalogRouteDetails versionTwo = new CatalogRouteDetails(
                summary(1, 1, "hub", 9, 2),
                WaypointCodec.encodeCatalog(List.of(route("hub", 1))));
        CatalogRouteInstaller.install(manager, API_ROOT, versionTwo);
        WaypointGroup installed = manager.allGroups().iterator().next();

        assertThrows(IllegalStateException.class,
                () -> CatalogRouteInstaller.install(manager, API_ROOT, versionTwo));
        assertEquals(List.of(installed), List.copyOf(manager.allGroups()));

        CatalogRouteDetails versionOne = new CatalogRouteDetails(
                summary(1, 1, "hub", 9, 1),
                WaypointCodec.encodeCatalog(List.of(route("hub", 1))));
        assertThrows(IllegalStateException.class,
                () -> CatalogRouteInstaller.install(manager, API_ROOT, versionOne));
        assertEquals(2, manager.allGroups().iterator().next()
                .catalogProvenance().routeVersion());
    }

    @Test
    void detailsBindAllStableSelectedMetadataBeforeDecode() {
        CatalogRouteSummary requested = new CatalogRouteSummary(
                "Abcdefghijklmnopqrstuv", "Route", "Description", "Tester",
                "wp_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", true,
                "unlisted", "hub", "Hub", 1, 1, 9, 1,
                0, "", "", "/r/Abcdefghijklmnopqrstuv");

        for (CatalogRouteSummary changed : List.of(
                copy(requested, "Other", requested.description(), requested.authorName(),
                        requested.publisherId(), requested.visibility(), requested.sharePath()),
                copy(requested, requested.title(), "Other", requested.authorName(),
                        requested.publisherId(), requested.visibility(), requested.sharePath()),
                copy(requested, requested.title(), requested.description(), "Other",
                        requested.publisherId(), requested.visibility(), requested.sharePath()),
                copy(requested, requested.title(), requested.description(),
                        requested.authorName(),
                        "wp_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        requested.visibility(), requested.sharePath()),
                copy(requested, requested.title(), requested.description(),
                        requested.authorName(), requested.publisherId(), "public",
                        requested.sharePath()),
                copy(requested, requested.title(), requested.description(),
                        requested.authorName(), requested.publisherId(),
                        requested.visibility(), "/r/Zbcdefghijklmnopqrstuv"),
                copyStructure(requested, "Zbcdefghijklmnopqrstuv", requested.zoneId(),
                        requested.waypointCount(), requested.groupCount(),
                        requested.codecVersion(), requested.version()),
                copyStructure(requested, requested.id(), "the_park",
                        requested.waypointCount(), requested.groupCount(),
                        requested.codecVersion(), requested.version()),
                copyStructure(requested, requested.id(), requested.zoneId(),
                        2, requested.groupCount(),
                        requested.codecVersion(), requested.version()),
                copyStructure(requested, requested.id(), requested.zoneId(),
                        requested.waypointCount(), 2,
                        requested.codecVersion(), requested.version()),
                copyStructure(requested, requested.id(), requested.zoneId(),
                        requested.waypointCount(), requested.groupCount(),
                        8, requested.version()),
                copyStructure(requested, requested.id(), requested.zoneId(),
                        requested.waypointCount(), requested.groupCount(),
                        requested.codecVersion(), 2))) {
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogProtocol.validateDetails(
                            requested, new CatalogRouteDetails(changed, "WP:test")));
        }
    }

    @Test
    void detailsAllowDisplayMetadataToRefresh() {
        CatalogRouteSummary requested = new CatalogRouteSummary(
                "Abcdefghijklmnopqrstuv", "Route", "Description", "Tester",
                "wp_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", false,
                "unlisted", "hub", "Hub", 1, 1, 9, 1,
                0, "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z",
                "/r/Abcdefghijklmnopqrstuv");
        CatalogRouteSummary refreshed = new CatalogRouteSummary(
                requested.id(), requested.title(), requested.description(),
                requested.authorName(), requested.publisherId(), true,
                requested.visibility(), requested.zoneId(), "Village", 1, 1, 9, 1,
                12, "2026-08-02T00:00:00Z", "2026-08-03T00:00:00Z",
                requested.sharePath());
        CatalogRouteDetails details = new CatalogRouteDetails(refreshed, "WP:test");

        assertEquals(details, CatalogProtocol.validateDetails(requested, details));
    }

    private static WaypointGroup route(String zoneId, int waypointCount) {
        WaypointGroup group = WaypointGroup.create("Route", zoneId);
        for (int index = 0; index < waypointCount; index++) {
            group.add(new Waypoint(index, 64, index, "Point " + index,
                    0xFFFFFF, 0, 0.0));
        }
        return group;
    }

    private static WaypointGroup tagged(
            CatalogRouteSummary route, int index, int version, String hash) {
        WaypointGroup group = WaypointGroup.create("Installed", route.zoneId());
        group.setCatalogProvenance(new CatalogRouteProvenance(
                API_ROOT, route.id(), version, route.codecVersion(),
                hash, index, route.groupCount()));
        return group;
    }

    private static CatalogRouteSummary copy(
            CatalogRouteSummary source, String title, String description,
            String authorName, String publisherId, String visibility, String sharePath) {
        return new CatalogRouteSummary(
                source.id(), title, description, authorName, publisherId,
                source.publisherVerified(), visibility, source.zoneId(), source.zoneLabel(),
                source.waypointCount(), source.groupCount(), source.codecVersion(),
                source.version(), source.downloads(), source.createdAt(),
                source.updatedAt(), sharePath);
    }

    private static CatalogRouteSummary copyStructure(
            CatalogRouteSummary source, String id, String zoneId,
            int waypointCount, int groupCount, int codecVersion, int version) {
        return new CatalogRouteSummary(
                id, source.title(), source.description(), source.authorName(),
                source.publisherId(), source.publisherVerified(), source.visibility(),
                zoneId, source.zoneLabel(), waypointCount, groupCount, codecVersion,
                version, source.downloads(), source.createdAt(), source.updatedAt(),
                source.sharePath());
    }

    private static CatalogRouteSummary summary(int waypointCount, int groupCount) {
        return summary(waypointCount, groupCount, "hub", 9);
    }

    private static CatalogRouteSummary summary(
            int waypointCount, int groupCount, String zoneId, int codecVersion) {
        return summary(waypointCount, groupCount, zoneId, codecVersion, 1);
    }

    private static CatalogRouteSummary summary(
            int waypointCount, int groupCount, String zoneId, int codecVersion, int version) {
        return new CatalogRouteSummary(
                "Abcdefghijklmnopqrstuv", "Route", "", "Tester", "", false,
                "unlisted", zoneId, "Hub", waypointCount, groupCount, codecVersion, version,
                0, "", "", "/routes/Abcdefghijklmnopqrstuv");
    }
}
