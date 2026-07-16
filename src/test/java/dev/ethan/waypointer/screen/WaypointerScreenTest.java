package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.Direction;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomRouteSync;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerScreenTest {

    @Test
    void roomHeaderSubtitleShowsInstalledSecretsSoImportsDoNotReadAsEmpty() {
        assertEquals("0 routes", WaypointerScreen.roomHeaderSubtitle(0, 0, false, false));
        assertEquals("0 routes  3 secrets", WaypointerScreen.roomHeaderSubtitle(0, 3, false, false));
        assertEquals("1 route  1 secret  current",
                WaypointerScreen.roomHeaderSubtitle(1, 1, true, false));
        assertEquals("2 routes  5 secrets  current  search",
                WaypointerScreen.roomHeaderSubtitle(2, 5, true, true));
    }

    @Test
    void nextRouteNamePicksTheSmallestFreeNumber() {
        assertEquals("Route 1", WaypointerScreen.nextRouteName(List.of()));

        WaypointGroup first = WaypointGroup.create("Route 1", "hub");
        WaypointGroup third = WaypointGroup.create("Route 3", "hub");
        assertEquals("Route 2", WaypointerScreen.nextRouteName(List.of(first, third)));

        WaypointGroup custom = WaypointGroup.create("Foraging", "hub");
        assertEquals("Route 1", WaypointerScreen.nextRouteName(List.of(custom)));
    }

    @Test
    void mainContentRightCapsWideScreensButNotNarrowOnes() {
        assertEquals(500, WaypointerScreen.mainContentRight(180, 500));
        assertEquals(180 + 660, WaypointerScreen.mainContentRight(180, 2000));
    }

    @Test
    void sidebarScrollClampsToVisibleZoneContent() {
        int rowHeight = GuiTokens.ROW_H;
        int viewportHeight = rowHeight * 3;

        assertEquals(0, WaypointerScreen.maxSidebarScroll(3, viewportHeight));
        assertEquals(rowHeight * 2, WaypointerScreen.maxSidebarScroll(5, viewportHeight));
        assertEquals(rowHeight * 2,
                WaypointerScreen.sidebarScrollAfterWheel(rowHeight * 2, -1, 5, viewportHeight));
        assertEquals(rowHeight,
                WaypointerScreen.sidebarScrollAfterWheel(rowHeight * 2, 1, 5, viewportHeight));
        assertEquals(0,
                WaypointerScreen.sidebarScrollAfterWheel(0, 1, 5, viewportHeight));
    }

    @Test
    void sidebarHitTestingAccountsForScrollOffset() {
        int rowsTop = 40;
        int rowsBottom = rowsTop + GuiTokens.ROW_H * 3;
        int scrollOffset = GuiTokens.ROW_H * 2;

        assertEquals(2, WaypointerScreen.sidebarIndexAt(rowsTop, rowsTop, rowsBottom,
                scrollOffset, 8));
        assertEquals(4, WaypointerScreen.sidebarIndexAt(rowsTop + GuiTokens.ROW_H * 2 + 1,
                rowsTop, rowsBottom, scrollOffset, 8));
        assertEquals(-1, WaypointerScreen.sidebarIndexAt(rowsBottom, rowsTop, rowsBottom,
                scrollOffset, 8));
        assertEquals(-1, WaypointerScreen.sidebarIndexAt(rowsTop - 1, rowsTop, rowsBottom,
                scrollOffset, 8));
    }

    @Test
    void routeToggleGeometryUsesSharedReadableChip() {
        int rowRight = 500;

        assertEquals(438, WaypointerScreen.routeToggleChipX(rowRight));
        assertEquals(436, WaypointerScreen.routeToggleHitLeft(rowRight));
        assertEquals("Shown", WaypointerScreen.routeToggleLabel(true));
        assertEquals("Hidden", WaypointerScreen.routeToggleLabel(false));
    }

    @Test
    void dungeonRoomRouteRowsIndentUnderRoomHeader() {
        int rowLeft = 24;

        assertEquals(rowLeft + GuiTokens.GAP + 2,
                WaypointerScreen.routeRowTextX(rowLeft, false));
        assertTrue(WaypointerScreen.routeRowTextX(rowLeft, true)
                > WaypointerScreen.routeRowTextX(rowLeft, false));
    }

    @Test
    void hideRoutesOnlyChangesEnabledRoutes() {
        WaypointGroup shown = WaypointGroup.create("Shown", "hub");
        WaypointGroup alreadyHidden = WaypointGroup.create("Hidden", "hub");
        alreadyHidden.setEnabled(false);

        assertEquals(1, WaypointerScreen.hideRoutes(List.of(shown, alreadyHidden)));
        assertFalse(shown.enabled());
        assertFalse(alreadyHidden.enabled());
        assertEquals(0, WaypointerScreen.hideRoutes(List.of(shown, alreadyHidden)));
        assertEquals(0, WaypointerScreen.hideRoutes(null));
    }

    @Test
    void hideAllConfirmationRequiresSameShownRoutesInsideWindow() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        List<WaypointGroup> armedRoutes = List.of(first, second);
        long now = 1_000L;
        long armedUntil = 2_000L;

        assertTrue(WaypointerScreen.hideAllConfirmationMatches(
                armedRoutes, WaypointerScreen.routeIds(armedRoutes), now, armedUntil));
        assertFalse(WaypointerScreen.hideAllConfirmationMatches(
                List.of(first), WaypointerScreen.routeIds(armedRoutes), now, armedUntil));
        assertFalse(WaypointerScreen.hideAllConfirmationMatches(
                armedRoutes, WaypointerScreen.routeIds(armedRoutes), armedUntil, armedUntil));
    }

    @Test
    void routeDoubleClickOnlyOpensAlreadyPrimarySelectedRoute() {
        assertTrue(WaypointerScreen.shouldOpenGroupEditorFromRouteDoubleClick(
                true, true, false, false));
        assertFalse(WaypointerScreen.shouldOpenGroupEditorFromRouteDoubleClick(
                false, true, false, false));
        assertFalse(WaypointerScreen.shouldOpenGroupEditorFromRouteDoubleClick(
                true, false, false, false));
        assertFalse(WaypointerScreen.shouldOpenGroupEditorFromRouteDoubleClick(
                true, true, true, false));
        assertFalse(WaypointerScreen.shouldOpenGroupEditorFromRouteDoubleClick(
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

        assertTrue(WaypointerScreen.groupMatchesSearch(group, "crystal"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "dwarven_mines"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "dwarven mines"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "sequence"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "1/3"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "33.3%"));
        assertFalse(WaypointerScreen.groupMatchesSearch(group, "garden"));
    }

    @Test
    void routeSearchMatchesWaypointNamesDisplayLabelsAndCoordinates() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(12, 70, -4, "Gem Spot", Waypoint.DEFAULT_COLOR, 0, 0.0));
        group.add(new Waypoint(13, 71, -5, "Tiny", Waypoint.DEFAULT_COLOR, 0, 0.0));
        assertTrue(group.toggleSubwaypoint(1));

        assertTrue(WaypointerScreen.groupMatchesSearch(group, "gem spot"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "#1.1"));
        assertTrue(WaypointerScreen.groupMatchesSearch(group, "12,70,-4"));
        assertTrue(WaypointerScreen.waypointMatchesSearch(group, 1, "13,71,-5"));
        assertFalse(WaypointerScreen.groupMatchesSearch(group, "missing"));
    }

    @Test
    void newRouteTargetKeepsNormalZonesAndTemporaryCurrentZone() {
        assertEquals("hub", WaypointerScreen.newRouteTargetZoneId("hub", "dungeon_hub"));
        assertEquals("crimson_isle", WaypointerScreen.newRouteTargetZoneId(
                WaypointerScreen.TEMPORARY_ZONE_ID, "crimson_isle"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.newRouteTargetZoneId(
                WaypointerScreen.TEMPORARY_ZONE_ID, null));
    }

    @Test
    void newRouteTargetRequiresDetectedRoomFromDungeonRoomsBucket() {
        assertEquals("admin", WaypointerScreen.newRouteTargetZoneId(
                WaypointerScreen.DUNGEON_ROOMS_ZONE_ID, "admin"));
        assertNull(WaypointerScreen.newRouteTargetZoneId(
                WaypointerScreen.DUNGEON_ROOMS_ZONE_ID, "dungeon_hub"));
        assertEquals("Stand in a detected dungeon room to create a room route.",
                WaypointerScreen.newRouteBlockedNotice(WaypointerScreen.DUNGEON_ROOMS_ZONE_ID));
    }

    @Test
    void newRouteTargetUsesSelectedDungeonRoomWhilePlayerIsElsewhere() {
        DungeonRoomData.clearAllCustom();
        try {
            defineRoom("offline-room", "Offline Room");

            assertEquals("offline-room", WaypointerScreen.newRouteTargetZoneId(
                    WaypointerScreen.DUNGEON_ROOMS_ZONE_ID,
                    "offline-room",
                    "dungeon_hub"));
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void offlineZoneListIncludesHypixelTargetsWithoutExistingRoutes() {
        List<String> zones = WaypointerScreen.zoneIdsForManager(new ActiveGroupManager());

        assertEquals(WaypointerScreen.TEMPORARY_ZONE_ID, zones.get(0));
        assertEquals(Zone.UNKNOWN.id(), zones.get(1));
        assertTrue(zones.contains("hub"));
        assertTrue(zones.contains("dungeon_f7"));
        assertTrue(zones.contains("torrhus_canyon"));
        assertTrue(zones.contains("safari"));
    }

    @Test
    void collapsedZoneListKeepsCurrentAndPopulatedZonesOnly() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        manager.add(WaypointGroup.create("Garden route", "garden"));

        List<String> collapsed = WaypointerScreen.sidebarZoneIdsForManager(manager, false);

        assertTrue(collapsed.contains("hub"));
        assertTrue(collapsed.contains("garden"));
        assertFalse(collapsed.contains(Zone.UNKNOWN.id()));
        assertFalse(collapsed.contains("dungeon_f7"));
        assertTrue(WaypointerScreen.sidebarZoneIdsForManager(manager, true)
                .contains("dungeon_f7"));
    }

    @Test
    void expandedZoneListOrdersCurrentAndPopulatedBeforeEmptyInactiveZones() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        manager.add(WaypointGroup.create("Garden route", "garden"));

        List<String> expanded = WaypointerScreen.sidebarZoneIdsForManager(manager, true);
        List<String> collapsed = WaypointerScreen.sidebarZoneIdsForManager(manager, false);
        List<String> hiddenLabels = expanded.subList(collapsed.size(), expanded.size()).stream()
                .map(id -> Zone.fromId(id).displayName())
                .toList();
        List<String> sortedHiddenLabels = hiddenLabels.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        assertEquals("hub", expanded.get(0));
        assertTrue(expanded.indexOf("garden") < expanded.indexOf("dungeon_f7"));
        assertTrue(expanded.contains("safari"));
        assertTrue(expanded.contains("torrhus_canyon"));
        assertEquals(sortedHiddenLabels, hiddenLabels);
    }

    @Test
    void zoneDisclosureRowsBookendTheHiddenCatalog() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));

        List<String> collapsed = WaypointerScreen.sidebarRowsForManager(manager, false);
        List<String> expanded = WaypointerScreen.sidebarRowsForManager(manager, true);

        assertEquals("Show More...",
                WaypointerScreen.zoneDisclosureLabel(collapsed.get(collapsed.size() - 1)));
        assertEquals("Show Less",
                WaypointerScreen.zoneDisclosureLabel(expanded.get(expanded.size() - 1)));
        assertTrue(WaypointerScreen.isZoneDisclosureRow(collapsed.get(collapsed.size() - 1)));
        assertTrue(WaypointerScreen.isZoneDisclosureRow(expanded.get(expanded.size() - 1)));
    }

    @Test
    void compactFooterFitsOnOneLineAtFiveHundredTwelvePixels() {
        assertTrue(WaypointerScreen.footerRequiredWidth() <= 512);
    }

    @Test
    void offlineZoneListIncludesEmptyCustomDungeonRoomDefinitions() {
        DungeonRoomData.clearAllCustom();
        try {
            defineRoom("empty-offline-room", "Empty Offline Room");

            List<String> zones = WaypointerScreen.zoneIdsForManager(new ActiveGroupManager());

            assertTrue(zones.contains(WaypointerScreen.DUNGEON_ROOMS_ZONE_ID));
            assertEquals("empty-offline-room", WaypointerScreen.newRouteTargetZoneId(
                    WaypointerScreen.DUNGEON_ROOMS_ZONE_ID,
                    "empty-offline-room",
                    null));
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void emptyExportNoticeNamesSelectedScope() {
        assertEquals("Nothing to export in Hub.",
                WaypointerScreen.emptyExportNotice("hub"));
        assertEquals("Nothing to export in Temporary.",
                WaypointerScreen.emptyExportNotice(WaypointerScreen.TEMPORARY_ZONE_ID));
        assertEquals("Nothing to export in Dungeon Rooms.",
                WaypointerScreen.emptyExportNotice(WaypointerScreen.DUNGEON_ROOMS_ZONE_ID));
        assertEquals("Nothing to export in Dungeons: Admin.",
                WaypointerScreen.emptyExportNotice("admin"));
    }

    @Test
    void dungeonDefinitionsForExportSkipsEmptyRoomsAndSortsByDisplayName() {
        DungeonRoomDefinition zed = new DungeonRoomDefinition(
                "zed",
                "Zed",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(DungeonWaypoint.plain("z", DungeonSecretCategory.CHEST, 1, 70, 1, "")));
        DungeonRoomDefinition empty = new DungeonRoomDefinition(
                "empty",
                "Empty",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of());
        DungeonRoomDefinition alpha = new DungeonRoomDefinition(
                "alpha",
                "Alpha",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(DungeonWaypoint.plain("a", DungeonSecretCategory.LEVER, 2, 70, 2, "")));

        List<DungeonRoomDefinition> out =
                WaypointerScreen.dungeonDefinitionsForExport(List.of(zed, empty, alpha));

        assertEquals(List.of(alpha, zed), out);
        assertEquals(2, WaypointerScreen.dungeonWaypointCount(out));
    }

    @Test
    void dungeonRouteExportWithoutSelectionUsesShownRoomRoutesOnly() {
        DungeonRoomData.clearAllCustom();
        try {
            defineRoom("waterfall", "Waterfall");
            defineRoom("creeper-beams", "Creeper Beams");

            WaypointGroup shown = WaypointGroup.create("Shown", "waterfall");
            WaypointGroup hidden = WaypointGroup.create("Hidden", "creeper-beams");
            hidden.setEnabled(false);
            WaypointGroup normal = WaypointGroup.create("Normal", "hub");
            WaypointGroup temp = WaypointGroup.create("Temp", "waterfall");
            temp.setTemp(true);

            List<WaypointGroup> out = WaypointerScreen.dungeonRouteGroupsForExport(
                    List.of(), List.of(hidden, normal, shown, temp));

            assertEquals(List.of(shown), out);
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void dungeonRouteExportUsesSelectedRouteWhenPresent() {
        DungeonRoomData.clearAllCustom();
        try {
            defineRoom("doors", "Doors");

            WaypointGroup selected = WaypointGroup.create("Selected", "doors");
            selected.setEnabled(false);
            WaypointGroup shown = WaypointGroup.create("Shown", "doors");

            List<WaypointGroup> out = WaypointerScreen.dungeonRouteGroupsForExport(
                    List.of(selected), List.of(shown));

            assertEquals(List.of(selected), out);
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void generatedDungeonRouteDeleteClearsRoomWaypoints() {
        DungeonRoomData.clearAllCustom();
        try {
            DungeonRoom room = new DungeonRoom(
                    DungeonRoomType.ROOM,
                    DungeonRoomShape.ONE_BY_ONE,
                    Direction.NW,
                    0,
                    0,
                    List.of(DungeonRoom.packSegment(0, 0)));
            DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                    "generated-delete", "Generated Delete", room);
            DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                    "secret",
                    DungeonSecretCategory.CHEST,
                    4,
                    70,
                    7,
                    ""));
            WaypointGroup generated = new WaypointGroup(
                    DungeonRoomRouteSync.generatedGroupId(definition.id()),
                    "Dungeon Secrets -- Generated Delete",
                    definition.id());
            generated.setRuntimeOnly(true);

            assertTrue(WaypointerScreen.clearGeneratedDungeonRouteBeforeDelete(generated));

            assertTrue(DungeonRoomData.definition(definition.id()).waypoints().isEmpty());
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void importTargetKeepsNormalZonesAndResolvesTemporaryToCurrentZone() {
        assertEquals("hub", WaypointerScreen.importTargetZoneId("hub", "crimson_isle"));
        assertEquals("crimson_isle", WaypointerScreen.importTargetZoneId(
                WaypointerScreen.TEMPORARY_ZONE_ID, "crimson_isle"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.importTargetZoneId(
                WaypointerScreen.TEMPORARY_ZONE_ID, null));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.importTargetZoneId(null, "hub"));
    }

    @Test
    void importTargetUsesCurrentRoomOrUnknownFromDungeonRoomsBucket() {
        assertEquals("admin", WaypointerScreen.importTargetZoneId(
                WaypointerScreen.DUNGEON_ROOMS_ZONE_ID, "admin"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.importTargetZoneId(
                WaypointerScreen.DUNGEON_ROOMS_ZONE_ID, "dungeon_hub"));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.importTargetZoneId(
                WaypointerScreen.DUNGEON_ROOMS_ZONE_ID, null));
    }

    @Test
    void retargetUnknownImportedGroupsMovesOnlyUnknownZones() {
        WaypointGroup unknown = WaypointGroup.create("Unknown", Zone.UNKNOWN.id());
        WaypointGroup explicit = WaypointGroup.create("Explicit", "hub");

        WaypointerScreen.retargetUnknownImportedGroups(List.of(unknown, explicit), "crimson_isle");

        assertEquals("crimson_isle", unknown.zoneId());
        assertEquals("hub", explicit.zoneId());
    }

    @Test
    void retargetUnknownImportedGroupsKeepsUnknownFallbackWhenTargetIsUnknown() {
        WaypointGroup unknown = WaypointGroup.create("Unknown", Zone.UNKNOWN.id());

        WaypointerScreen.retargetUnknownImportedGroups(List.of(unknown), Zone.UNKNOWN.id());

        assertEquals(Zone.UNKNOWN.id(), unknown.zoneId());
    }

    @Test
    void importedGroupSidebarSelectionCollapsesDungeonRoomsToVisibleParent() {
        assertEquals("hub", WaypointerScreen.sidebarSelectionForZoneId("hub"));
        assertEquals(WaypointerScreen.TEMPORARY_ZONE_ID,
                WaypointerScreen.sidebarSelectionForZoneId(WaypointerScreen.TEMPORARY_ZONE_ID));
        assertEquals(Zone.UNKNOWN.id(), WaypointerScreen.sidebarSelectionForZoneId(Zone.UNKNOWN.id()));
        assertEquals(WaypointerScreen.DUNGEON_ROOMS_ZONE_ID,
                WaypointerScreen.sidebarSelectionForZoneId("admin"));
        assertEquals("dungeon_hub", WaypointerScreen.sidebarSelectionForZoneId("dungeon_hub"));
        assertNull(WaypointerScreen.sidebarSelectionForZoneId(null));
    }

    private static DungeonRoomDefinition defineRoom(String id, String name) {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of(DungeonRoom.packSegment(0, 0)));
        return DungeonRoomData.defineRoom(id, name, room);
    }
}
