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
            "namedStyles", "preferredStyleId", "crystalHollowsStyles", "zoneStyles",
            "beaconMotionSpeed", "beaconWidthScale", "completedWaypointOpacity", "currentWaypointOpacity", "labelBackdropOpacity", "labelDetailDistance", "labelDetailOnDemand", "labelShowCoordinates", "labelShowRouteName", "preventLabelOverlap", "routeLineDashed", "routeLineOpacity", "routeLineWidth", "sequenceContextOpacity", "showWaypointRoleBadge", "tracerCenterGap", "tracerEdgeArrow", "upcomingWaypointOpacity", "waypointIcon",
            "crystalHollowsCompassRays",
            "minimapScale", "minimapCustomPosition", "minimapPositionX", "minimapPositionY",
            "crystalHollowsDetections",
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
        catalogIds.removeIf(id -> id.startsWith("crystalHollowsDetections."));
        assertEquals(fields, catalogIds,
                "WaypointerConfig fields and catalog MAIN ids must match exactly; "
                        + "catalog new fields or add them to MAIN_EXEMPT deliberately");
    }

    @Test
    void exportPreviewRemainsAnOptionalExtra() {
        Setting toggle = SettingsCatalog.byId("enableFeatureBloat");
        assertEquals("system", categoryContaining(toggle.id()));
        assertEquals("Enable Extras", toggle.label());
        assertEquals("Enable the 3D route preview.", toggle.tooltip());
        WaypointerConfig config = new WaypointerConfig();
        Setting preview = SettingsCatalog.byId("showExportRoutePreview");
        assertEquals("feature_bloat", categoryContaining(preview.id()));
        assertFalse(preview.isEnabled(config, null));
        toggle.set(config, null, true);
        assertTrue(preview.isEnabled(config, null));
    }

    @Test
    void antialiasingIsAnEnabledAppearanceToggle() {
        Setting setting = SettingsCatalog.byId("renderAntialiasing");
        assertEquals(Setting.Kind.BOOL, setting.kind());
        assertEquals("appearance", categoryContaining(setting.id()));
        WaypointerConfig config = new WaypointerConfig();
        assertEquals(true, setting.get(config, null));
        setting.set(config, null, false);
        assertFalse(config.renderAntialiasing());
    }

    @Test
    void miningCategoryGroupsCrystalHollowsSettingsUnderItsRealMasterSwitch() {
        SettingsCatalog.Category category = SettingsCatalog.categories().stream()
                .filter(candidate -> candidate.id().equals("mining"))
                .findFirst().orElseThrow();

        assertEquals("crystalHollowsEnabled", category.masterSettingId());
        assertEquals(List.of("crystal_hollows_structures", "detections", "minimap", "wishing_compass"),
                category.groups().stream().map(SettingsCatalog.Group::id).toList());
        assertEquals(33, category.groups().stream()
                .flatMap(group -> group.settings().stream()).count());
        assertTrue(category.groups().stream()
                .flatMap(group -> group.settings().stream())
                .allMatch(setting -> setting.store() == Setting.Store.MAIN
                        || setting.id().equals(SettingsCatalog.ACTION_MINIMAP_EDITOR)));
        Setting master = SettingsCatalog.byId("crystalHollowsEnabled");
        assertNotNull(master);
        assertTrue(master.aliases().containsAll(
                List.of("mining", "compass", "hollows", "divan", "temple")));
        WaypointerConfig config = new WaypointerConfig();
        assertTrue(category.bodyVisibleWhen().test(config, null));
        Setting sharing = SettingsCatalog.byId("crystalHollowsRemoteSharing");
        assertNotNull(sharing);
        assertEquals("mining", categoryContaining(sharing.id()));
        assertEquals(true, sharing.get(config, null));
        sharing.set(config, null, false);
        assertFalse(config.crystalHollowsRemoteSharing());
        Setting tracers = SettingsCatalog.byId("crystalHollowsTracers");
        assertNotNull(tracers);
        assertEquals(true, tracers.get(config, null));
        tracers.set(config, null, false);
        assertFalse(config.crystalHollowsTracers());
        Setting compassTracers = SettingsCatalog.byId("crystalHollowsCompassTracers");
        assertEquals(Setting.Kind.BOOL, compassTracers.kind());
        assertEquals("mining", categoryContaining(compassTracers.id()));
        assertTrue(config.crystalHollowsCompassTracers());
        compassTracers.set(config, null, false);
        assertFalse(config.crystalHollowsCompassTracers());
        assertTrue(config.crystalHollowsCompassAutoFocus());
        Setting autoFocus = SettingsCatalog.byId("crystalHollowsCompassAutoFocus");
        assertNotNull(autoFocus);
        assertEquals(true, autoFocus.get(config, null));
        autoFocus.set(config, null, false);
        assertFalse(config.crystalHollowsCompassAutoFocus());
        Setting hideStructures = SettingsCatalog.byId("crystalHollowsHideStructuresFolder");
        assertNotNull(hideStructures);
        assertEquals("Hide Structures folder", hideStructures.label());
        assertEquals("Hide the Structures folder and its generated waypoints.",
                hideStructures.tooltip());
        assertEquals(false, hideStructures.get(config, null));
        hideStructures.set(config, null, true);
        assertEquals(true, hideStructures.get(config, null));
        config.setCrystalHollowsEnabled(false);
        assertFalse(category.bodyVisibleWhen().test(config, null));
    }

    @Test
    void automaticDetectionControlsAreCompleteAndLocalOnly() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig config = new WaypointerConfig();
        List<Setting> detections = SettingsCatalog.allSettings().stream()
                .filter(setting -> setting.id().startsWith("crystalHollowsDetections.")).toList();
        assertEquals(12, detections.size());
        for (Setting setting : detections) {
            assertEquals("detections", groupContaining(setting.id()));
            assertTrue(setting.isLocalOnly());
            assertEquals(true, setting.get(config, null));
            setting.set(config, null, false);
            assertFalse(config.crystalHollowsDetectionEnabled(
                    setting.id().substring("crystalHollowsDetections.".length())));
        }
        assertEquals(12, SettingsCatalog.countChangedSettings(defaults, config));
        assertEquals(0, SettingsCatalog.countImportedSettings(defaults, config));
        assertEquals(Setting.Kind.ACTION, SettingsCatalog.byId(SettingsCatalog.ACTION_MINIMAP_EDITOR).kind());
        assertEquals("minimap", groupContaining(SettingsCatalog.ACTION_MINIMAP_EDITOR));
    }

    @Test
    void minimapControlsUseTheirConfigValues() {
        WaypointerConfig config = new WaypointerConfig();
        Setting corner = SettingsCatalog.byId("crystalHollowsMinimapCorner");
        assertEquals(Setting.Kind.ENUM, corner.kind());
        assertEquals(List.of(0, 1, 2, 3), corner.enumOptions().stream()
                .map(Setting.EnumOption::value).toList());
        corner.set(config, null, 3);
        assertEquals(3, config.crystalHollowsMinimapCorner());
        Setting size = SettingsCatalog.byId("crystalHollowsMinimapSize");
        size.set(config, null, 200.0);
        assertEquals(200, config.crystalHollowsMinimapSize());
    }

    @Test
    void categoryOrderIncludesFunctionalMiningWithoutAnEmptyForagingPage() {
        assertEquals(List.of("waypoints", "appearance", "routes", "dungeons", "mining",
                        "chat", "sharing", "feature_bloat", "system", "feedback"),
                SettingsCatalog.categories().stream()
                        .map(SettingsCatalog.Category::id)
                        .toList());
    }

    @Test
    void feedbackActionIsAvailableWithoutExtrasEnabled() {
        Setting feedback = SettingsCatalog.byId(SettingsCatalog.ACTION_FEEDBACK);
        assertNotNull(feedback);
        assertEquals(Setting.Kind.ACTION, feedback.kind());
        assertEquals("feedback", categoryContaining(feedback.id()));
        assertTrue(feedback.isEnabled(new WaypointerConfig(), null));
    }

    @Test
    void skyblockerSharingDefaultsOnAndRequiresMasterSharing() {
        WaypointerConfig config = new WaypointerConfig();
        Setting sharing = SettingsCatalog.byId("crystalHollowsSkyblockerSharing");
        assertNotNull(sharing);
        assertEquals("Share Crystal Hollows structures through the Skyblocker websocket. Skyblocker data can be inaccurate.\n\n"
                + "API.AzureAaron.net receives your Minecraft UUID and profile key proof to authenticate.", sharing.tooltip());
        assertEquals("mining", categoryContaining(sharing.id()));
        assertEquals(true, sharing.get(config, null));
        sharing.set(config, null, false);
        assertFalse(config.crystalHollowsSkyblockerSharing());
        assertTrue(sharing.isLocalOnly());
        assertEquals(1, SettingsCatalog.countChangedSettings(new WaypointerConfig(), config));
        assertEquals(0, SettingsCatalog.countImportedSettings(new WaypointerConfig(), config));
        config.setCrystalHollowsRemoteSharing(false);
        assertFalse(sharing.isEnabled(config, null));
        config.setCrystalHollowsRemoteSharing(true);
        config.setCrystalHollowsEnabled(false);
        assertFalse(sharing.isEnabled(config, null));
        config.setCrystalHollowsEnabled(true);
        assertTrue(sharing.isEnabled(config, null));
    }

    @Test
    void sequenceRoleColorsAreOptInAppearanceSettingsUnderSequenced() {
        WaypointerConfig config = new WaypointerConfig();
        SettingsCatalog.Category appearance = SettingsCatalog.categories().stream()
                .filter(category -> category.id().equals("appearance"))
                .findFirst().orElseThrow();
        SettingsCatalog.Group sequenced = appearance.groups().stream()
                .filter(group -> "sequenced".equals(group.id()))
                .findFirst().orElseThrow();

        assertTrue(sequenced.settings().stream()
                .map(Setting::id)
                .toList()
                .containsAll(List.of("sequencePreviousWaypointCount",
                        "showCurrentSequenceWaypoint", "sequenceNextWaypointCount",
                        "dimSequenceContextWaypoints", "colorSequenceWaypointsByRole",
                        "sequencePreviousWaypointColor", "sequenceCurrentWaypointColor",
                        "sequenceNextWaypointColor")));
        Setting toggle = SettingsCatalog.byId("colorSequenceWaypointsByRole");
        assertEquals(false, toggle.get(config, null));
        for (String id : List.of("sequencePreviousWaypointColor",
                "sequenceCurrentWaypointColor", "sequenceNextWaypointColor")) {
            Setting color = SettingsCatalog.byId(id);
            assertNotNull(color);
            assertFalse(color.isEnabled(config, null));
            config.setColorSequenceWaypointsByRole(true);
            assertTrue(color.isEnabled(config, null));
            config.setColorSequenceWaypointsByRole(false);
        }
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
    void everyShareableMainEntrySurvivesTheCodecRoundTrip() {
        WaypointerConfig defaults = new WaypointerConfig();
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN || setting.isLocalOnly()) continue;

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
    void probingEveryShareableMainEntryCountsThemAllAndRoundTripsToZeroDiff() {
        WaypointerConfig defaults = new WaypointerConfig();
        WaypointerConfig live = new WaypointerConfig();
        int mainEntries = 0;
        for (Setting setting : SettingsCatalog.allSettings()) {
            if (setting.store() != Setting.Store.MAIN || setting.isLocalOnly()) continue;
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
    void appearanceUsesInlineControlsAndPainterRemainsAvailable() {
        assertNotNull(SettingsCatalog.byId("boxStyle"));
        assertNull(SettingsCatalog.byId("action.waypointInspector"));
        assertNull(SettingsCatalog.byId("action.routeStyles"));
        assertNull(SettingsCatalog.byId("action.structureStyles"));
        assertNull(SettingsCatalog.byId("waypointIcon"));
        assertTrue(SettingsCatalog.byId("action.waypointPaint").isEnabled(new WaypointerConfig(), null));
        assertEquals("appearance", categoryContaining("action.waypointPaint"));
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
                        "renderAntialiasing",
                        "boxStyle",
                        SettingsCatalog.ACTION_WAYPOINT_PAINT,
                        "waypointMarkerScale",
                        "defaultWaypointColor",
                        "beaconOpacity",
                        "matchWaypointOutlineToWaypointColor",
                        "waypointOutlineColor",
                        "waypointOutlineOpacity",
                        "waypointOutlineThickness",
                        "lineOfSightOnly"),
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
        assertRange("labelOpacity", 0.0, 1.0, false);
        assertRange("maxWaypointLabels", 0.0, Integer.MAX_VALUE, true);
        assertRange("hideWaypointLabelsNearRadius", 0.5, 100.0, false);
        assertRange("tracerOpacity", 0.0, 1.0, false);
        assertRange("tracerThickness", 1.0, 12.0, false);
        assertRange("defaultReachRadius", 0.5, 100.0, false);
        assertRange("editModePlacementReach", 1.0, 64.0, false);
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
    void backwardProgressionIsAnOptInLowImpactRouteSetting() {
        Setting setting = SettingsCatalog.byId("allowBackwardProgress");

        assertNotNull(setting);
        assertEquals("Allow stepping back", setting.label());
        assertEquals("Reaching an earlier waypoint moves the route back to it",
                setting.tooltip());
        assertEquals(false, setting.get(new WaypointerConfig(), null));
        assertEquals(Setting.Impact.LOW, setting.impact());
        assertEquals("routes", categoryContaining(setting.id()));
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
        assertEquals("Pathfind to first waypoint", entryPath.label());
        assertEquals("", entryPath.tooltip());
        assertEquals("Pathfind to all waypoints",
                SettingsCatalog.byId("showDungeonEntryPathToFollowingWaypoints").label());
        assertEquals("", SettingsCatalog.byId("showDungeonEntryPathToFollowingWaypoints").tooltip());
        assertEquals(false, entryPath.get(new WaypointerConfig(), new DungeonConfig()));
    }

    @Test
    void etherwarpAlignmentSoundInputIsEmptyUnderEtherwarp() {
        Setting setting = SettingsCatalog.byId("etherwarpAlignmentSound");

        assertNotNull(setting);
        assertEquals("Etherwarp alignment sound", setting.label());
        assertEquals("Minecraft sound ID, e.g. block.note_block.pling. Leave blank to disable.",
                setting.tooltip());
        assertEquals(Setting.Kind.TEXT, setting.kind());
        assertTrue(setting.acceptsText("block.note_block.pling"));
        assertTrue(setting.acceptsText("custom:cue/ready"));
        assertTrue(setting.acceptsText(""));
        assertFalse(setting.acceptsText("not a sound"));
        assertEquals("",
                setting.get(new WaypointerConfig(), new DungeonConfig()));
        assertEquals(Setting.Store.MAIN, setting.store());
        assertEquals("appearance", categoryContaining(setting.id()));
        assertEquals("etherwarp", groupContaining(setting.id()));
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
            assertEquals("sequenced", groupContaining(id), id);
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
    void theExportRoutePreviewIsOffByDefaultUnderFeatureBloat() {
        Setting preview = SettingsCatalog.byId("showExportRoutePreview");

        assertNotNull(preview);
        assertEquals("3D route preview", preview.label());
        assertEquals("Show a rotating 3D preview of the route on the export screen.",
                preview.tooltip());
        assertEquals(false, preview.get(new WaypointerConfig(), null));
        assertEquals("feature_bloat", categoryContaining("showExportRoutePreview"));

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
        assertEquals("Dim prev/next waypoints",
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
                SettingsCatalog.ACTION_PRESETS)) {
            assertEquals("", SettingsCatalog.byId(id).tooltip(), id);
        }
        assertEquals("Create waypoints from chat messages",
                SettingsCatalog.byId("autoAddChatTempWaypoints").label());
        assertEquals("Chat share buttons",
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
        if (setting.kind() == Setting.Kind.TEXT) return "block.note_block.pling";
        if (defaultValue instanceof Boolean b) return !b;
        if ("waypointOutlineOpacity".equals(setting.id()) || "labelOpacity".equals(setting.id())) return 0.5;
        if (defaultValue instanceof Double d) {
            double candidate = Math.min(setting.maximum(), d + 2.0);
            return candidate != d ? candidate : Math.max(setting.minimum(), d - 0.5);
        }
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
