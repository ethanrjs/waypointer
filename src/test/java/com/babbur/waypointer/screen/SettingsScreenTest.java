package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomRouteProjection;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsScreenTest {

    @Test
    void projectedRouteAppearanceWritesToTheSavedRouteAndUndoRestoresBoth() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup stored = WaypointGroup.create("Room route", "spider");
        stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        stored.add(Waypoint.at(1, 2, 3));
        manager.add(stored);
        WaypointGroup mirror = new WaypointGroup(
                DungeonRoomRouteProjection.generatedGroupId("spider"), "Room route", "spider");
        mirror.setRuntimeOnly(true);
        mirror.setRuntimeSourceGroupId(stored.id());
        mirror.add(Waypoint.at(101, 2, 103));
        manager.add(mirror);
        var original = GroupEditScreen.RouteHistoryEntry.capture(manager, mirror);
        AppearanceTarget target = AppearanceTarget.route(manager, config, mirror, () -> { });
        WaypointerConfig draft = target.scope().effective();
        draft.setLabelScale(2);
        target.scope().capture(draft, "labelScale");
        assertEquals("2.0", stored.styleOverride().values().get("labelScale"));
        assertEquals(stored.styleOverride(), mirror.styleOverride());
        assertEquals(1, config.labelScale());
        assertNull(AppearanceTarget.waypoint(manager, config, mirror, 0, () -> { }));
        original.restore(mirror);
        assertTrue(stored.styleOverride().isEmpty());
        assertTrue(mirror.styleOverride().isEmpty());
    }

    @Test
    void waypointAppearanceOmitsRouteLineControls() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(1, 2, 3));
        manager.add(group);
        AppearanceTarget target = AppearanceTarget.waypoint(manager, new WaypointerConfig(), group, 0, () -> { });
        var settings = SettingsScreen.appearanceCategories(target::includes).stream()
                .flatMap(category -> category.groups().stream())
                .flatMap(section -> section.settings().stream()).map(Setting::id).toList();
        for (String id : List.of("showRouteLines", "routeLineWidth", "routeLineOpacity", "routeLineDashed")) {
            assertFalse(settings.contains(id), id);
        }
        assertTrue(settings.contains("labelScale"));
    }

    @Test
    void appearanceTitlesShowColourCodesAsColourNotText() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("&bBlue route", "hub");
        group.add(new Waypoint(1, 2, 3, "&aDrop down", 0xFFFFFF, 0, 0.0));
        manager.add(group);
        WaypointerConfig config = new WaypointerConfig();
        assertEquals("Drop down", ChatFormatting.stripFormatting(
                AppearanceTarget.waypoint(manager, config, group, 0, () -> { }).name().getString()));
        assertEquals("Blue route", ChatFormatting.stripFormatting(
                AppearanceTarget.route(manager, config, group, () -> { }).name().getString()));
    }

    @Test
    void unnamedDependentGroupsStayWithThePreviousNamedSection() {
        Setting first = SettingsCatalog.byId("showWaypointNames");
        Setting dependent = SettingsCatalog.byId("hideWaypointLabelsNearPlayer");
        Setting tracer = SettingsCatalog.byId("showTracer");
        SettingsCatalog.Category category = SettingsCatalog.Category.of("test", "Test",
                SettingsCatalog.Group.plain("labels", "Labels", first),
                SettingsCatalog.Group.parented((c, d) -> false, dependent),
                SettingsCatalog.Group.plain("tracers", "Tracers", tracer));
        assertEquals(2, SettingsScreen.sections(category).size());
        assertEquals(2, SettingsScreen.sections(category).getFirst().groups().size());
        assertEquals("labels", SettingsScreen.sectionForSetting(category, dependent.id()));
        assertEquals("tracers", SettingsScreen.sectionForSetting(category, tracer.id()));
        assertNull(SettingsScreen.sectionForSetting(category, "missing"));
    }

    @Test
    void leadingUnnamedSettingsKeepTheirGeneralHome() {
        SettingsCatalog.Category category = SettingsCatalog.Category.of("test", "Test",
                SettingsCatalog.Group.plain(SettingsCatalog.byId("boxStyle")),
                SettingsCatalog.Group.plain("labels", "Labels", SettingsCatalog.byId("showWaypointNames")));
        assertEquals("general", SettingsScreen.sectionForSetting(category, "boxStyle"));
        assertEquals("labels", SettingsScreen.sectionForSetting(category, "showWaypointNames"));
    }

    @Test
    void childGuidesStopAtTheLastVisibleChildsBranch() {
        assertEquals(28, SettingsScreen.childGuideHeight(28, false));
        assertEquals(13, SettingsScreen.childGuideHeight(28, true));
    }

    @Test
    void nextSequenceCountRejectsAll() {
        Setting setting = SettingsCatalog.byId("sequenceNextWaypointCount");

        assertEquals("32", SettingsText.localizedValue(setting, 32.0D).getString());
        assertNull(SettingsValuePolicy.acceptedNumberValue(setting, "All"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(setting, "33"));
    }

    @Test
    void presetsAppearAsMinimalDefaultAndNothing() {
        assertEquals(List.of("minimal", "default", "nothing"),
                SettingsValuePolicy.visiblePresetIds());
    }

    @Test
    void newEditModeVisualDefaultsMatchRequestedStates() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.editSounds());
        assertTrue(config.showEditModeSubtitle());
    }

    @Test
    void disableAllCountIncludesEveryEnabledDungeonToggle() {
        DungeonConfig config = new DungeonConfig();
        config.setDebugLogRoomChanges(true);
        config.setShowDungeonTracers(true);

        assertEquals(7, SettingsScreen.changedDungeonSettingsWhenDisabled(config));

        config.disableAllSettings();
        assertEquals(0, SettingsScreen.changedDungeonSettingsWhenDisabled(config));
    }

    @Test
    void settingsSearchClearButtonActivatesOnlyWhenQueryHasText() {
        assertFalse(SettingsValuePolicy.searchClearActive(null));
        assertFalse(SettingsValuePolicy.searchClearActive(""));
        assertTrue(SettingsValuePolicy.searchClearActive(" "));
        assertTrue(SettingsValuePolicy.searchClearActive("tracer"));
    }

    @Test
    void maxScrollClampsToZeroWhenContentFitsAndKeepsBottomSlackOtherwise() {
        assertEquals(0, SettingsScreen.maxScrollFor(100, 200));
        assertEquals(0, SettingsScreen.maxScrollFor(0, 200));
        assertEquals(108, SettingsScreen.maxScrollFor(300, 200));
        assertEquals(300 + 8, SettingsScreen.maxScrollFor(300, -50));
    }

    @Test
    void contentDeadStripSwallowsClicksAboveAndBelowTheViewportOnly() {
        int mainLeft = 180;
        int mainRight = 600;
        int top = 33;
        int rowsTop = 65;
        int bottom = 300;
        int footerTop = 332;

        assertTrue(SettingsScreen.inContentDeadStrip(200, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertTrue(SettingsScreen.inContentDeadStrip(200, 310, mainLeft, mainRight, top, rowsTop, bottom, footerTop));

        assertFalse(SettingsScreen.inContentDeadStrip(200, 100, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(50, 50, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
        assertFalse(SettingsScreen.inContentDeadStrip(200, 340, mainLeft, mainRight, top, rowsTop, bottom, footerTop));
    }

    @Test
    void enumCyclingAdvancesInOptionOrderAndWraps() {
        Setting boxStyle = SettingsCatalog.byId("boxStyle");

        assertEquals(WaypointerConfig.BoxStyle.FILLED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.OUTLINED));
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED));
        assertEquals(WaypointerConfig.BoxStyle.PAINT,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.FILLED_OUTLINED));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, WaypointerConfig.BoxStyle.PAINT));
        assertEquals(WaypointerConfig.BoxStyle.OUTLINED,
                SettingsValuePolicy.nextEnumValue(boxStyle, "garbage"));

    }

    @Test
    void boundedNumberInputRejectsInvalidTextInsteadOfSilentlyClamping() {
        Setting size = SettingsCatalog.byId("waypointMarkerScale");
        Setting duration = SettingsCatalog.byId("tempDefaultDurationSec");

        assertEquals(0.25, SettingsValuePolicy.acceptedNumberValue(size, "0.25"));
        assertEquals(3.0, SettingsValuePolicy.acceptedNumberValue(size, "3"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "0.24"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "3.01"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "NaN"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, "Infinity"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(size, ""));
        assertEquals(3.0, SettingsValuePolicy.acceptedNumberValue(duration, "3"));
        assertNull(SettingsValuePolicy.acceptedNumberValue(duration, "3.5"));
    }

    @Test
    void multiButtonRowsUseEqualColumnsAndTheSameOuterEdges() {
        int controlRight = 400;
        int clusterWidth = 232;

        assertEquals(112, SettingsScreen.actionGridButtonWidth(clusterWidth, 2));
        assertEquals(72, SettingsScreen.actionGridButtonWidth(clusterWidth, 3));
        assertEquals(168, SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 2, 0));
        assertEquals(168, SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 3, 0));
        assertEquals(controlRight,
                SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 2, 1)
                        + SettingsScreen.actionGridButtonWidth(clusterWidth, 2));
        assertEquals(controlRight,
                SettingsScreen.actionGridButtonX(controlRight, clusterWidth, 3, 2)
                        + SettingsScreen.actionGridButtonWidth(clusterWidth, 3));
    }

    @Test
    void tooltipNormalizationCollapsesLinesButKeepsParagraphBreaks() {
        assertEquals("", SettingsText.normalizeTooltip(null));
        assertEquals("one two", SettingsText.normalizeTooltip("one\ntwo"));
        assertEquals("one\n\ntwo", SettingsText.normalizeTooltip("one\n\ntwo"));
        assertEquals("a b\n\nc", SettingsText.normalizeTooltip("  a \r\n b \r\n\r\n c "));
    }

    @Test
    void descriptionlessSettingTooltipIsOnlyTheGrayControlLabel() {
        Setting setting = SettingsCatalog.byId("hideReachedStaticWaypointsUntilCycleComplete");

        Component tooltip = SettingsText.tooltip(setting);

        assertEquals(setting.label(), tooltip.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), tooltip.getStyle().getColor());
    }
    @Test
    void scopedAppearanceListsOnlyStylePropertiesGroupedBySection() {
        List<SettingsCatalog.Category> all = SettingsScreen.appearanceCategories(id -> true);
        Set<String> ids = new HashSet<>();
        for (SettingsCatalog.Category category : all) {
            for (SettingsCatalog.Group group : category.groups()) {
                assertNull(group.label(), "the sidebar section names the group");
                group.settings().forEach(setting -> ids.add(setting.id()));
            }
        }
        assertTrue(WaypointStyles.supportedProperties().containsAll(ids));
        assertTrue(ids.contains("showTracer"));
        assertTrue(ids.contains("hideWaypointsNearPlayer"), "appearance properties from other categories stay reachable");
        assertFalse(ids.contains("renderAntialiasing"), "global-only render settings are not per-route");
        assertEquals(SettingsScreen.SCOPED_SECTION_PREFIX + "waypoint_marker", all.getFirst().id());
        assertEquals(SettingsScreen.SCOPED_VISIBILITY_ID, all.getLast().id());

        List<SettingsCatalog.Category> waypointOnly =
                SettingsScreen.appearanceCategories(id -> !id.equals("defaultWaypointColor"));
        assertTrue(waypointOnly.stream().flatMap(c -> c.groups().stream())
                .flatMap(g -> g.settings().stream()).noneMatch(s -> s.id().equals("defaultWaypointColor")));
    }
}
