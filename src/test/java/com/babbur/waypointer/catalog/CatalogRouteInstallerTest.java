package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
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
    void enforcesCatalogSpecificGroupLimit() {
        List<WaypointGroup> groups = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> route("hub", 1))
                .toList();
        String payload = WaypointCodec.encodeCatalog(groups);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                CatalogRouteInstaller.prepare(new CatalogRouteDetails(
                        summary(65, 65), payload)));
        assertTrue(failure.getMessage().contains("install limits"));
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
        assertEquals(0, updatedGroup.currentIndex(),
                "a changed catalog route must restart progress from its new first step");
        assertEquals(folder, manager.folderForGroup(updatedGroup.id()));
        assertEquals(List.of(updatedGroup, localNeighbor), manager.allGroupsList(),
                "catalog updates must keep the user's route order");
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
