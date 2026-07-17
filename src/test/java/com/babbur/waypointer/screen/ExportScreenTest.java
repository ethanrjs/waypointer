package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointExportCodec;
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
        assertEquals("Can fit in chat and commands", commandFit.message());

        ExportScreen.ExportFitSummary chatOnly = ExportScreen.exportFitSummary("a".repeat(254));
        assertTrue(chatOnly.chatOk());
        assertFalse(chatOnly.commandOk());
        assertEquals("Can fit in chat", chatOnly.message());

        ExportScreen.ExportFitSummary tooLong = ExportScreen.exportFitSummary("a".repeat(257));
        assertFalse(tooLong.chatOk());
        assertFalse(tooLong.commandOk());
        assertEquals("Too long for chat or commands (like /pc)", tooLong.message());
    }

    @Test
    void labelTooltipExplainsTargetSupport() {
        assertEquals("Optional title shown by Waypointer imports",
                ExportScreen.labelInputTooltipText(WaypointExportCodec.Target.WAYPOINTER));
        assertEquals("SkyHanni exports do not support Waypointer labels",
                ExportScreen.labelInputTooltipText(WaypointExportCodec.Target.SKYHANNI));
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
