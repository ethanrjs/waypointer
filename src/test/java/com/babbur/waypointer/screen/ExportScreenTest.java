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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportScreenTest {

    @Test
    void dungeonRoomsExportWholeAsV11WhateverTheSettings() {
        WaypointGroup route = WaypointGroup.create("Crypt", "crypt-a");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.add(new Waypoint(1, 70, 2, "Chest", 0x2EE0FF, Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        WaypointCodec.Options stripped = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeNames(false).includeColors(false).build();

        String payload = ExportScreen.encode(List.of(route), stripped,
                WaypointExportCodec.Target.WAYPOINTER, RouteLibraryMetadata.empty(), new WaypointerConfig());

        assertEquals(UniversalShareCodec.encodeDungeon(List.of(route)), payload);
        assertTrue(payload.startsWith("WP:") && WaypointCodec.debugDecode(payload).version() == 11);
        assertTrue(UniversalShareCodec.decode(payload)
                instanceof UniversalShareCodec.DungeonRoutes);
    }

    @Test
    void dungeonRoomsOfferOnlyFormatsThatUnderstandRooms() {
        WaypointGroup room = WaypointGroup.create("Crypt", "crypt-a");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup island = WaypointGroup.create("Ruby", "crystal_hollows");

        assertEquals(List.of(WaypointExportCodec.Target.WAYPOINTER, WaypointExportCodec.Target.ODIN),
                ExportScreen.formatsFor(List.of(room)));
        assertEquals(List.of(WaypointExportCodec.Target.values()),
                ExportScreen.formatsFor(List.of(room, island)));
        assertEquals(List.of(WaypointExportCodec.Target.values()), ExportScreen.formatsFor(List.of(island)));
    }

    @Test
    void aFormatThatCannotCarryTheRouteSaysWhy() {
        WaypointGroup route = WaypointGroup.create("Odd", "custom_island_name");
        route.add(Waypoint.at(1, 70, 2));

        ExportScreen.EncodeResult result = ExportScreen.encodeResult(List.of(route),
                WaypointCodec.Options.FULL_FIDELITY, WaypointExportCodec.Target.ODIN,
                RouteLibraryMetadata.empty(), new WaypointerConfig());

        assertEquals("", result.code());
        assertTrue(result.error().startsWith("Odin does not recognize"), result.error());
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
    void individualCaptureSkipsFoldersBeforeValidationButKeepsRouteMetadata() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3).withColor(0x112233));
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.set(0, route.get(0).withColor(0xAABBCC));
        route.setGradientMode(WaypointGroup.GradientMode.STATIC);
        manager.add(route);
        manager.addFolder(new RouteFolder(
                "folder", "A folder with metadata", "hub", true, 0x123456),
                List.of(route.id()));

        RouteLibraryMetadata individual = RouteLibraryMetadata.captureForExport(
                manager, List.of(route), false);
        RouteLibraryMetadata folderShare = RouteLibraryMetadata.captureForExport(
                manager, List.of(route), true);

        assertEquals(1, individual.manualColors().size());
        assertTrue(individual.folders().isEmpty());
        assertEquals(List.of(0), folderShare.folders().getFirst().memberOrdinals());
    }

    @Test
    void plainIndividualCaptureUsesTheDirectRouteWireShape() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(1, 2, 3));
        manager.add(route);
        manager.addFolder(new RouteFolder(
                "folder", "Source folder", "hub", false, 0x123456),
                List.of(route.id()));

        RouteLibraryMetadata metadata = RouteLibraryMetadata.captureForExport(
                manager, List.of(route), false);
        String payload = WaypointExportCodec.encode(
                List.of(route), WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER, metadata);

        assertTrue(WaypointCodec.debugDecode(payload).groups().stream()
                .noneMatch(group -> group.coordMode().startsWith("V10_LIBRARY_")));
    }

    @Test
    void individualCaptureDoesNotValidateAnUnsharedOversizedFolderName() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(1, 2, 3));
        manager.add(route);
        manager.addFolder(new RouteFolder(
                "folder", "x".repeat(RouteLibraryMetadata.MAX_FOLDER_NAME_CHARS + 1),
                "hub", false, 0x123456), List.of(route.id()));

        RouteLibraryMetadata metadata = assertDoesNotThrow(() ->
                RouteLibraryMetadata.captureForExport(manager, List.of(route), false));

        assertTrue(metadata.isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                RouteLibraryMetadata.captureForExport(manager, List.of(route), true));
    }

    @Test
    void exportScopeSubtitleFollowsRouteCountAndScopeIntent() {
        assertEquals("waypointer.screen.export.subtitle.route.many",
                ExportScreen.subtitleKey(ExportScreen.ScopeKind.SELECTION, 1, 2));
        assertEquals("waypointer.screen.export.subtitle.folder.one",
                ExportScreen.subtitleKey(ExportScreen.ScopeKind.FOLDER, 1, 4));
        assertEquals("waypointer.screen.export.subtitle.selection.many",
                ExportScreen.subtitleKey(ExportScreen.ScopeKind.SELECTION, 2, 4));
        assertEquals("waypointer.screen.export.subtitle.zone.many",
                ExportScreen.subtitleKey(ExportScreen.ScopeKind.ZONE, 3, 4));
    }

    @Test
    void selectedRoutesSubtitleUsesOnlyItsTwoCountArguments() {
        Component subtitle = ExportScreen.subtitleComponent(
                ExportScreen.ScopeKind.SELECTION, "", "ignored", 2, 4);
        TranslatableContents contents = assertInstanceOf(
                TranslatableContents.class, subtitle.getContents());

        assertEquals("waypointer.screen.export.subtitle.selection.many", contents.getKey());
        assertEquals(2, contents.getArgs().length);
        assertEquals(2, contents.getArgs()[0]);
        assertEquals(4, contents.getArgs()[1]);
    }

    @Test
    void everyExportLayoutKeepsFormatsCodeAndFooterOnScreen() {
        for (int[] size : new int[][]{{320, 240}, {320, 480}, {360, 270}, {427, 240}, {480, 270},
                {854, 480}, {960, 540}, {1280, 720}}) {
            for (boolean multiRoute : new boolean[]{false, true}) {
                String name = size[0] + "x" + size[1] + (multiRoute ? " routes" : " route");
                ExportScreen.LayoutPolicy layout = ExportScreen.layoutPolicy(size[0], size[1], 6, 56,
                        multiRoute, multiRoute, multiRoute ? 4 : 1, 10, 24, 96);

                assertTrue(layout.panelX() >= 0 && layout.panelY() >= 0, name);
                assertTrue(layout.panelX() + layout.panelWidth() <= size[0], name);
                assertTrue(layout.panelBottom() <= size[1], name);
                assertTrue(layout.footerBottom() <= layout.panelBottom(), name);
                assertTrue(layout.formatRowsY() + layout.formatRows() * BTN_H <= layout.sizeY(), name);
                assertTrue(layout.codeY() + layout.codeHeight() < layout.footerY(), name);
                if (multiRoute) {
                    assertTrue(layout.routeRows() >= 1, name);
                    assertTrue(layout.routeListY() + layout.routeRows() * 26 <= layout.sizeY(), name);
                }
            }
        }
    }

    @Test
    void roomyScreensGetTheWidePanelAndAFullCodeBox() {
        ExportScreen.LayoutPolicy layout = ExportScreen.layoutPolicy(854, 480, 6, 56, true, true, 4, 10, 24, 96);

        assertEquals(480, layout.panelWidth());
        assertEquals(4 * 10 + 16, layout.codeHeight());
        assertEquals(4, layout.routeRows());
    }

    @Test
    void theCodeShowsEvenAtTheLargestGuiScale() {
        ExportScreen.LayoutPolicy layout = ExportScreen.layoutPolicy(427, 240, 6, 56, false, false, 1, 10, 24, 96);

        assertTrue(layout.codeHeight() >= 10 + 16, "code box is " + layout.codeHeight());
        assertEquals(2, layout.formatRows());
    }

    @Test
    void footerTakesAnotherRowOnlyWhenTheCopyLabelsCannotFitBesideBack() {
        assertArrayEquals(new int[]{133, 133, 1}, ExportScreen.footerColumns(416, 24, 96));
        assertArrayEquals(new int[]{40, 116, 1}, ExportScreen.footerColumns(288, 24, 96));
        assertArrayEquals(new int[]{228, 110, 2}, ExportScreen.footerColumns(228, 24, 96));

        ExportScreen.LayoutPolicy normal = ExportScreen.layoutPolicy(
                736, 480, 6, 56, false, false, 1, 10, 24, 96);
        ExportScreen.LayoutPolicy translated = ExportScreen.layoutPolicy(
                736, 480, 6, 56, false, false, 1, 10, 24, 220);
        assertEquals(1, normal.footerRows());
        assertEquals(2, translated.footerRows());
        assertEquals(BTN_H + GAP, translated.panelHeight() - normal.panelHeight());
    }

    @Test
    void formatsShareARowUntilTheirNamesWouldNotFit() {
        assertEquals(6, ExportScreen.formatColumns(600, 6, 56));
        assertEquals(3, ExportScreen.formatColumns(448, 6, 56));
        assertEquals(2, ExportScreen.formatColumns(200, 6, 56));
        assertEquals(1, ExportScreen.formatColumns(60, 6, 56));
        assertEquals(2, ExportScreen.formatColumns(228, 2, 56));
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
    void builderDefaultsFollowTheConfiguredIslandPreference() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(0, 64, 0));

        assertTrue(ExportPolicy.optionsFromConfig(config, List.of(group)).build().includeZone);

        config.setExportIncludeZone(false);
        assertFalse(ExportPolicy.optionsFromConfig(config, List.of(group)).build().includeZone);
    }

    @Test
    void allOffPolicySelectsKind2AndKind6AndTracksToggleHistory() {
        WaypointerConfig config = new WaypointerConfig();
        config.setExportIncludeNames(false);
        config.setExportIncludeColors(false);
        config.setExportIncludeRadii(false);
        config.setExportIncludeWaypointFlags(false);
        config.setExportIncludeGroupMeta(false);
        config.setExportIncludeZone(false);
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
        assertEquals(11, WaypointCodec.debugDecode(payload).version());
        assertTrue(UniversalShareCodec.decode(payload)
                instanceof UniversalShareCodec.DungeonRoutes);

        WaypointGroup regular = WaypointGroup.create("Regular", "hub");
        regular.add(Waypoint.at(4, 65, 6));
        assertFalse(ExportPolicy.optionsFromConfig(
                config, List.of(regular, dungeon)).build().isBareCoordinateProjection());
        assertFalse(ExportPolicy.optionsFromConfig(
                config, List.of()).build().isBareCoordinateProjection());
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
                WaypointExportCodec.Target.SKYHANNI, List.of(normal)));
        assertFalse(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of()));
        assertTrue(ExportPolicy.showSubwaypointWarning(
                WaypointExportCodec.Target.SKYHANNI, List.of(withSubwaypoint)));
    }
}
