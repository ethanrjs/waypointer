package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportScreenTest {

    @Test
    void dungeonExportScreenUsesUniversalKind4Payload() {
        WaypointGroup route = WaypointGroup.create("Crypt", "crypt-a");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.add(Waypoint.at(1, 70, 2));

        String payload = DungeonRoomExportScreen.encodePayload(List.of(route));

        assertTrue(payload.startsWith("WP:") && WaypointCodec.debugDecode(payload).version() == 10);
        assertTrue(UniversalShareCodec.decode(payload)
                instanceof UniversalShareCodec.DungeonRoutes);
    }

    @Test
    void guiExportCapturesFoldersOnlyForMultiRouteNativeExports() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(1, 2, 3));
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        second.add(Waypoint.at(4, 5, 6));
        manager.addAll(List.of(group, second));
        manager.addFolder(new RouteFolder(
                "folder", "Mining", "hub", true, 0x123456),
                List.of(group.id(), second.id()));

        RouteLibraryMetadata multi = ExportScreen.captureLibraryMetadata(
                manager, List.of(group, second), WaypointExportCodec.Target.WAYPOINTER);
        RouteLibraryMetadata single = ExportScreen.captureLibraryMetadata(
                manager, List.of(group), WaypointExportCodec.Target.WAYPOINTER);
        RouteLibraryMetadata thirdParty = ExportScreen.captureLibraryMetadata(
                manager, List.of(group, second), WaypointExportCodec.Target.SKYBLOCKER);

        assertEquals(List.of(0, 1), multi.folders().getFirst().memberOrdinals());
        assertTrue(single.folders().isEmpty(),
                "sharing one route must not export the sender's folder layout");
        assertTrue(thirdParty.isEmpty());
    }

    @Test
    void routePreviewLayoutSwitchesAtExactWideBoundary() {
        assertFalse(ExportScreen.isWidePreviewLayout(735));
        assertTrue(ExportScreen.isWidePreviewLayout(736));
        assertEquals(240, ExportScreen.widePreviewWidth(704, 440));
    }

    @Test
    void disablingThePreviewCollapsesTheSplitLayoutAtEveryWidth() {
        assertTrue(ExportScreen.isWidePreviewLayout(true, 736));
        assertFalse(ExportScreen.isWidePreviewLayout(false, 736));
        assertFalse(ExportScreen.isWidePreviewLayout(false, 2560));
    }

    @Test
    void shortExportLayoutsKeepOptionsRouteSelectionAndFooterReachable() {
        List<LayoutCase> cases = List.of(
                new LayoutCase(320, 240, false, false),
                new LayoutCase(320, 240, false, true),
                new LayoutCase(320, 240, true, false),
                new LayoutCase(320, 240, true, true),
                new LayoutCase(320, 480, false, false),
                new LayoutCase(320, 480, true, true),
                new LayoutCase(360, 270, false, false),
                new LayoutCase(360, 270, false, true),
                new LayoutCase(360, 270, true, false),
                new LayoutCase(360, 270, true, true),
                new LayoutCase(480, 240, false, false),
                new LayoutCase(480, 270, false, false),
                new LayoutCase(480, 270, false, true),
                new LayoutCase(480, 270, true, false),
                new LayoutCase(480, 270, true, true));

        for (LayoutCase testCase : cases) {
            int groupCount = testCase.multiRoute() ? 4 : 1;
            ExportScreen.LayoutPolicy layout = ExportScreen.layoutPolicy(
                    testCase.width(), testCase.height(), testCase.preview(),
                    testCase.multiRoute(), testCase.multiRoute(), groupCount, 10, 380);

            assertTrue(layout.panelX() >= 0);
            assertTrue(layout.panelY() >= 0);
            assertTrue(layout.panelX() + layout.panelWidth() <= testCase.width());
            assertTrue(layout.panelBottom() <= testCase.height(), testCase.toString());
            assertTrue(layout.footerY() >= layout.panelY());
            assertTrue(layout.footerY() + BTN_H <= layout.panelBottom(), testCase.toString());

            int includeRowsH = ExportScreen.includeGrid(layout.contentWidth(), 6)
                    .rowsPerColumn() * 24 - 4;
            int routeBlockH = testCase.multiRoute()
                    ? ExportScreen.routePickerBlockHeight(
                    true, groupCount, testCase.height(), includeRowsH, 10) : 0;
            if (layout.stackedFooter()) {
                assertEquals(BTN_H + GAP, layout.footerY() - layout.footerTop());
            }
            if (!layout.compactLayout()) continue;

            assertTrue(layout.optionsViewportBottom() > layout.optionsViewportTop(),
                    testCase.toString());
            assertTrue(layout.optionsMaxScrollOffset() >= 0, testCase.toString());

            assertTrue(layout.optionsContentBottom() >= layout.includeRowsY() + includeRowsH,
                    testCase.toString());
            if (testCase.multiRoute()) {
                assertTrue(layout.optionsContentBottom() >= layout.routeBlockY() + routeBlockH,
                        testCase.toString());
                assertTrue(layout.optionsMaxScrollOffset() >= layout.routeBlockY()
                        + routeBlockH - layout.optionsViewportBottom(),
                        testCase.toString());
            }
        }
    }

    @Test
    void footerReservesAnotherRowWhenTranslatedLabelsExceedAvailableWidth() {
        ExportScreen.LayoutPolicy narrow = ExportScreen.layoutPolicy(
                320, 480, false, false, false, 1, 10, 380);
        ExportScreen.LayoutPolicy normal = ExportScreen.layoutPolicy(
                736, 480, true, false, false, 1, 10, 380);
        ExportScreen.LayoutPolicy translated = ExportScreen.layoutPolicy(
                736, 480, true, false, false, 1, 10, 700);

        assertFalse(narrow.compactLayout());
        assertTrue(narrow.stackedFooter());
        assertFalse(normal.stackedFooter());
        assertTrue(translated.stackedFooter());
        assertEquals(BTN_H + GAP, translated.panelHeight() - normal.panelHeight());
        assertTrue(narrow.footerY() + BTN_H <= narrow.panelBottom());
        assertTrue(translated.footerY() + BTN_H <= translated.panelBottom());
    }

    @Test
    void compactPreviewPageDisablesHiddenRouteListInput() {
        assertTrue(ExportScreen.routeListInputEnabled(true, true, true));
        assertFalse(ExportScreen.routeListInputEnabled(false, true, true));
        assertFalse(ExportScreen.routeListInputEnabled(true, false, true));
        assertFalse(ExportScreen.routeListInputEnabled(true, true, false));
    }

    @Test
    void compactOptionsScrollPolicyClampsKeyboardPaging() {
        assertEquals(24, ExportScreen.optionsScrollPage(48));
        assertEquals(0, ExportScreen.optionsScrollTarget(24, 120, -48));
        assertEquals(120, ExportScreen.optionsScrollTarget(96, 120, 48));
        assertEquals(120, ExportScreen.optionsScrollTarget(0, 120, Integer.MAX_VALUE));
    }

    private record LayoutCase(int width, int height, boolean preview, boolean multiRoute) {}

    @Test
    void previewNavigationDoesNotChangeExportSelection() {
        ExportRouteSelection selected = ExportRouteSelection.of(true, false, true, true);

        assertEquals(2, selected.navigate(0, 1));
        assertEquals(3, selected.navigate(0, -1));
        assertArrayEquals(new boolean[]{true, false, true, true}, selected.snapshot());
    }

    @Test
    void previewNavigationLeavesEncodedOutputByteForByteUnchanged() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(1, 2, 3));
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        second.add(Waypoint.at(4, 5, 6));
        List<WaypointGroup> groups = List.of(first, second);
        ExportRouteSelection selected = ExportRouteSelection.of(true, true);
        WaypointCodec.Options options = WaypointCodec.Options.builder().build();
        String before = WaypointExportCodec.encode(
                selected.selectedGroups(groups), options,
                WaypointExportCodec.Target.WAYPOINTER);

        assertEquals(1, selected.navigate(0, 1));
        String after = WaypointExportCodec.encode(
                selected.selectedGroups(groups), options,
                WaypointExportCodec.Target.WAYPOINTER);

        assertEquals(before, after);
    }

    @Test
    void deselectedPreviewRouteChoosesNextThenPrevious() {
        assertEquals(3, ExportRouteSelection.of(true, false, false, true).replacementFor(2));
        assertEquals(0, ExportRouteSelection.of(true, false, false, false).replacementFor(2));
    }

    @Test
    void initialRouteSelectionStartsEveryRouteSelected() {
        assertArrayEquals(new boolean[0], new ExportRouteSelection(-1).snapshot());
        assertArrayEquals(new boolean[0], new ExportRouteSelection(0).snapshot());
        assertArrayEquals(new boolean[]{true, true, true},
                new ExportRouteSelection(3).snapshot());
    }

    @Test
    void routePickerStartsExpandedOnlyForSmallMultiRouteExports() {
        assertFalse(ExportScreen.shouldStartRoutePickerExpanded(0));
        assertFalse(ExportScreen.shouldStartRoutePickerExpanded(1));
        assertTrue(ExportScreen.shouldStartRoutePickerExpanded(2));
        assertTrue(ExportScreen.shouldStartRoutePickerExpanded(8));
        assertFalse(ExportScreen.shouldStartRoutePickerExpanded(9));
    }

    @Test
    void toggleRouteSelectionKeepsAtLeastOneRouteSelected() {
        ExportRouteSelection selected = ExportRouteSelection.of(true, true, true);

        assertTrue(selected.toggle(1));
        assertArrayEquals(new boolean[]{true, false, true}, selected.snapshot());
        assertEquals(2, selected.count());
        assertTrue(selected.hasExcludedRoutes());

        assertTrue(selected.toggle(0));
        assertArrayEquals(new boolean[]{false, false, true}, selected.snapshot());
        assertEquals(1, selected.count());

        assertFalse(selected.toggle(2));
        assertArrayEquals(new boolean[]{false, false, true}, selected.snapshot());
        assertEquals(1, selected.count());
    }

    @Test
    void selectAllRoutesRestoresEveryExcludedRoute() {
        ExportRouteSelection selected = ExportRouteSelection.of(false, true, false);

        selected.selectAll();

        assertArrayEquals(new boolean[]{true, true, true}, selected.snapshot());
        assertFalse(selected.hasExcludedRoutes());
        assertEquals(3, selected.count());
    }

    @Test
    void selectedGroupsForExportPreservesOriginalOrderAndSingleRouteExports() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        WaypointGroup third = WaypointGroup.create("Third", "hub");

        assertEquals(List.of(first), ExportRouteSelection.of(false).selectedGroups(List.of(first)));
        assertEquals(List.of(first, third), ExportRouteSelection.of(true, false, true)
                .selectedGroups(List.of(first, second, third)));
        assertEquals(List.of(), ExportRouteSelection.of(false, false, false)
                .selectedGroups(List.of(first, second, third)));
    }

    @Test
    void codeBlockPayloadWrapsTheExactEncodedText() {
        assertEquals("```\nWP:abc\n```", ExportPolicy.codeBlockPayload("WP:abc"));
        assertEquals("```\n\n```", ExportPolicy.codeBlockPayload(null));
    }

    @Test
    void exportFitSummarySeparatesCommandFitFromChatFit() {
        ExportPolicy.FitSummary commandFit = ExportPolicy.fitSummary("a".repeat(253));
        assertEquals(253, commandFit.characters());
        assertEquals(253, commandFit.wireBytes());
        assertEquals(256, commandFit.commandBytes());
        assertTrue(commandFit.chatOk());
        assertTrue(commandFit.commandOk());
        assertEquals("waypointer.export.fit.chat_and_commands", commandFit.messageKey());

        ExportPolicy.FitSummary chatOnly = ExportPolicy.fitSummary("a".repeat(254));
        assertTrue(chatOnly.chatOk());
        assertFalse(chatOnly.commandOk());
        assertEquals("waypointer.export.fit.chat_only", chatOnly.messageKey());

        ExportPolicy.FitSummary tooLong = ExportPolicy.fitSummary("a".repeat(257));
        assertFalse(tooLong.chatOk());
        assertFalse(tooLong.commandOk());
        assertEquals("waypointer.export.fit.too_long", tooLong.messageKey());
    }

    @Test
    void includeGridCollapsesToOneColumnOnlyWhenLabelsWouldNotFit() {
        // The panel at full width: two columns of three, each capped so the
        // column width is what sets the panel width rather than the reverse.
        ExportScreen.IncludeGrid full = ExportScreen.includeGrid(416, 6);
        assertEquals(200, full.columnWidth());
        assertEquals(3, full.rowsPerColumn());

        // 308 of content is the exact width where two columns still clear the
        // label minimum, so it is where the collapse rule turns on.
        ExportScreen.IncludeGrid snug = ExportScreen.includeGrid(308, 6);
        assertEquals(150, snug.columnWidth());
        assertEquals(3, snug.rowsPerColumn());

        // Narrower than that and the list stacks full width instead of clipping.
        ExportScreen.IncludeGrid narrow = ExportScreen.includeGrid(306, 6);
        assertEquals(306, narrow.columnWidth());
        assertEquals(6, narrow.rowsPerColumn());
    }

    @Test
    void theSingleRoutePanelFitsEvenAtTheSmallestUsefulGuiSize() {
        // Two columns of three include rows, no route picker.
        int includeRowsH = 3 * 24 - 4;
        int fixed = ExportScreen.panelFixedHeight(includeRowsH, 0);
        int lineH = 10; // Minecraft's 9px font line box plus a pixel of leading.

        // Roomy window: the preview gets its full three lines.
        assertEquals(3 * lineH + 12, ExportScreen.previewHeight(540, fixed, lineH));
        assertTrue(fixed + ExportScreen.previewHeight(540, fixed, lineH) <= 540);

        // GUI scale 4 on a 1080p monitor leaves 270 units of height. The panel
        // has to fit there too, with the copy buttons still on screen.
        int tight = ExportScreen.previewHeight(270, fixed, lineH);
        assertTrue(fixed + tight <= 270,
                "panel is " + (fixed + tight) + " tall in a 270-unit window");
        // It gives ground rather than vanishing: still at least one visible line.
        assertTrue(tight >= lineH + 12);
    }

    @Test
    void toggleMarkerDistinguishesUnavailableFromOff() {
        assertEquals("[x]", ExportScreen.toggleMarker(true, true));
        assertEquals("[ ]", ExportScreen.toggleMarker(true, false));
        assertEquals("[-]", ExportScreen.toggleMarker(false, true));
        assertEquals("[-]", ExportScreen.toggleMarker(false, false));
    }

    @Test
    void builderDefaultsFollowTheConfiguredIslandPreference() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(0, 64, 0));

        assertFalse(ExportPolicy.optionsFromConfig(config, List.of(group)).build().includeZone);

        config.setExportIncludeZone(true);
        assertTrue(ExportPolicy.optionsFromConfig(config, List.of(group)).build().includeZone);
    }

    @Test
    void freshConfigAllOffPolicySelectsKind2AndKind6AndTracksToggleHistory() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup first = WaypointGroup.create("Discarded first", "hub");
        first.add(Waypoint.at(1, 64, 2));
        first.add(Waypoint.at(2, 64, 3));
        WaypointGroup second = WaypointGroup.create("Discarded second", "hub");
        second.add(Waypoint.at(4, 65, 6));
        List<WaypointGroup> routes = List.of(first, second);
        WaypointCodec.Options.Builder builder = ExportPolicy.optionsFromConfig(config, routes);

        WaypointCodec.Options allOff = builder.build();
        assertTrue(allOff.isBareCoordinateProjection());
        String single = WaypointExportCodec.encode(
                List.of(first), allOff, WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty());
        String multi = WaypointExportCodec.encode(
                routes, allOff, WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty());

        assertTrue(List.of("V10_RICE", "V10_QUOTIENT", "V10_DELTA_DEFLATE")
                .contains(WaypointCodec.debugDecode(single).groups().getFirst().coordMode()));
        assertTrue(WaypointCodec.debugDecode(multi).groups().stream().allMatch(group ->
                group.coordMode().startsWith("V10_BARE_PACK_")));

        builder.includeNames(true);
        assertFalse(builder.build().isBareCoordinateProjection());
        builder.includeNames(false);
        assertTrue(builder.build().isBareCoordinateProjection());
        builder.label("named export");
        assertFalse(builder.build().isBareCoordinateProjection());
        builder.label("");
        assertTrue(builder.build().isBareCoordinateProjection());
    }

    @Test
    void freshConfigDungeonSelectionDoesNotRequestABareRoute() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup dungeon = WaypointGroup.create("Crypt", "crypt-a");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        dungeon.add(Waypoint.at(1, 70, 2));

        WaypointCodec.Options options = ExportPolicy.optionsFromConfig(
                config, List.of(dungeon)).build();
        String payload = WaypointExportCodec.encode(
                List.of(dungeon), options, WaypointExportCodec.Target.WAYPOINTER,
                RouteLibraryMetadata.empty());

        assertFalse(options.isBareCoordinateProjection());
        assertTrue(WaypointCodec.debugDecode(payload).groups().stream().noneMatch(group ->
                group.coordMode().startsWith("V10_BARE")));
        assertTrue(UniversalShareCodec.decode(payload)
                instanceof UniversalShareCodec.Waypoints);

        WaypointGroup regular = WaypointGroup.create("Regular", "hub");
        regular.add(Waypoint.at(4, 65, 6));
        assertFalse(ExportPolicy.optionsFromConfig(
                config, List.of(regular, dungeon)).build().isBareCoordinateProjection());
        assertFalse(ExportPolicy.optionsFromConfig(
                config, List.of()).build().isBareCoordinateProjection());
    }

    @Test
    void labelTooltipExplainsTargetSupport() {
        assertEquals("Optional title shown by Waypointer imports",
                ExportPolicy.labelTooltipText(WaypointExportCodec.Target.WAYPOINTER));
        assertEquals("SkyHanni exports do not support Waypointer labels",
                ExportPolicy.labelTooltipText(WaypointExportCodec.Target.SKYHANNI));
    }

    @Test
    void waypointerLabelOverridesTheLivePreviewRouteName() {
        assertEquals("Shared Route", ExportPolicy.previewRouteName(
                "Original Route", " Shared Route ", WaypointExportCodec.Target.WAYPOINTER, 1));
        assertEquals("Original Route", ExportPolicy.previewRouteName(
                "Original Route", "   ", WaypointExportCodec.Target.WAYPOINTER, 1));
        assertEquals("Original Route", ExportPolicy.previewRouteName(
                "Original Route", "Shared Route", WaypointExportCodec.Target.SKYHANNI, 1));
        assertEquals("Original Route", ExportPolicy.previewRouteName(
                "Original Route", "Bundle Name", WaypointExportCodec.Target.WAYPOINTER, 2));
    }

    @Test
    void previewOverflowTextUsesCompactCopy() {
        assertEquals("...1 more line", ExportPolicy.previewOverflowText(1));
        assertEquals("...3 more lines", ExportPolicy.previewOverflowText(3));
        assertEquals("...0 more lines", ExportPolicy.previewOverflowText(-1));
    }

    @Test
    void builderDefaultsEnableWaypointFlagsForSubwaypointExports() {
        WaypointerConfig config = new WaypointerConfig();
        config.setExportIncludeWaypointFlags(false);
        WaypointGroup normal = WaypointGroup.create("Normal", "hub");
        normal.add(Waypoint.at(0, 64, 0));

        assertFalse(ExportPolicy.optionsFromConfig(config, List.of(normal))
                .build().includeWaypointFlags);

        WaypointGroup withSubwaypoint = WaypointGroup.create("Subway", "hub");
        withSubwaypoint.add(Waypoint.at(0, 64, 0));
        withSubwaypoint.add(Waypoint.at(1, 64, 1));
        assertTrue(withSubwaypoint.toggleSubwaypoint(1));

        assertTrue(ExportPolicy.optionsFromConfig(config, List.of(withSubwaypoint))
                .build().includeWaypointFlags);
        assertFalse(ExportPolicy.optionsFromConfig(config, List.of(withSubwaypoint))
                .build().isBareCoordinateProjection());
        assertFalse(config.exportIncludeWaypointFlags());
    }

    @Test
    void subwaypointCompatibilityWarningOnlyAppliesToSelectedThirdPartyRoutes() {
        WaypointGroup normal = WaypointGroup.create("Normal", "hub");
        normal.add(Waypoint.at(0, 64, 0));

        WaypointGroup withSubwaypoint = WaypointGroup.create("Subway", "hub");
        withSubwaypoint.add(Waypoint.at(0, 64, 0));
        withSubwaypoint.add(Waypoint.at(1, 64, 1));
        assertTrue(withSubwaypoint.toggleSubwaypoint(1));

        assertFalse(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.WAYPOINTER, List.of(withSubwaypoint)));
        assertFalse(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.CHUNKLOGGER, List.of(withSubwaypoint)));
        assertFalse(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of(normal)));
        assertFalse(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of()));
        assertTrue(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of(withSubwaypoint)));
    }
}
