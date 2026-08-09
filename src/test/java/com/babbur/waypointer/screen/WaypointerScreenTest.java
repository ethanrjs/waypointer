package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomRouteProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerScreenTest {

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
                RouteListPresentation.routeRowTextX(rowLeft, false));
        assertTrue(RouteListPresentation.routeRowTextX(rowLeft, true)
                > RouteListPresentation.routeRowTextX(rowLeft, false));
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
}
