package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsCatalogTest {

    private static final Set<String> MAIN_EXEMPT = Set.of(
            "configSchemaVersion",
            "waypointPainterPalette",
            "waypointPainterDefaultPalette",
            "waypointPainterDefaultPixels");

    private static final Set<String> DUNGEON_EXEMPT = Set.of(
            "debugLogRoomChanges",
            "defaultDirection", "visibleSecretStages");

    @Test
    void mainEntriesCoverEveryWaypointerConfigField() {
        Set<String> fields = persistedFieldNames(WaypointerConfig.class, MAIN_EXEMPT);
        Set<String> catalogIds = idsByStore(Setting.Store.MAIN);
        assertEquals(fields, catalogIds,
                "WaypointerConfig fields and catalog MAIN ids must match exactly; "
                        + "catalog new fields or add them to MAIN_EXEMPT deliberately");
    }

    @Test
    void crystalHollowsCategoryUsesMainStoreAndMasterSwitch() {
        SettingsCatalog.Category category = SettingsCatalog.categories().stream()
                .filter(candidate -> candidate.id().equals("crystal_hollows"))
                .findFirst().orElseThrow();

        assertEquals("crystalHollowsEnabled", category.masterSettingId());
        assertEquals(9, category.groups().stream()
                .flatMap(group -> group.settings().stream()).count());
        assertTrue(category.groups().stream()
                .flatMap(group -> group.settings().stream())
                .allMatch(setting -> setting.store() == Setting.Store.MAIN));
        Setting master = SettingsCatalog.byId("crystalHollowsEnabled");
        assertNotNull(master);
        assertTrue(master.aliases().containsAll(
                List.of("mining", "compass", "hollows", "divan", "temple")));
        WaypointerConfig config = new WaypointerConfig();
        assertTrue(category.bodyVisibleWhen().test(config, null));
        config.setCrystalHollowsEnabled(false);
        assertFalse(category.bodyVisibleWhen().test(config, null));
    }

    @Test
    void dungeonEntriesCoverEveryPlayerFacingDungeonConfigField() {
        Set<String> fields = persistedFieldNames(DungeonConfig.class, DUNGEON_EXEMPT);
        Set<String> catalogIds = idsByStore(Setting.Store.DUNGEON);
        assertEquals(fields, catalogIds);
    }

    @Test
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Setting setting : SettingsCatalog.allSettings()) {
            assertTrue(seen.add(setting.id()), "duplicate setting id: " + setting.id());
        }
    }

    @Test
    void translatedGroupsAndEnumOptionsUseStableSemanticIds() {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            Set<String> groupIds = new HashSet<>();
            for (SettingsCatalog.Group group : category.groups()) {
                if (group.label() == null) continue;
                assertNotNull(group.id(), category.id() + " has a translated group without an id");
                assertFalse(group.id().isBlank(), category.id() + " has a translated group without an id");
                assertTrue(groupIds.add(group.id()),
                        category.id() + " has duplicate group id " + group.id());
                assertEquals("waypointer.settings.group." + category.id() + "." + group.id(),
                        SettingsCatalog.groupTranslationKey(category, group));
            }
        }

        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() != Setting.Kind.ENUM) continue;
            Set<String> optionIds = new HashSet<>();
            for (int i = 0; i < setting.enumOptions().size(); i++) {
                Setting.EnumOption option = setting.enumOptions().get(i);
                assertTrue(optionIds.add(option.id()),
                        setting.id() + " has duplicate enum option id " + option.id());
                assertEquals("waypointer.settings.setting." + setting.id() + ".option." + option.id(),
                        setting.enumOptionTranslationKey(i));
                assertEquals("waypointer.settings.setting." + setting.id() + ".option." + i,
                        setting.legacyEnumOptionTranslationKey(i));
            }
        }
    }

    @Test
    void everyVisibleEntryHasALabelAndNoWhitespaceOnlyTooltip() {
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() == Setting.Kind.HIDDEN) continue;
            assertFalse(setting.label().isBlank(), setting.id() + " needs a label");
            assertEquals(setting.tooltip().isEmpty(), setting.tooltip().isBlank(),
                    setting.id() + " has a whitespace-only tooltip");
        }
    }

    @Test
    void enumEntriesListDistinctOptionsIncludingTheDefault() {
        WaypointerConfig defaults = new WaypointerConfig();
        DungeonConfig dungeonDefaults = new DungeonConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() != Setting.Kind.ENUM) continue;
            List<Setting.EnumOption> options = setting.enumOptions();
            assertTrue(options.size() >= 2, setting.id() + " needs at least two options");
            Set<Object> values = new HashSet<>();
            for (Setting.EnumOption option : options) {
                assertFalse(option.label().isBlank());
                assertTrue(values.add(option.value()), setting.id() + " has duplicate option values");
            }
            Object defaultValue = setting.defaultValue(defaults, dungeonDefaults);
            assertTrue(values.contains(defaultValue),
                    setting.id() + " default " + defaultValue + " is not among its options");
        }
    }

    @Test
    void parentedGroupsLeadWithTheirParentAndMastersExist() {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            Set<String> categoryIds = new HashSet<>();
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) categoryIds.add(setting.id());
                if (group.parentSettingId() != null) {
                    assertEquals(group.parentSettingId(), group.settings().get(0).id(),
                            "parented group in " + category.id() + " must lead with its parent");
                    assertNotNull(group.childrenVisibleWhen(),
                            "parented group in " + category.id() + " needs a collapse predicate");
                }
            }
            if (category.masterSettingId() != null) {
                assertTrue(categoryIds.contains(category.masterSettingId()),
                        category.id() + " master must be one of its own settings");
                assertNotNull(category.bodyVisibleWhen());
            }
        }
    }

    @Test
    void everyMainEntrySurvivesTheCodecRoundTrip() {
        WaypointerConfig defaults = new WaypointerConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN) continue;

            WaypointerConfig live = new WaypointerConfig();
            Object probe = probeValue(setting, defaults);
            setting.set(live, null, probe);
            assertNotEquals(setting.get(defaults, null), setting.get(live, null),
                    setting.id() + " probe did not change the live value (clamped back to default?)");

            assertEquals(1, SettingsCatalog.countChangedSettings(defaults, live),
                    setting.id() + " probe should register as exactly one changed setting");

            WaypointerConfig decoded = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(live));
            assertEquals(setting.get(live, null), setting.get(decoded, null),
                    setting.id() + " did not survive the config-code round trip");
        }
    }

    @Test
    void probingEveryMainEntryCountsThemAllAndRoundTripsToZeroDiff() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig live = new WaypointerConfig();
        int mainEntries = 0;
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN) continue;
            setting.set(live, null, probeValue(setting, defaults));
            mainEntries++;
        }
        assertEquals(mainEntries, SettingsCatalog.countChangedSettings(defaults, live));

        WaypointerConfig decoded = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(live));
        assertEquals(0, SettingsCatalog.countChangedSettings(live, decoded));
    }

    @Test
    void hiddenLegacyEntriesStillCountInTheImportDiff() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig decoded = new WaypointerConfig();
        decoded.setDungeonWaypointsFeatureEnabled(true);
        assertEquals(1, SettingsCatalog.countChangedSettings(defaults, decoded));

        WaypointerConfig withBadges = new WaypointerConfig();
        withBadges.setShowContributorBadges(false);
        assertEquals(1, SettingsCatalog.countChangedSettings(defaults, withBadges),
                "the old hand-written diff missed showContributorBadges; the derived one must not");
    }

    @Test
    void dungeonEntriesReadAndWriteTheDungeonConfig() {
        DungeonConfig dungeon = new DungeonConfig();
        DungeonConfig dungeonDefaults = new DungeonConfig();
        WaypointerConfig config = new WaypointerConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.DUNGEON) continue;
            Object defaultValue = setting.get(config, dungeonDefaults);
            Object probe = probeValue(setting, new WaypointerConfig(), dungeonDefaults);
            setting.set(config, dungeon, probe);
            assertNotEquals(defaultValue, setting.get(config, dungeon),
                    setting.id() + " probe did not change the dungeon value");
            assertTrue(setting.isModified(config, dungeon, config, dungeonDefaults));
        }
    }

    @Test
    void combinedDiffIncludesMainAndDungeonSettings() {
        WaypointerConfig liveMain = new WaypointerConfig();
        DungeonConfig liveDungeon = new DungeonConfig();
        WaypointerConfig nextMain = new WaypointerConfig();
        DungeonConfig nextDungeon = new DungeonConfig();
        nextMain.setShowTracer(false);
        nextDungeon.setEnabled(false);

        assertEquals(2, SettingsCatalog.countChangedSettings(
                liveMain, liveDungeon, nextMain, nextDungeon));
    }

    @Test
    void actionEntriesNeverDiffOrReportModified() {
        WaypointerConfig a = new WaypointerConfig();
        WaypointerConfig b = new WaypointerConfig();
        assertEquals(0, SettingsCatalog.countChangedSettings(a, b));
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.kind() != Setting.Kind.ACTION) continue;
            assertEquals(Setting.Store.NONE, setting.store());
            assertFalse(setting.isModified(a, null, b, null));
        }
    }

    @Test
    void waypointPainterIsAnActionInTheAppearanceCategory() {
        Setting paint = SettingsCatalog.byId(SettingsCatalog.ACTION_WAYPOINT_PAINT);

        assertNotNull(paint);
        assertEquals("Paint", paint.label());
        assertEquals(Setting.Kind.ACTION, paint.kind());
        assertEquals("appearance", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals(SettingsCatalog.ACTION_WAYPOINT_PAINT)))
                .findFirst().orElseThrow().id());
    }

    @Test
    void globalWaypointAppearanceSettingsLiveTogether() {
        for (String id : List.of(
                "boxStyle", "beaconOpacity", "waypointOutlineThickness",
                "waypointMarkerScale", "waypointOutlineOpacity", "defaultWaypointColor",
                "matchWaypointOutlineToWaypointColor", "waypointOutlineColor",
                "showWaypointNames", "showWaypointDistances", "showLabelBackdrop",
                "showTracer", "tracerColor", "beaconBeamMode",
                "showRouteLines", "routeLineColor")) {
            assertEquals("appearance", categoryContaining(id), id);
        }
        assertFalse(SettingsCatalog.categories().stream()
                .map(SettingsCatalog.Category::id)
                .anyMatch(List.of("labels", "tracers", "beams")::contains));
    }

    @Test
    void markerAppearanceControlsFollowVisualDependencyOrder() {
        SettingsCatalog.Category appearance = SettingsCatalog.categories().stream()
                .filter(category -> category.id().equals("appearance"))
                .findFirst().orElseThrow();

        assertEquals(List.of(
                        "boxStyle",
                        "waypointMarkerScale",
                        "defaultWaypointColor",
                        "beaconOpacity",
                        "matchWaypointOutlineToWaypointColor",
                        "waypointOutlineColor",
                        "waypointOutlineOpacity",
                        "waypointOutlineThickness",
                        SettingsCatalog.ACTION_WAYPOINT_PAINT),
                appearance.groups().getFirst().settings().stream().map(Setting::id).toList());
    }

    @Test
    void clampedNumberSettingsDeclareTheSameInputBounds() {
        assertRange("maxStaticWaypointRenderDistance", 0.0, Double.POSITIVE_INFINITY, false);
        assertRange("tempDefaultDurationSec", 1.0, 86_400.0, true);
        assertRange("beaconOpacity", 0.0, 1.0, false);
        assertRange("waypointOutlineThickness", 1.0, 12.0, false);
        assertRange("waypointMarkerScale", 0.25, 3.0, false);
        assertRange("waypointOutlineOpacity", 0.0, 1.0, false);
        assertRange("labelScale", 0.25, 4.0, false);
        assertRange("maxWaypointLabels", 0.0, Integer.MAX_VALUE, true);
        assertRange("hideWaypointLabelsNearRadius", 0.5, 100.0, false);
        assertRange("tracerOpacity", 0.0, 1.0, false);
        assertRange("tracerThickness", 1.0, 12.0, false);
        assertRange("defaultReachRadius", 0.5, 100.0, false);
        assertRange("hideWaypointsNearRadius", 0.5, 100.0, false);
    }

    @Test
    void routeIndicesAreOptInUnderRoutesAndSurviveCodecRoundTrip() {
        Setting routeIndices = SettingsCatalog.byId("showRouteIndicesInGui");

        assertNotNull(routeIndices);
        assertEquals("Show route indices", routeIndices.label());
        assertEquals("In the route list, show each route's index number for commands.",
                routeIndices.tooltip());
        assertEquals(false, routeIndices.get(new WaypointerConfig(), null));
        assertEquals("routes", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals("showRouteIndicesInGui")))
                .findFirst().orElseThrow().id());

        WaypointerConfig enabled = new WaypointerConfig();
        routeIndices.set(enabled, null, true);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(enabled));
        assertEquals(true, routeIndices.get(decoded, null));
    }

    @Test
    void reachedSubwaypointHoldIsOnByDefaultUnderRoutesAndSurvivesCodecRoundTrip() {
        String id = "keepSubwaypointsVisibleUntilNextWaypoint";
        Setting setting = SettingsCatalog.byId(id);

        assertNotNull(setting);
        assertEquals("Keep subwaypoints until next waypoint", setting.label());
        assertEquals(true, setting.get(new WaypointerConfig(), null));
        assertEquals("routes", SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(candidate -> candidate.id().equals(id)))
                .findFirst().orElseThrow().id());

        WaypointerConfig disabled = new WaypointerConfig();
        setting.set(disabled, null, false);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(disabled));
        assertEquals(false, setting.get(decoded, null));
    }

    @Test
    void dungeonEntryPathIsOffByDefaultInTheCatalog() {
        Setting entryPath = SettingsCatalog.byId("showDungeonEntryPathToFirstWaypoint");

        assertNotNull(entryPath);
        assertEquals("Pathfind to First Waypoint", entryPath.label());
        assertEquals("", entryPath.tooltip());
        assertEquals("Pathfind to All Waypoints",
                SettingsCatalog.byId("showDungeonEntryPathToFollowingWaypoints").label());
        assertEquals("", SettingsCatalog.byId("showDungeonEntryPathToFollowingWaypoints").tooltip());
        assertEquals(false, entryPath.get(new WaypointerConfig(), new DungeonConfig()));
    }

    @Test
    void etherwarpAlignmentSoundSelectorIsOffUnderWaypointVisibility() {
        Setting setting = SettingsCatalog.byId("etherwarpAlignmentSound");

        assertNotNull(setting);
        assertEquals("Etherwarp alignment sound", setting.label());
        assertEquals("Choose a sound to play when you can etherwarp to a waypoint.",
                setting.tooltip());
        assertEquals(Setting.Kind.ENUM, setting.kind());
        assertEquals(List.of("off", "experience", "pling", "bell"),
                setting.enumOptions().stream().map(Setting.EnumOption::id).toList());
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF,
                setting.get(new WaypointerConfig(), new DungeonConfig()));
        assertEquals(Setting.Store.MAIN, setting.store());
        assertEquals("appearance", categoryContaining(setting.id()));
        assertEquals("waypoint_visibility", groupContaining(setting.id()));
    }

    @Test
    void sequenceVisibilityControlsUseBoundedWholeNumbers() {
        assertRange("sequencePreviousWaypointCount", 0,
                SequenceVisibility.MAX_CONTEXT_WAYPOINTS, true);
        assertRange("sequenceNextWaypointCount", 0,
                SequenceVisibility.MAX_CONTEXT_WAYPOINTS, true);
        Setting previous = SettingsCatalog.byId("sequencePreviousWaypointCount");
        Setting next = SettingsCatalog.byId("sequenceNextWaypointCount");
        assertEquals("All", previous.formatValue((double) SequenceVisibility.ALL));
        assertEquals("32", previous.formatValue(32.0D));
        assertNull(next.numberDisplayValue());
        assertEquals("Amount of surroundings you want to see. 0-32", next.tooltip());
        assertFalse(previous.tooltip().contains("33"));
        assertEquals(Setting.Kind.BOOL,
                SettingsCatalog.byId("showCurrentSequenceWaypoint").kind());
        for (String id : List.of(
                "sequencePreviousWaypointCount",
                "showCurrentSequenceWaypoint",
                "sequenceNextWaypointCount")) {
            assertEquals("appearance", categoryContaining(id), id);
            assertEquals("waypoint_visibility", groupContaining(id), id);
        }
    }

    @Test
    void dungeonRoutePresentationDefaultsLiveInTheDungeonsCategory() {
        WaypointerConfig main = new WaypointerConfig();
        DungeonConfig dungeon = new DungeonConfig();
        for (String id : List.of(
                "showDungeonRouteLines",
                "showDungeonTracers")) {
            Setting setting = SettingsCatalog.byId(id);
            assertNotNull(setting, id);
            assertEquals(Setting.Store.DUNGEON, setting.store(), id);
            assertEquals("dungeons", categoryContaining(id), id);
        }
        assertEquals(true, SettingsCatalog.byId("showDungeonRouteLines").get(main, dungeon));
        assertEquals(false, SettingsCatalog.byId("showDungeonTracers").get(main, dungeon));
    }

    @Test
    void theExportRoutePreviewIsOffByDefaultUnderSharing() {
        Setting preview = SettingsCatalog.byId("showExportRoutePreview");

        assertNotNull(preview);
        assertEquals("3D route preview", preview.label());
        assertEquals("Show a rotating 3D preview of the route on the export screen.",
                preview.tooltip());
        assertEquals(false, preview.get(new WaypointerConfig(), null));
        assertEquals("sharing", categoryContaining("showExportRoutePreview"));

        WaypointerConfig enabled = new WaypointerConfig();
        preview.set(enabled, null, true);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(enabled));
        assertEquals(true, preview.get(decoded, null));
    }

    @Test
    void formatValueRendersEveryKindReadably() {
        assertEquals("On", Setting.formatValue(Setting.Kind.BOOL, Boolean.TRUE, List.of()));
        assertEquals("Off", Setting.formatValue(Setting.Kind.BOOL, Boolean.FALSE, List.of()));
        assertEquals("3", Setting.formatValue(Setting.Kind.NUMBER, 3.0, List.of()));
        assertEquals("0.95", Setting.formatValue(Setting.Kind.NUMBER, 0.95, List.of()));
        assertEquals("#00FF00", Setting.formatValue(Setting.Kind.COLOR, 0x00FF00, List.of()));
        assertEquals("Gradient", Setting.formatValue(Setting.Kind.ENUM, "AUTO",
                List.of(new Setting.EnumOption("One color", "STATIC"),
                        new Setting.EnumOption("Gradient", "AUTO"))));
    }

    private static Object probeValue(Setting setting, WaypointerConfig defaults) {
        return probeValue(setting, defaults, new DungeonConfig());
    }

    @Test
    void conciseSettingsCopyMatchesTheRenderedControls() {
        assertEquals("Focus mode for temporary waypoints",
                SettingsCatalog.byId("focusTempWaypoints").label());
        assertEquals("Show only the temporary waypoint that is active.",
                SettingsCatalog.byId("focusTempWaypoints").tooltip());
        assertEquals("Dim surrounding waypoints",
                SettingsCatalog.byId("dimSequenceContextWaypoints").label());
        assertEquals("Enable waypoint skipping",
                SettingsCatalog.byId("skipAheadMechanicEnabled").label());
        for (String id : List.of(
                "tempDefaultMode", "waypointMarkerScale", "waypointOutlineColor",
                "waypointOutlineOpacity", "dimSequenceContextWaypoints", "showRouteProgress",
                "showLabelTextShadow", "labelHeightOffset", "hideWaypointLabelsNearPlayer",
                "tracerOpacity", "tracerColor", "beaconBeamMode", "useBeaconBeamTextures",
                "beaconBeamExtendsBelowWaypoint", "routeLineColor", "resetProgressOnWorldJoin",
                "keepSubwaypointsVisibleUntilNextWaypoint",
                "hideReachedStaticWaypointsUntilCycleComplete", "skipAheadMechanicEnabled",
                "enabled", "hideCompletedRooms", "secretCompletionSound",
                "showDungeonRouteLines", "showDungeonTracers",
                "showDungeonEntryPathToFirstWaypoint",
                "showDungeonEntryPathToFollowingWaypoints", "dungeonEntryPathColor",
                "chatCoordDetection", "autoAddChatTempWaypoints",
                "showWaypointChatShareButtons", "importedRouteDefaultColor",
                "exportIncludeNames", "exportIncludeColors", "exportIncludeRadii",
                "exportIncludeWaypointFlags", "exportIncludeGroupMeta", "exportIncludeZone",
                "irisShaderHudFallback", SettingsCatalog.ACTION_CONFIG_CODE,
                SettingsCatalog.ACTION_PRESETS, SettingsCatalog.ACTION_PERF_TEST)) {
            assertEquals("", SettingsCatalog.byId(id).tooltip(), id);
        }
        assertEquals("Create Waypoints from Chat Messages",
                SettingsCatalog.byId("autoAddChatTempWaypoints").label());
        assertEquals("Chat Share Buttons",
                SettingsCatalog.byId("showWaypointChatShareButtons").label());
        assertEquals("Overrides imported waypoint colors.",
                SettingsCatalog.byId("importedRouteColorMode").tooltip());
        assertEquals("", SettingsCatalog.byId("dungeonEntryPathColor").colorSwatchTooltip());
        assertEquals("", SettingsCatalog.byId("importedRouteDefaultColor").colorSwatchTooltip());
        assertNull(SettingsCatalog.byId("routeTimesEnabled"));
        assertNull(SettingsCatalog.byId("autoCompleteRoomsOnGreenCheckmark"));
        assertNull(SettingsCatalog.byId("visibleSecretStages"));
        assertNull(SettingsCatalog.byId(SettingsCatalog.ACTION_DISABLE_ALL));
        assertNull(SettingsCatalog.byId(SettingsCatalog.ACTION_RESET_DEFAULTS));
    }

    private static void assertRange(String id, double minimum, double maximum,
                                    boolean wholeNumber) {
        Setting setting = SettingsCatalog.byId(id);
        assertNotNull(setting, id);
        assertEquals(minimum, setting.minimum(), id);
        assertEquals(maximum, setting.maximum(), id);
        assertEquals(wholeNumber, setting.requiresWholeNumber(), id);
    }

    private static Object probeValue(Setting setting, WaypointerConfig defaults, DungeonConfig dungeonDefaults) {
        Object defaultValue = setting.get(defaults, dungeonDefaults);
        if (setting.kind() == Setting.Kind.ENUM) {
            for (Setting.EnumOption option : setting.enumOptions()) {
                if (!Objects.equals(option.value(), defaultValue)) return option.value();
            }
            throw new AssertionError(setting.id() + " has no non-default option");
        }
        if (setting.kind() == Setting.Kind.COLOR) {
            return ((Number) defaultValue).intValue() ^ 0x0F0F0F;
        }
        if (defaultValue instanceof Boolean b) return !b;
        if ("waypointOutlineOpacity".equals(setting.id())) return 0.5;
        if (defaultValue instanceof Double d) return d + 2.0;
        if (defaultValue instanceof List<?>) return List.of("ProbePlayer");
        throw new AssertionError(setting.id() + " has unprobeable default " + defaultValue);
    }

    private static Set<String> persistedFieldNames(Class<?> type, Set<String> exempt) {
        Set<String> out = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
            if (exempt.contains(field.getName())) continue;
            out.add(field.getName());
        }
        return out;
    }

    private static Set<String> idsByStore(Setting.Store store) {
        Set<String> out = new TreeSet<>();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() == store) out.add(setting.id());
        }
        return out;
    }

    private static String categoryContaining(String id) {
        return SettingsCatalog.categories().stream()
                .filter(category -> category.groups().stream()
                        .flatMap(group -> group.settings().stream())
                        .anyMatch(setting -> setting.id().equals(id)))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private static String groupContaining(String id) {
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            for (SettingsCatalog.Group group : category.groups()) {
                if (group.settings().stream().anyMatch(setting -> setting.id().equals(id))) {
                    return group.id();
                }
            }
        }
        throw new IllegalArgumentException("Unknown setting " + id);
    }
}
