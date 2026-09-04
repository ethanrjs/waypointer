package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.RouteLibraryCodec;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CrystalHollowsProjection;
import com.babbur.waypointer.dungeon.DungeonRoomRouteProjection;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerScreenTest {

    @Test
    void clipboardImportRoutesUniversalAndLegacyDungeonSharesToTypedDungeonPath() {
        WaypointGroup route = WaypointGroup.create("Crypt Route", "crypt-a");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setSkipAheadEnabled(false);
        route.add(new Waypoint(1, 70, -2, "Chest", 0x123456,
                Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_SKIP_ON_INTERACT, 2.5)
                .withPreciseSixteenths(23, 1128, -25));

        for (String payload : List.of(
                "```text\n" + UniversalShareCodec.encodeDungeon(List.of(route)) + "\n```",
                DungeonRoomShareCodec.encode(List.of(route)))) {
            WaypointerScreen.ClipboardImportOutcome outcome =
                    WaypointerScreen.decodeClipboardImport(payload, "Localized Import");

            assertNull(outcome.error());
            assertNull(outcome.waypoints());
            assertNotNull(outcome.dungeonRoutes());
            assertEquals(1, outcome.dungeonRoutes().groups().size());
            WaypointGroup decoded = outcome.dungeonRoutes().groups().getFirst();
            assertEquals(WaypointGroup.RouteKind.DUNGEON, decoded.routeKind());
            assertEquals(route.zoneId(), decoded.zoneId());
            assertEquals(route.name(), decoded.name());
            assertEquals(route.waypoints(), decoded.waypoints());
        }
    }

    @Test
    void clipboardImportKeepsWaypointPathAndHandsConfigToTheReviewGate() {
        WaypointGroup route = WaypointGroup.create("Mining Route", "hub");
        route.add(Waypoint.at(1, 2, 3));
        WaypointerScreen.ClipboardImportOutcome waypoint =
                WaypointerScreen.decodeClipboardImport(
                        WaypointCodec.encode(List.of(route)), "Localized Import");

        assertNull(waypoint.error());
        assertNull(waypoint.dungeonRoutes());
        assertNotNull(waypoint.waypoints());
        assertEquals("Mining Route", waypoint.waypoints().groups().getFirst().name());

        WaypointerScreen.ClipboardImportOutcome unnamed =
                WaypointerScreen.decodeClipboardImport(
                        "[{\"x\":1,\"y\":2,\"z\":3}]",
                        "Localized Import");
        assertNull(unnamed.error());
        assertNotNull(unnamed.waypoints());
        assertEquals("Localized Import", unnamed.waypoints().groups().getFirst().name());

        WaypointerConfig sourceConfig = new WaypointerConfig();
        sourceConfig.setDefaultReachRadius(7.5);
        for (String configPayload : List.of(
                UniversalShareCodec.encodeConfig(sourceConfig),
                WaypointerConfigCodec.encode(sourceConfig))) {
            WaypointerScreen.ClipboardImportOutcome config =
                    WaypointerScreen.decodeClipboardImport(configPayload, "Localized Import");
            assertNull(config.waypoints());
            assertNull(config.dungeonRoutes());
            assertNull(config.error());
            // Decoding never mutates settings; the confirmation screen applies them.
            assertNotNull(config.config());
            assertEquals(7.5, config.config().defaultReachRadius());
        }
    }

    @Test
    void folderSpriteContainsTwoSixteenPixelFrames() throws IOException {
        try (var stream = WaypointerScreenTest.class.getResourceAsStream(
                "/assets/waypointer/textures/gui/folders.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            assertEquals(32, image.getWidth());
            assertEquals(16, image.getHeight());
        }
    }

    @Test
    void guiImportInstallsLibraryMetadataWithoutOverwritingHiddenColors() {
        ActiveGroupManager source = new ActiveGroupManager();
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.add(Waypoint.at(1, 2, 3).withColor(0x112233));
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.set(0, group.get(0).withColor(0xABCDEF));
        List<Integer> hiddenColors = group.manualColorSnapshot();
        group.setStaticColor(0x2468AC);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        source.add(group);
        source.addFolder(new RouteFolder(
                "source-folder", "Imported", "hub", true, 0x13579B),
                List.of(group.id()));
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(source, List.of(group));
        String payload = RouteLibraryCodec.encode(
                List.of(group.exportSnapshot()),
                WaypointCodec.Options.FULL_FIDELITY, metadata);
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(payload);
        WaypointerConfig config = new WaypointerConfig();
        config.setImportedRouteColorMode(WaypointGroup.GradientMode.STATIC);
        config.setImportedRouteDefaultColor(0x00FF00);
        ActiveGroupManager target = new ActiveGroupManager();

        WaypointerScreen.installImportedWaypointGroups(
                target, config, imported, "hub");

        WaypointGroup installed = target.allGroupsList().getFirst();
        assertEquals(0x2468AC, installed.get(0).color());
        assertEquals(hiddenColors, installed.manualColorSnapshot());
        RouteFolder folder = target.folderForGroup(installed.id());
        assertEquals("Imported", folder.name());
        assertTrue(folder.collapsed());
    }

    @Test
    void routeFolderModelKeepsManagerOrderAndSearchRevealsCollapsedMatches() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup amber = new WaypointGroup("amber", "Amber Route", "hub");
        WaypointGroup ruby = new WaypointGroup("ruby", "Ruby Route", "hub");
        WaypointGroup unfiled = new WaypointGroup("free", "Free Route", "hub");
        manager.addAll(List.of(amber, ruby, unfiled));
        RouteFolder folder = new RouteFolder("gems", "Gemstones", "hub", true);
        manager.addFolder(folder, List.of(amber.id(), ruby.id()));

        RouteFolderListModel.Snapshot normal = RouteFolderListModel.build(manager, "hub", "");
        assertEquals(List.of(amber, ruby), normal.folders().get(0).groups());
        assertEquals(List.of(unfiled), normal.unfiled());
        assertFalse(normal.folders().get(0).searchReveal());

        RouteFolderListModel.Snapshot search = RouteFolderListModel.build(
                manager, "hub", "ruby");
        assertEquals(List.of(ruby), search.folders().get(0).groups());
        assertTrue(search.folders().get(0).searchReveal());
        assertTrue(search.unfiled().isEmpty());
        assertEquals("waypointer.screen.main.folder.routes.one",
                RouteListPresentation.folderRouteCountKey(1));
        assertEquals("waypointer.screen.main.folder.routes.many",
                RouteListPresentation.folderRouteCountKey(0));
        assertEquals("waypointer.screen.main.folder.routes.many",
                RouteListPresentation.folderRouteCountKey(2));
        assertEquals("waypointer.screen.main.folder.search_suffix",
                RouteListPresentation.folderSearchSuffixKey());
    }

    @Test
    void focusedRouteFolderExpansionRevealsSiblingRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = new WaypointGroup("first", "First", "hub");
        WaypointGroup second = new WaypointGroup("second", "Second", "hub");
        manager.addAll(List.of(first, second));
        manager.addFolder(new RouteFolder("folder", "Imported", "hub", true),
                List.of(first.id(), second.id()));

        WaypointerRouteList.revealContainingFolder(manager, first.id());

        assertFalse(manager.folder("folder").collapsed());
        assertEquals(List.of(first, second),
                RouteFolderListModel.build(manager, "hub", "").folders().getFirst().groups());
    }

    @Test
    void routeFolderModelListsRuntimeRoutesOnlyInsideRuntimeFolders() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup runtime = new WaypointGroup(
                "crystal_hollows:structure:odawa", "Odawa", "crystal_hollows");
        runtime.setRuntimeOnly(true);
        manager.add(runtime);
        RouteFolder folder = new RouteFolder("crystal_hollows:structures", "Structures",
                "crystal_hollows", false, 0x55FFFF, true);
        manager.addFolder(folder, List.of(runtime.id()));

        RouteFolderListModel.Snapshot snapshot = RouteFolderListModel.build(
                manager, "crystal_hollows", "");
        assertEquals(List.of(runtime), snapshot.folders().getFirst().groups());
        assertTrue(snapshot.unfiled().isEmpty());

        manager.removeGroupFromFolder(runtime.id());
        snapshot = RouteFolderListModel.build(manager, "crystal_hollows", "");
        assertTrue(snapshot.folders().getFirst().groups().isEmpty());
        assertTrue(snapshot.unfiled().isEmpty());
        assertEquals("fairy_grotto:2", CrystalHollowsProjection.structureReferenceForGroup(
                "crystal_hollows:structure:fairy_grotto:2"));
        assertEquals("crystal_nucleus", CrystalHollowsProjection.structureReferenceForGroup(
                "crystal_hollows:structure:nucleus"));
    }

    @Test
    void reorderArrowPolicySupportsStoredDungeonRoutesButNotRuntimeRows() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = dungeonGroup("first", "room-a");
        WaypointGroup runtime = dungeonGroup("runtime", "room-a");
        runtime.setRuntimeOnly(true);
        WaypointGroup second = dungeonGroup("second", "room-a");
        manager.addAll(List.of(first, runtime, second));

        assertEquals(new WaypointerScreen.ReorderActionState(false, true),
                WaypointerScreen.reorderActionState(manager,
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "", List.of(first)));
        assertEquals(new WaypointerScreen.ReorderActionState(true, false),
                WaypointerScreen.reorderActionState(manager,
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "", List.of(second)));
        assertEquals(new WaypointerScreen.ReorderActionState(false, false),
                WaypointerScreen.reorderActionState(manager,
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "", List.of(runtime)));
        assertEquals(new WaypointerScreen.ReorderActionState(false, false),
                WaypointerScreen.reorderActionState(manager,
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "route", List.of(first)));
        assertEquals(new WaypointerScreen.ReorderActionState(false, false),
                WaypointerScreen.reorderActionState(manager,
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "", List.of(first, second)));
        assertEquals(new WaypointerScreen.ReorderActionState(false, false),
                WaypointerScreen.reorderActionState(manager, "room-a", "", List.of(first)));
    }

    @Test
    void roomHeaderSubtitleShowsInstalledSecretsSoImportsDoNotReadAsEmpty() {
        assertEquals("0 routes", RouteListPresentation.roomHeaderSubtitle(0, 0, false, false));
        assertEquals("0 routes  3 secrets",
                RouteListPresentation.roomHeaderSubtitle(0, 3, false, false));
        assertEquals("1 route  1 secret  current",
                RouteListPresentation.roomHeaderSubtitle(1, 1, true, false));
        assertEquals("2 routes  5 secrets  current  search",
                RouteListPresentation.roomHeaderSubtitle(2, 5, true, true));
    }

    @Test
    void installedSecretRowDisappearsAfterConversionToARegularRoute() {
        WaypointGroup editable = WaypointGroup.create("Secret Route", "room");
        editable.add(Waypoint.at(1, 70, 1));
        WaypointGroup temporary = WaypointGroup.create("Temporary", "room");
        temporary.setTemp(true);
        temporary.add(Waypoint.at(2, 70, 2));

        assertEquals(0, RouteListPresentation.displayedInstalledSecretCount(
                4, List.of(temporary, editable)));
        assertEquals(4, RouteListPresentation.displayedInstalledSecretCount(
                4, List.of(temporary)));
    }

    @Test
    void nextRouteNamePicksTheSmallestFreeNumber() {
        assertEquals("Route 1", RouteListPresentation.nextRouteName(List.of()));

        WaypointGroup first = WaypointGroup.create("Route 1", "hub");
        WaypointGroup third = WaypointGroup.create("Route 3", "hub");
        assertEquals("Route 2", RouteListPresentation.nextRouteName(List.of(first, third)));

        WaypointGroup custom = WaypointGroup.create("Foraging", "hub");
        assertEquals("Route 1", RouteListPresentation.nextRouteName(List.of(custom)));
    }

    @Test
    void mainContentRightUsesTheFullAvailableWidth() {
        assertEquals(500, WaypointerRouteList.contentRight(180, 500));
        assertEquals(2000, WaypointerRouteList.contentRight(180, 2000));
    }

    @Test
    void dropdownScrollClampsToVisibleZoneContent() {
        int rowHeight = GuiTokens.ROW_H;
        int viewportHeight = rowHeight * 3;

        assertEquals(0, WaypointerScreen.maxDropdownScroll(3, viewportHeight));
        assertEquals(rowHeight * 2, WaypointerScreen.maxDropdownScroll(5, viewportHeight));
        assertEquals(rowHeight * 2,
                WaypointerScreen.dropdownScrollAfterWheel(rowHeight * 2, -1, 5, viewportHeight));
        assertEquals(rowHeight,
                WaypointerScreen.dropdownScrollAfterWheel(rowHeight * 2, 1, 5, viewportHeight));
        assertEquals(0,
                WaypointerScreen.dropdownScrollAfterWheel(0, 1, 5, viewportHeight));
    }

    @Test
    void dropdownHitTestingAccountsForScrollOffset() {
        int rowsTop = 40;
        int rowsBottom = rowsTop + GuiTokens.ROW_H * 3;
        int scrollOffset = GuiTokens.ROW_H * 2;

        assertEquals(2, WaypointerScreen.dropdownRowIndexAt(rowsTop, rowsTop, rowsBottom,
                scrollOffset, 8));
        assertEquals(4, WaypointerScreen.dropdownRowIndexAt(rowsTop + GuiTokens.ROW_H * 2 + 1,
                rowsTop, rowsBottom, scrollOffset, 8));
        assertEquals(-1, WaypointerScreen.dropdownRowIndexAt(rowsBottom, rowsTop, rowsBottom,
                scrollOffset, 8));
        assertEquals(-1, WaypointerScreen.dropdownRowIndexAt(rowsTop - 1, rowsTop, rowsBottom,
                scrollOffset, 8));
    }

    @Test
    void routeToggleGeometryUsesSharedReadableChip() {
        int rowRight = 500;

        assertEquals(438, RouteListPresentation.routeToggleChipX(rowRight));
        assertEquals(436, RouteListPresentation.routeToggleHitLeft(rowRight));
        assertEquals("Shown", RouteListPresentation.routeToggleLabel(true));
        assertEquals("Hidden", RouteListPresentation.routeToggleLabel(false));
    }

    @Test
    void routeRowsUseZeroBasedCommandIndicesFromManagerOrder() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup filteredOut = WaypointGroup.create("Filtered", "garden");
        WaypointGroup visible = WaypointGroup.create("Visible", "hub");
        manager.add(filteredOut);
        manager.add(visible);

        var indices = RouteListPresentation.routeCommandIndices(manager.allGroupsList());

        assertEquals(0, indices.get(filteredOut.id()));
        assertEquals(1, indices.get(visible.id()));
        assertEquals("[1] Visible", RouteListPresentation.routeRowName(visible,
                indices.get(visible.id()), true));
        assertEquals("Visible", RouteListPresentation.routeRowName(visible, 1, false));
        assertEquals("Visible", RouteListPresentation.routeRowName(visible, -1, true));
    }

    @Test
    void dungeonRoomRouteRowsIndentUnderRoomHeader() {
        int rowLeft = 24;

        assertEquals(rowLeft + GuiTokens.GAP + 2,
                RouteListPresentation.routeRowTextX(rowLeft, false, false));
        assertTrue(RouteListPresentation.routeRowTextX(rowLeft, true, false)
                > RouteListPresentation.routeRowTextX(rowLeft, false, false));
        assertTrue(RouteListPresentation.routeRowTextX(rowLeft, true, true)
                > RouteListPresentation.routeRowTextX(rowLeft, false, false));
    }

    @Test
    void folderChildrenIndentTheirBandAndExposeMatchingSelectAndEditTargets() {
        int rowLeft = 24;
        int rowRight = 500;

        assertEquals(rowLeft, RouteListPresentation.routeRowBandLeft(rowLeft, false));
        assertTrue(RouteListPresentation.routeRowBandLeft(rowLeft, true) > rowLeft);
        assertEquals(438, RouteListPresentation.folderEditControlX(rowRight));
        assertEquals(376, RouteListPresentation.folderSelectControlX(rowRight));
        assertFalse(RouteListPresentation.isFolderEditControlHit(437, rowRight));
        assertTrue(RouteListPresentation.isFolderEditControlHit(438, rowRight));
        assertFalse(RouteListPresentation.isFolderEditControlHit(492, rowRight));
        assertTrue(RouteListPresentation.isFolderSelectControlHit(376, rowRight));
        assertEquals(RouteListPresentation.FolderHeaderAction.EDIT,
                RouteListPresentation.folderHeaderAction(460, rowRight, false));
        assertEquals(RouteListPresentation.FolderHeaderAction.SELECT,
                RouteListPresentation.folderHeaderAction(400, rowRight, false));
        assertEquals(RouteListPresentation.FolderHeaderAction.TOGGLE,
                RouteListPresentation.folderHeaderAction(300, rowRight, false));
        assertEquals(RouteListPresentation.FolderHeaderAction.NONE,
                RouteListPresentation.folderHeaderAction(300, rowRight, true));
    }

    @Test
    void routeDragRequiresASavedRouteAndAnUnfilteredUnmodifiedList() {
        WaypointGroup saved = WaypointGroup.create("Saved", "hub");
        WaypointGroup temporary = WaypointGroup.create("Temporary", "hub");
        temporary.setTemp(true);
        WaypointGroup runtime = WaypointGroup.create("Runtime", "hub");
        runtime.setRuntimeOnly(true);

        assertTrue(RouteListPresentation.canStartRouteDrag(
                saved, "", false, false));
        assertTrue(RouteListPresentation.canStartRouteDrag(
                saved, "   ", false, false));
        assertFalse(RouteListPresentation.canStartRouteDrag(
                saved, "saved", false, false));
        assertFalse(RouteListPresentation.canStartRouteDrag(
                saved, "", true, false));
        assertFalse(RouteListPresentation.canStartRouteDrag(
                saved, "", false, true));
        assertFalse(RouteListPresentation.canStartRouteDrag(
                temporary, "", false, false));
        assertFalse(RouteListPresentation.canStartRouteDrag(
                runtime, "", false, false));
    }

    @Test
    void hideRoutesOnlyChangesEnabledRoutes() {
        WaypointGroup shown = WaypointGroup.create("Shown", "hub");
        WaypointGroup alreadyHidden = WaypointGroup.create("Hidden", "hub");
        alreadyHidden.setEnabled(false);

        assertEquals(1, RouteListPresentation.hideRoutes(List.of(shown, alreadyHidden)));
        assertFalse(shown.enabled());
        assertFalse(alreadyHidden.enabled());
        assertEquals(0, RouteListPresentation.hideRoutes(List.of(shown, alreadyHidden)));
        assertEquals(0, RouteListPresentation.hideRoutes(null));
    }

    @Test
    void routeTogglePublishesPersistenceOnlyForSavedRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup saved = WaypointGroup.create("Saved", "hub");
        WaypointGroup runtime = WaypointGroup.create("Generated", "hub");
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(saved, runtime));
        AtomicInteger allChanges = new AtomicInteger();
        AtomicInteger persistentChanges = new AtomicInteger();
        manager.addDataListener(allChanges::incrementAndGet);
        manager.addPersistentDataListener(persistentChanges::incrementAndGet);

        WaypointerScreen.toggleRouteEnabled(manager, runtime);
        WaypointerScreen.toggleRouteEnabled(manager, saved);

        assertFalse(runtime.enabled());
        assertFalse(saved.enabled());
        assertEquals(2, allChanges.get());
        assertEquals(1, persistentChanges.get());
    }

    @Test
    void routeToggleOfDungeonMirrorPersistsItsSavedSource() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup saved = WaypointGroup.create("Stored room route", "room");
        saved.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup mirror = new WaypointGroup(
                "dungeon:auto:room", "Stored room route", "room");
        mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(saved.id());
        manager.addAll(List.of(saved, mirror));
        AtomicInteger allChanges = new AtomicInteger();
        AtomicInteger persistentChanges = new AtomicInteger();
        manager.addDataListener(allChanges::incrementAndGet);
        manager.addPersistentDataListener(persistentChanges::incrementAndGet);

        WaypointerScreen.toggleRouteEnabled(manager, mirror);

        assertFalse(mirror.enabled());
        assertFalse(saved.enabled());
        assertEquals(1, allChanges.get());
        assertEquals(1, persistentChanges.get());
    }

    @Test
    void routeDoubleClickOnlyOpensAlreadyPrimarySelectedRoute() {
        assertTrue(RouteListPresentation.shouldOpenEditor(
                true, true, false, false));
        assertFalse(RouteListPresentation.shouldOpenEditor(
                false, true, false, false));
        assertFalse(RouteListPresentation.shouldOpenEditor(
                true, false, false, false));
        assertFalse(RouteListPresentation.shouldOpenEditor(
                true, true, true, false));
        assertFalse(RouteListPresentation.shouldOpenEditor(
                true, true, false, true));
    }

    @Test
    void routeSearchMatchesGroupMetadataAndProgressSummary() {
        WaypointGroup group = WaypointGroup.create("Crystal Loop", "dwarven_mines");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(Waypoint.at(1, 64, 1));
        group.add(Waypoint.at(2, 64, 2));
        group.add(Waypoint.at(3, 64, 3));
        group.setCurrentIndex(1);

        assertTrue(routeMatches(group, "crystal"));
        assertTrue(routeMatches(group, "dwarven_mines"));
        assertTrue(routeMatches(group, "dwarven mines"));
        assertTrue(routeMatches(group, "sequence"));
        assertTrue(routeMatches(group, "1/3"));
        assertTrue(routeMatches(group, "33.3%"));
        assertFalse(routeMatches(group, "garden"));
    }

    @Test
    void routeSearchMatchesWaypointNamesDisplayLabelsAndCoordinates() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(12, 70, -4, "Gem Spot", Waypoint.DEFAULT_COLOR, 0, 0.0));
        group.add(new Waypoint(13, 71, -5, "Tiny", Waypoint.DEFAULT_COLOR, 0, 0.0));
        assertTrue(group.toggleSubwaypoint(1));

        assertTrue(routeMatches(group, "gem spot"));
        assertTrue(routeMatches(group, "#1.1"));
        assertTrue(routeMatches(group, "12,70,-4"));
        assertTrue(RouteListPresentation.waypointMatchesSearch(group, 1, "13,71,-5"));
        assertFalse(routeMatches(group, "missing"));
    }

    @Test
    void newRouteTargetKeepsNormalZonesAndTemporaryCurrentZone() {
        assertEquals("hub", WaypointerZoneCatalog.newRouteTargetZoneId("hub", "dungeon_hub"));
        assertEquals("crimson_isle", WaypointerZoneCatalog.newRouteTargetZoneId(
                WaypointerZoneCatalog.TEMPORARY_ZONE_ID, "crimson_isle"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerZoneCatalog.newRouteTargetZoneId(
                WaypointerZoneCatalog.TEMPORARY_ZONE_ID, null));
    }

    @Test
    void privateWorldRoutesCanMoveToKnownWorldZones() {
        WaypointGroup route = WaypointGroup.create("Offline route", Zone.PRIVATE_WORLD.id());

        assertTrue(WaypointerZoneCatalog.canMoveRouteZone(route));
        assertTrue(WaypointerZoneCatalog.retargetRoute(route, "hub"));
        assertEquals("hub", route.zoneId());
        assertFalse(WaypointerZoneCatalog.retargetRoute(route, "hub"));

        WaypointGroup temporary = WaypointGroup.create("Temporary", Zone.PRIVATE_WORLD.id());
        temporary.setTemp(true);
        assertFalse(WaypointerZoneCatalog.canMoveRouteZone(temporary));

        WaypointGroup generated = WaypointGroup.create("Generated", Zone.PRIVATE_WORLD.id());
        generated.setRuntimeOnly(true);
        assertFalse(WaypointerZoneCatalog.canMoveRouteZone(generated));

        WaypointGroup dungeonRoute = WaypointGroup.create("Room route", "admin");
        dungeonRoute.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        assertFalse(WaypointerZoneCatalog.canMoveRouteZone(dungeonRoute));
        assertFalse(WaypointerZoneCatalog.retargetRoute(route, "admin"));
        assertFalse(WaypointerZoneCatalog.retargetRoute(
                route, WaypointerZoneCatalog.TEMPORARY_ZONE_ID));
        assertFalse(WaypointerZoneCatalog.retargetRoute(route, Zone.UNKNOWN.id()));
        assertFalse(WaypointerZoneCatalog.retargetRoute(route, Zone.PRIVATE_WORLD.id()));
    }

    @Test
    void routeMoveTargetsExcludeCatacombsAndMasterModeZones() {
        WaypointGroup route = WaypointGroup.create("Route", Zone.PRIVATE_WORLD.id());
        List<String> dungeonZones = List.of(
                "dungeon",
                "dungeon_f1", "dungeon_f2", "dungeon_f3", "dungeon_f4",
                "dungeon_f5", "dungeon_f6", "dungeon_f7",
                "dungeon_m1", "dungeon_m2", "dungeon_m3", "dungeon_m4",
                "dungeon_m5", "dungeon_m6", "dungeon_m7");

        for (String zoneId : dungeonZones) {
            assertTrue(WaypointerZoneCatalog.isCatacombsOrMasterModeZone(zoneId));
            assertFalse(WaypointerZoneCatalog.canRetargetRoute(route, zoneId));
        }
        assertTrue(WaypointerZoneCatalog.isCatacombsOrMasterModeZone(" DUNGEON_M7 "));
        assertFalse(WaypointerZoneCatalog.isCatacombsOrMasterModeZone("dungeon_hub"));
        assertTrue(WaypointerZoneCatalog.canRetargetRoute(route, "dungeon_hub"));
    }

    @Test
    void newRouteTargetRequiresDetectedRoomFromDungeonRoomsBucket() {
        assertEquals("admin", WaypointerZoneCatalog.newRouteTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "admin"));
        assertNull(WaypointerZoneCatalog.newRouteTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "dungeon_hub"));
        assertEquals("Stand in a detected dungeon room to create a room route.",
                WaypointerZoneCatalog.newRouteBlockedNotice(
                        WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID));
    }

    @Test
    void newRouteTargetUsesSelectedDungeonRoomWhilePlayerIsElsewhere() {
        assertEquals("offline-room", WaypointerZoneCatalog.newRouteTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID,
                "offline-room",
                "dungeon_hub"));
    }

    @Test
    void offlineZoneListIncludesHypixelTargetsWithoutExistingRoutes() {
        List<String> zones = WaypointerZoneCatalog.zoneIdsForManager(new ActiveGroupManager());

        assertEquals(WaypointerZoneCatalog.TEMPORARY_ZONE_ID, zones.get(0));
        assertEquals(Zone.UNKNOWN.id(), zones.get(1));
        assertTrue(zones.contains("hub"));
        assertTrue(zones.contains("dungeon_f7"));
        assertTrue(zones.contains("torrhus_canyon"));
        assertTrue(zones.contains("safari"));
        assertEquals(1, zones.stream().filter("dwarven_mines"::equals).count());
        assertFalse(zones.contains("great_glacite_lake"));
        assertFalse(zones.contains("glacite_tunnels"));
        assertFalse(zones.contains("dwarven_base_camp"));
    }

    @Test
    void islandDropdownInitiallyShowsCurrentAndPopulatedIslandsOnly() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        manager.add(WaypointGroup.create("Garden route", "garden"));

        List<String> ids = WaypointerZoneCatalog.islandDropdownIdsForManager(manager);

        assertEquals(List.of("hub", "garden"), ids);

        List<String> expanded = WaypointerZoneCatalog.islandDropdownIdsForManager(manager, true);
        assertEquals("hub", expanded.get(0));
        assertEquals("garden", expanded.get(1));
        assertTrue(expanded.contains("safari"));
        assertTrue(expanded.contains("torrhus_canyon"));
        List<String> emptyLabels = expanded.subList(2, expanded.size()).stream()
                .map(id -> Zone.fromId(id).displayName())
                .toList();
        List<String> sortedEmptyLabels = emptyLabels.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        assertEquals(sortedEmptyLabels, emptyLabels);
    }

    @Test
    void openOverlaySuppressesHoverOnBackgroundControls() {
        assertTrue(WaypointerScreen.backgroundHoverAllowed(false, false));
        assertFalse(WaypointerScreen.backgroundHoverAllowed(true, false));
        assertFalse(WaypointerScreen.backgroundHoverAllowed(false, true));
    }

    @Test
    void privateWorldIsTheCurrentRouteBucketDuringOfflineAuthoring() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.PRIVATE_WORLD);
        manager.add(WaypointGroup.create("Offline route", Zone.PRIVATE_WORLD.id()));

        assertEquals(List.of(Zone.PRIVATE_WORLD.id()),
                WaypointerZoneCatalog.islandDropdownIdsForManager(manager));
        assertEquals(Zone.PRIVATE_WORLD.id(),
                WaypointerZoneCatalog.zoneIdsForManager(manager).get(1));
    }

    @Test
    void islandsTabDoesNotMixInTheDungeonBucket() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));

        List<String> ids = WaypointerZoneCatalog.islandDropdownIdsForManager(manager);

        assertEquals(List.of("hub"), ids);
        assertFalse(ids.contains(WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID));
    }

    @Test
    void dungeonRoomsOrderPopulatedAlphabeticallyBeforeEmptyAlphabetically() {
        assertEquals(List.of("beta-populated", "zed-populated", "alpha-empty", "gamma-empty"),
                WaypointerZoneCatalog.orderedDungeonRoomIds(
                        List.of("gamma-empty", "zed-populated", "alpha-empty", "beta-populated"),
                        Set.of("zed-populated", "beta-populated")));
    }

    @Test
    void currentDungeonRoomSortsFirstEvenWhenItHasNoSavedRoute() {
        assertEquals(List.of("alpha-current", "beta-populated", "zed-populated"),
                WaypointerZoneCatalog.orderedDungeonRoomIds(
                        List.of("zed-populated", "alpha-current", "beta-populated"),
                        Set.of("zed-populated", "beta-populated"),
                        "alpha-current"));
    }

    @Test
    void roomFocusScrollOnlyMovesWhenTheTargetIsOutsideTheViewport() {
        int pitch = GuiTokens.ROW_H + 4;
        int viewport = pitch * 3;

        assertEquals(pitch * 2, WaypointerRouteList.scrollOffsetToRevealRow(
                pitch * 2, 3, 10, viewport),
                "an already-visible target preserves user scroll");

        int revealed = WaypointerRouteList.scrollOffsetToRevealRow(0, 7, 10, viewport);
        int rowTop = 7 * pitch;
        int rowBottom = rowTop + GuiTokens.ROW_H + 2;
        assertTrue(rowTop >= revealed);
        assertTrue(rowBottom <= revealed + viewport);

        assertEquals(0, WaypointerRouteList.scrollOffsetToRevealRow(
                revealed, 0, 10, viewport),
                "a newly-current first room scrolls back to the top");
    }

    @Test
    void currentDungeonRoomHeaderUsesPersistentGreenHighlight() {
        assertEquals(RouteListPresentation.CURRENT_DUNGEON_ROOM_ACCENT,
                RouteListPresentation.roomHeaderAccent(true));
        assertTrue(RouteListPresentation.roomHeaderAccent(true)
                != RouteListPresentation.roomHeaderAccent(false));
        assertTrue(RouteListPresentation.roomHeaderBackground(false, false, true) != 0,
                "current room stays highlighted when a child route owns selection");
        assertEquals(0, RouteListPresentation.roomHeaderBackground(false, false, false));
    }

    @Test
    void compactFooterFitsOnOneLineAtFiveHundredTwelvePixels() {
        assertTrue(WaypointerScreen.footerRequiredWidth() <= 512);
    }

    @Test
    void offlineZoneListIncludesStoredDungeonRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Offline", "empty-offline-room");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        manager.add(route);

        List<String> zones = WaypointerZoneCatalog.zoneIdsForManager(manager);

        assertTrue(zones.contains(WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID));
    }

    @Test
    void emptyExportNoticeNamesSelectedScope() {
        assertEquals("Nothing to export in Hub.",
                WaypointerScreen.emptyExportNotice("hub"));
        assertEquals("Nothing to export in Temporary.",
                WaypointerScreen.emptyExportNotice(WaypointerZoneCatalog.TEMPORARY_ZONE_ID));
        assertEquals("Nothing to export in Dungeon Rooms.",
                WaypointerScreen.emptyExportNotice(WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID));
        assertEquals("Nothing to export in Dungeons: Admin.",
                WaypointerScreen.emptyExportNotice("admin"));
    }

    @Test
    void dungeonRouteExportWithoutSelectionUsesShownRoomRoutesOnly() {
        {
            WaypointGroup shown = WaypointGroup.create("Shown", "waterfall");
            shown.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            WaypointGroup hidden = WaypointGroup.create("Hidden", "creeper-beams");
            hidden.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            hidden.setEnabled(false);
            WaypointGroup normal = WaypointGroup.create("Normal", "hub");
            WaypointGroup temp = WaypointGroup.create("Temp", "waterfall");
            temp.setTemp(true);

            List<WaypointGroup> out = WaypointerScreen.dungeonRouteGroupsForExport(
                    List.of(), List.of(hidden, normal, shown, temp));

            assertEquals(List.of(shown), out);
        }
    }

    @Test
    void dungeonRouteExportUsesSelectedRouteWhenPresent() {
        {
            WaypointGroup selected = WaypointGroup.create("Selected", "doors");
            selected.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            selected.setEnabled(false);
            WaypointGroup shown = WaypointGroup.create("Shown", "doors");
            shown.setRouteKind(WaypointGroup.RouteKind.DUNGEON);

            List<WaypointGroup> out = WaypointerScreen.dungeonRouteGroupsForExport(
                    List.of(selected), List.of(shown));

            assertEquals(List.of(selected), out);
        }
    }

    @Test
    void deletingGeneratedDungeonRouteHasNoSecondaryDatastoreSideEffect() {
        WaypointGroup generated = new WaypointGroup(
                DungeonRoomRouteProjection.generatedGroupId("generated-delete"),
                "Dungeon Route", "generated-delete");
        generated.setRuntimeOnly(true);

        assertFalse(WaypointerScreen.clearGeneratedDungeonRouteBeforeDelete(generated));
    }

    @Test
    void importTargetKeepsNormalZonesAndResolvesTemporaryToCurrentZone() {
        assertEquals("hub", WaypointerZoneCatalog.importTargetZoneId("hub", "crimson_isle"));
        assertEquals("crimson_isle", WaypointerZoneCatalog.importTargetZoneId(
                WaypointerZoneCatalog.TEMPORARY_ZONE_ID, "crimson_isle"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerZoneCatalog.importTargetZoneId(
                WaypointerZoneCatalog.TEMPORARY_ZONE_ID, null));
        assertEquals(Zone.UNKNOWN.id(), WaypointerZoneCatalog.importTargetZoneId(null, "hub"));
    }

    @Test
    void importTargetUsesCurrentRoomOrUnknownFromDungeonRoomsBucket() {
        assertEquals("admin", WaypointerZoneCatalog.importTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "admin"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerZoneCatalog.importTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, "dungeon_hub"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerZoneCatalog.importTargetZoneId(
                WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID, null));
    }

    @Test
    void retargetUnknownImportedGroupsMovesOnlyUnknownZones() {
        WaypointGroup unknown = WaypointGroup.create("Unknown", Zone.UNKNOWN.id());
        WaypointGroup explicit = WaypointGroup.create("Explicit", "hub");

        WaypointerZoneCatalog.retargetUnknownImportedGroups(
                List.of(unknown, explicit), "crimson_isle");

        assertEquals("crimson_isle", unknown.zoneId());
        assertEquals("hub", explicit.zoneId());
    }

    @Test
    void retargetUnknownImportedGroupsKeepsUnknownFallbackWhenTargetIsUnknown() {
        WaypointGroup unknown = WaypointGroup.create("Unknown", Zone.UNKNOWN.id());

        WaypointerZoneCatalog.retargetUnknownImportedGroups(
                List.of(unknown), Zone.UNKNOWN.id());

        assertEquals(Zone.UNKNOWN.id(), unknown.zoneId());
    }

    @Test
    void retargetUnknownImportedGroupsResolvesMineshaftOnlyInsideExactLayout() {
        WaypointGroup inside = WaypointGroup.create("Inside", "mineshaft_unknown");
        WaypointGroup explicit = WaypointGroup.create("Explicit", "hub");

        WaypointerZoneCatalog.retargetUnknownImportedGroups(
                List.of(inside, explicit), "mineshaft_ruby_1");

        assertEquals("mineshaft_ruby_1", inside.zoneId());
        assertEquals("hub", explicit.zoneId());

        WaypointGroup outside = WaypointGroup.create("Outside", "mineshaft_unknown");
        WaypointerZoneCatalog.retargetUnknownImportedGroups(List.of(outside), "dwarven_mines");
        assertEquals("mineshaft_unknown", outside.zoneId());
    }

    @Test
    void importedGroupSelectorEntryCollapsesDungeonRoomsToVisibleParent() {
        assertEquals("hub", WaypointerZoneCatalog.selectorEntryForZoneId("hub"));
        assertEquals(WaypointerZoneCatalog.TEMPORARY_ZONE_ID,
                WaypointerZoneCatalog.selectorEntryForZoneId(
                        WaypointerZoneCatalog.TEMPORARY_ZONE_ID));
        assertEquals(Zone.UNKNOWN.id(),
                WaypointerZoneCatalog.selectorEntryForZoneId(Zone.UNKNOWN.id()));
        assertEquals(WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID,
                WaypointerZoneCatalog.selectorEntryForZoneId("admin"));
        assertEquals("dungeon_hub",
                WaypointerZoneCatalog.selectorEntryForZoneId("dungeon_hub"));
        assertNull(WaypointerZoneCatalog.selectorEntryForZoneId(null));
    }

    private static boolean routeMatches(WaypointGroup group, String query) {
        return RouteListPresentation.groupMatchesSearch(
                group, query, Zone.fromId(group.zoneId()).displayName());
    }

    private static WaypointGroup dungeonGroup(String id, String roomId) {
        WaypointGroup group = new WaypointGroup(id, id, roomId);
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        return group;
    }

    @Test
    void importFailuresKeepTheCodecReasonAndOnlyGenericOnesFallBack() {
        String unrecognized = net.minecraft.network.chat.Component.translatable(
                "waypointer.import.error.unrecognized").getString();
        assertEquals(unrecognized, WaypointerScreen.importFailureText(null));
        assertEquals(unrecognized, WaypointerScreen.importFailureText(
                new IllegalArgumentException("  ")));
        assertEquals(unrecognized, WaypointerScreen.importFailureText(
                new IllegalArgumentException(
                        "unrecognized waypoint payload (tried Waypointer, Skyblocker)")));
        assertEquals("group \"a\" has too many waypoints",
                WaypointerScreen.importFailureText(new IllegalArgumentException(
                        "group \"a\" has too many waypoints")));
    }

    @Test
    void undoRestoreAnchorsToTheNextSurvivingRouteInTheSameContainer() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup a = new WaypointGroup("a", "A", "hub");
        WaypointGroup b = new WaypointGroup("b", "B", "hub");
        WaypointGroup c = new WaypointGroup("c", "C", "hub");
        WaypointGroup d = new WaypointGroup("d", "D", "hub");
        manager.add(a);
        manager.add(b);
        manager.add(c);
        manager.add(d);
        manager.addFolder(new RouteFolder(
                "folder", "Folder", "hub", false, 0x123456), List.of(c.id()));

        Set<String> deleting = Set.of("a", "b");
        assertEquals("d", WaypointerScreen.nextSurvivorGroupId(manager, a, deleting, null),
                "skips other deleted routes and folder members in another container");
        assertEquals("d", WaypointerScreen.nextSurvivorGroupId(manager, b, deleting, null));
        assertEquals("c", WaypointerScreen.nextSurvivorGroupId(
                manager, b, Set.of("b"), "folder"));
        assertNull(WaypointerScreen.nextSurvivorGroupId(
                manager, d, Set.of("d"), null), "the last route has no later anchor");

        WaypointGroup dungeon = new WaypointGroup("dg", "DG", "room");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        manager.add(dungeon);
        assertNull(WaypointerScreen.nextSurvivorGroupId(
                manager, dungeon, Set.of("dg"), null),
                "dungeon routes restore without an ordering anchor");

        List<WaypointerScreen.RouteRestore> plan =
                WaypointerScreen.planUndoRestores(manager, List.of(c, dungeon));
        assertEquals(2, plan.size());
        assertEquals("folder", plan.get(0).folderId(),
                "folder membership is captured before the delete");
        assertNull(plan.get(0).beforeGroupId(), "c is the only folder member");
        assertNull(plan.get(1).folderId(), "dungeon routes carry no folder");
    }

    @Test
    void footerReservesRoomForEveryActionPlusTheDoneLane() {
        assertEquals(408, WaypointerScreen.footerRequiredWidth());
    }

    @Test
    void collapsedFolderRoutesLeaveTheSelectionSoDeleteOnlyHitsVisibleRows() {
        var visible = List.of("a", "c");
        var selected = new java.util.LinkedHashSet<>(List.of("a", "b", "c"));
        assertEquals(new java.util.LinkedHashSet<>(List.of("a", "c")),
                RouteSelectionPolicy.retainVisible(visible, selected),
                "ids hidden by a collapsed folder must drop out of the selection");
        assertTrue(RouteSelectionPolicy.retainVisible(List.of(), selected).isEmpty());
        assertTrue(RouteSelectionPolicy.retainVisible(null, selected).isEmpty());
        assertTrue(RouteSelectionPolicy.retainVisible(visible, null).isEmpty());
    }

    @Test
    void zoneCatalogSelectionFallsBackSafelyWithoutACurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        assertEquals(Zone.UNKNOWN.id(),
                WaypointerZoneCatalog.initialSelectedZoneId(manager));
        assertEquals(0, WaypointerZoneCatalog.dungeonRoomGroupCount(manager));
        assertNull(WaypointerZoneCatalog.currentDungeonRoomZoneId(null));
        assertNull(WaypointerZoneCatalog.currentDungeonRoomZoneId(manager));

        WaypointGroup room = WaypointGroup.create("Room route", "admin");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup temporary = WaypointGroup.create("Temp", "hub");
        temporary.setTemp(true);
        manager.addAll(List.of(room, temporary));
        assertEquals(1, WaypointerZoneCatalog.dungeonRoomGroupCount(manager));

        manager.onZoneChanged(Zone.fromId("hub"));
        assertEquals("hub", WaypointerZoneCatalog.initialSelectedZoneId(manager));
        assertNull(WaypointerZoneCatalog.currentDungeonRoomZoneId(manager));
    }
}
