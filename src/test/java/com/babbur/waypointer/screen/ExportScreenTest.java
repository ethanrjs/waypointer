package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportScreenTest {

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
    void previewNavigationDoesNotChangeExportSelection() {
        boolean[] selected = {true, false, true, true};

        assertEquals(2, ExportScreen.navigatePreviewRouteIndex(selected, 0, 1));
        assertEquals(3, ExportScreen.navigatePreviewRouteIndex(selected, 0, -1));
        assertArrayEquals(new boolean[]{true, false, true, true}, selected);
    }

    @Test
    void previewNavigationLeavesEncodedOutputByteForByteUnchanged() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(1, 2, 3));
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        second.add(Waypoint.at(4, 5, 6));
        List<WaypointGroup> groups = List.of(first, second);
        boolean[] selected = {true, true};
        WaypointCodec.Options options = WaypointCodec.Options.builder().build();
        String before = WaypointExportCodec.encode(
                ExportScreen.selectedGroupsForExport(groups, selected), options,
                WaypointExportCodec.Target.WAYPOINTER);

        assertEquals(1, ExportScreen.navigatePreviewRouteIndex(selected, 0, 1));
        String after = WaypointExportCodec.encode(
                ExportScreen.selectedGroupsForExport(groups, selected), options,
                WaypointExportCodec.Target.WAYPOINTER);

        assertEquals(before, after);
    }

    @Test
    void deselectedPreviewRouteChoosesNextThenPrevious() {
        assertEquals(3, ExportScreen.replacementPreviewRouteIndex(
                new boolean[]{true, false, false, true}, 2));
        assertEquals(0, ExportScreen.replacementPreviewRouteIndex(
                new boolean[]{true, false, false, false}, 2));
    }

    @Test
    void initialRouteSelectionStartsEveryRouteSelected() {
        assertArrayEquals(new boolean[0], ExportScreen.initialRouteSelection(-1));
        assertArrayEquals(new boolean[0], ExportScreen.initialRouteSelection(0));
        assertArrayEquals(new boolean[]{true, true, true},
                ExportScreen.initialRouteSelection(3));
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
        boolean[] selected = {true, true, true};

        assertTrue(ExportScreen.toggleRouteSelectionState(selected, 1));
        assertArrayEquals(new boolean[]{true, false, true}, selected);
        assertEquals(2, ExportScreen.selectedGroupCount(selected));
        assertTrue(ExportScreen.hasExcludedRoutes(selected));

        assertTrue(ExportScreen.toggleRouteSelectionState(selected, 0));
        assertArrayEquals(new boolean[]{false, false, true}, selected);
        assertEquals(1, ExportScreen.selectedGroupCount(selected));

        assertFalse(ExportScreen.toggleRouteSelectionState(selected, 2));
        assertArrayEquals(new boolean[]{false, false, true}, selected);
        assertEquals(1, ExportScreen.selectedGroupCount(selected));
    }

    @Test
    void selectAllRoutesRestoresEveryExcludedRoute() {
        boolean[] selected = {false, true, false};

        ExportScreen.selectAllRouteSelectionState(selected);

        assertArrayEquals(new boolean[]{true, true, true}, selected);
        assertFalse(ExportScreen.hasExcludedRoutes(selected));
        assertEquals(3, ExportScreen.selectedGroupCount(selected));
    }

    @Test
    void selectedGroupsForExportPreservesOriginalOrderAndSingleRouteExports() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        WaypointGroup third = WaypointGroup.create("Third", "hub");

        assertEquals(List.of(first), ExportScreen.selectedGroupsForExport(
                List.of(first), new boolean[]{false}));
        assertEquals(List.of(first, third), ExportScreen.selectedGroupsForExport(
                List.of(first, second, third), new boolean[]{true, false, true}));
        assertEquals(List.of(), ExportScreen.selectedGroupsForExport(
                List.of(first, second, third), new boolean[]{false, false, false}));
    }

    @Test
    void codeBlockPayloadWrapsTheExactEncodedText() {
        assertEquals("```\nWP:abc\n```", ExportScreen.codeBlockPayload("WP:abc"));
        assertEquals("```\n\n```", ExportScreen.codeBlockPayload(null));
    }

    @Test
    void exportFitSummarySeparatesCommandFitFromChatFit() {
        ExportScreen.ExportFitSummary commandFit = ExportScreen.exportFitSummary("a".repeat(253));
        assertEquals(253, commandFit.characters());
        assertEquals(253, commandFit.wireBytes());
        assertEquals(256, commandFit.commandBytes());
        assertTrue(commandFit.chatOk());
        assertTrue(commandFit.commandOk());
        assertEquals("waypointer.export.fit.chat_and_commands", commandFit.messageKey());

        ExportScreen.ExportFitSummary chatOnly = ExportScreen.exportFitSummary("a".repeat(254));
        assertTrue(chatOnly.chatOk());
        assertFalse(chatOnly.commandOk());
        assertEquals("waypointer.export.fit.chat_only", chatOnly.messageKey());

        ExportScreen.ExportFitSummary tooLong = ExportScreen.exportFitSummary("a".repeat(257));
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

        assertTrue(ExportScreen.builderFromConfig(config, List.of(group)).build().includeZone);

        config.setExportIncludeZone(false);
        assertFalse(ExportScreen.builderFromConfig(config, List.of(group)).build().includeZone);
    }

    @Test
    void labelTooltipExplainsTargetSupport() {
        assertEquals("Optional title shown by Waypointer imports",
                ExportScreen.labelInputTooltipText(WaypointExportCodec.Target.WAYPOINTER));
        assertEquals("SkyHanni exports do not support Waypointer labels",
                ExportScreen.labelInputTooltipText(WaypointExportCodec.Target.SKYHANNI));
    }

    @Test
    void waypointerLabelOverridesTheLivePreviewRouteName() {
        assertEquals("Shared Route", ExportScreen.previewRouteName(
                "Original Route", " Shared Route ", WaypointExportCodec.Target.WAYPOINTER, 1));
        assertEquals("Original Route", ExportScreen.previewRouteName(
                "Original Route", "   ", WaypointExportCodec.Target.WAYPOINTER, 1));
        assertEquals("Original Route", ExportScreen.previewRouteName(
                "Original Route", "Shared Route", WaypointExportCodec.Target.SKYHANNI, 1));
        assertEquals("Original Route", ExportScreen.previewRouteName(
                "Original Route", "Bundle Name", WaypointExportCodec.Target.WAYPOINTER, 2));
    }

    @Test
    void previewOverflowTextUsesCompactCopy() {
        assertEquals("...1 more line", ExportScreen.previewOverflowText(1));
        assertEquals("...3 more lines", ExportScreen.previewOverflowText(3));
        assertEquals("...0 more lines", ExportScreen.previewOverflowText(-1));
    }

    @Test
    void builderDefaultsEnableWaypointFlagsForSubwaypointExports() {
        WaypointerConfig config = new WaypointerConfig();
        config.setExportIncludeWaypointFlags(false);
        WaypointGroup normal = WaypointGroup.create("Normal", "hub");
        normal.add(Waypoint.at(0, 64, 0));

        assertFalse(ExportScreen.builderFromConfig(config, List.of(normal))
                .build().includeWaypointFlags);

        WaypointGroup withSubwaypoint = WaypointGroup.create("Subway", "hub");
        withSubwaypoint.add(Waypoint.at(0, 64, 0));
        withSubwaypoint.add(Waypoint.at(1, 64, 1));
        assertTrue(withSubwaypoint.toggleSubwaypoint(1));

        assertTrue(ExportScreen.builderFromConfig(config, List.of(withSubwaypoint))
                .build().includeWaypointFlags);
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

        assertFalse(ExportScreen.showSubwaypointCompatibilityWarning(
                WaypointExportCodec.Target.WAYPOINTER, List.of(withSubwaypoint)));
        assertFalse(ExportScreen.showSubwaypointCompatibilityWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of(normal)));
        assertFalse(ExportScreen.showSubwaypointCompatibilityWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of()));
        assertTrue(ExportScreen.showSubwaypointCompatibilityWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of(withSubwaypoint)));
    }
}
