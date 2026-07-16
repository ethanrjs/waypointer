package dev.ethan.waypointer.config;

import dev.ethan.waypointer.codec.AsciiStreamCodec;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerConfigTest {

    @Test
    void defaultReachRadiusIsAlwaysFiniteAndBounded() {
        WaypointerConfig config = new WaypointerConfig();

        config.setDefaultReachRadius(Double.POSITIVE_INFINITY);
        assertEquals(Waypoint.DEFAULT_REACH_RADIUS, config.defaultReachRadius());

        config.setDefaultReachRadius(1_000_000.0);
        assertEquals(Waypoint.MAX_REACH_RADIUS, config.defaultReachRadius());
    }

    @Test
    void labelHeightOffsetDefaultsToHistoricalPlacement() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(0.0, config.labelHeightOffset());
    }

    @Test
    void labelHeightOffsetAcceptsLargeFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();

        config.setLabelHeightOffset(4.25);
        assertEquals(4.25, config.labelHeightOffset());

        config.setLabelHeightOffset(-99.0);
        assertEquals(-99.0, config.labelHeightOffset());

        config.setLabelHeightOffset(1_000.0);
        assertEquals(1_000.0, config.labelHeightOffset());
    }

    @Test
    void labelHeightOffsetRejectsNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setLabelHeightOffset(42.0);

        config.setLabelHeightOffset(Double.NaN);
        assertEquals(42.0, config.labelHeightOffset());

        config.setLabelHeightOffset(Double.POSITIVE_INFINITY);
        assertEquals(42.0, config.labelHeightOffset());
    }

        @Test
    void labelScaleDefaultsToOneAndClampsSafeBounds() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(1.0, config.labelScale());

        config.setLabelScale(2.5);
        assertEquals(2.5, config.labelScale());

        config.setLabelScale(0.1);
        assertEquals(0.25, config.labelScale());

        config.setLabelScale(9.0);
        assertEquals(4.0, config.labelScale());
    }

        @Test
    void labelScaleRejectsNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setLabelScale(1.75);

        config.setLabelScale(Double.NaN);
        assertEquals(1.75, config.labelScale());

        config.setLabelScale(Double.POSITIVE_INFINITY);
        assertEquals(1.75, config.labelScale());
    }

    @Test
    void nullBoxStyleFallsBackToOutlined() {
        WaypointerConfig config = new WaypointerConfig();

        config.setBoxStyle(null);

        assertEquals(WaypointerConfig.BoxStyle.OUTLINED, config.boxStyle());
    }

    @Test
    void nullBeaconBeamModeFallsBackToOff() {
        WaypointerConfig config = new WaypointerConfig();

        config.setBeaconBeamMode(null);

        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
    }

    @Test
    void visualCustomizationDefaultsPreserveCurrentRendering() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
        assertFalse(config.beaconBeamExtendsBelowWaypoint());
        assertTrue(config.showWaypointDistances());
        // Labels are the most expensive render feature (see the perf stress
        // test), so the default budgets them to the 32 nearest.
        assertEquals(32, config.maxWaypointLabels());
        assertEquals(0.0, config.maxStaticWaypointRenderDistance());
    }

    @Test
    void visualCustomizationTogglesCanBeChanged() {
        WaypointerConfig config = new WaypointerConfig();

        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        config.setBeaconBeamExtendsBelowWaypoint(true);
        config.setShowWaypointDistances(false);

        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, config.beaconBeamMode());
        assertTrue(config.beaconBeamExtendsBelowWaypoint());
        assertFalse(config.showWaypointDistances());
    }

    @Test
    void beaconTextureSettingDefaultsDisablesAndResets() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.useBeaconBeamTextures());

        config.setUseBeaconBeamTextures(false);
        assertFalse(config.useBeaconBeamTextures());

        config.setUseBeaconBeamTextures(true);
        config.disableAllSettings();
        assertFalse(config.useBeaconBeamTextures());

        config.resetToDefaults();
        assertTrue(config.useBeaconBeamTextures());
    }

    @Test
    void contributorBadgesDefaultOnAndResetWithOtherToggles() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.showContributorBadges());

        config.disableAllSettings();
        assertFalse(config.showContributorBadges());

        config.resetToDefaults();
        assertTrue(config.showContributorBadges());
    }

    @Test
    void performanceBudgetSettersClampNegativesToDisabled() {
        WaypointerConfig config = new WaypointerConfig();

        config.setMaxWaypointLabels(75);
        assertEquals(75, config.maxWaypointLabels());

        config.setMaxWaypointLabels(-1);
        assertEquals(0, config.maxWaypointLabels());

        config.setMaxStaticWaypointRenderDistance(128.5);
        assertEquals(128.5, config.maxStaticWaypointRenderDistance());

        config.setMaxStaticWaypointRenderDistance(-20.0);
        assertEquals(0.0, config.maxStaticWaypointRenderDistance());
    }

    @Test
    void performanceDistanceBudgetRejectsNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setMaxStaticWaypointRenderDistance(250.0);

        config.setMaxStaticWaypointRenderDistance(Double.NaN);
        assertEquals(250.0, config.maxStaticWaypointRenderDistance());

        config.setMaxStaticWaypointRenderDistance(Double.POSITIVE_INFINITY);
        assertEquals(250.0, config.maxStaticWaypointRenderDistance());
    }

    @Test
    void tempWaypointDefaultsStayInsideSupportedModes() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.focusTempWaypoints());
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
        assertTrue(config.tempWaypointsExpireByDefault());
        assertEquals(1, config.tempDefaultDurationMin());

        config.setFocusTempWaypoints(true);
        assertTrue(config.focusTempWaypoints());

        config.setTempDefaultMode(1);
        assertEquals(1, config.tempDefaultMode());

        config.setTempDefaultMode(3);
        assertEquals(3, config.tempDefaultMode());

        config.setTempDefaultMode(0);
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());

        config.setTempDefaultMode(4);
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
    }

    @Test
    void tempWaypointExpiryToggleMapsToTimeOrLeaveDefault() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTempWaypointsExpireByDefault(false);
        assertFalse(config.tempWaypointsExpireByDefault());
        assertEquals(Waypoint.TEMP_UNTIL_LEAVE, config.tempDefaultMode());
        assertEquals(0L, config.defaultTempExpiresAtMillis(1_000L));

        config.setTempWaypointsExpireByDefault(true);
        assertTrue(config.tempWaypointsExpireByDefault());
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
        assertEquals(61_000L, config.defaultTempExpiresAtMillis(1_000L));
    }

    @Test
    void oldImplicitTempDefaultsMigrateToIssue31Defaults() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "autoAddChatTempWaypoints": true,
                  "tempDefaultMode": 2,
                  "tempDefaultDurationMin": 10
                }
                """);

        assertFalse(config.autoAddChatTempWaypoints());
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
        assertEquals(1, config.tempDefaultDurationMin());
    }

    @Test
    void oldConfigWithAutoAddAlreadyOffStillMigratesDurationDefault() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "autoAddChatTempWaypoints": false,
                  "tempDefaultMode": 2,
                  "tempDefaultDurationMin": 10
                }
                """);

        assertFalse(config.autoAddChatTempWaypoints());
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
        assertEquals(1, config.tempDefaultDurationMin());
    }

    @Test
    void currentSchemaKeepsExplicitTenMinuteTempDuration() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 2,
                  "autoAddChatTempWaypoints": true,
                  "tempDefaultMode": 1,
                  "tempDefaultDurationMin": 10
                }
                """);

        assertTrue(config.autoAddChatTempWaypoints());
        assertEquals(Waypoint.TEMP_TIME, config.tempDefaultMode());
        assertEquals(10, config.tempDefaultDurationMin());
    }

    @Test
    void tempWaypointDurationClampsToOneMinuteThroughOneDay() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTempDefaultDurationMin(30);
        assertEquals(30, config.tempDefaultDurationMin());

        config.setTempDefaultDurationMin(-1);
        assertEquals(1, config.tempDefaultDurationMin());

        config.setTempDefaultDurationMin(9_999);
        assertEquals(24 * 60, config.tempDefaultDurationMin());
    }
    @Test
    void tempWaypointDurationSupportsSecondGranularity() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTempDefaultDurationSec(45);
        assertEquals(45, config.tempDefaultDurationSec());
        assertEquals(46_000L, config.defaultTempExpiresAtMillis(1_000L));

        config.setTempDefaultDurationSec(0);
        assertEquals(1, config.tempDefaultDurationSec());

        config.setTempDefaultDurationSec(999_999);
        assertEquals(24 * 60 * 60, config.tempDefaultDurationSec());

        config.setTempDefaultDurationMin(2);
        assertEquals(120, config.tempDefaultDurationSec());
        assertEquals(2, config.tempDefaultDurationMin());
    }

    @Test
    void colorSettersMaskAlphaChannel() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTracerColor(0xAA112233);

        assertEquals(0x112233, config.tracerColor());
    }

    @Test
    void defaultWaypointColorDefaultsMasksResetsAndLoadsFromJson() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(Waypoint.DEFAULT_COLOR, config.defaultWaypointColor());

        config.setDefaultWaypointColor(0xAA112233);
        assertEquals(0x112233, config.defaultWaypointColor());

        config.resetToDefaults();
        assertEquals(Waypoint.DEFAULT_COLOR, config.defaultWaypointColor());

        WaypointerConfig loaded = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 3,
                  "defaultWaypointColor": 1193046
                }
                """);
        assertEquals(0x123456, loaded.defaultWaypointColor());
    }

    @Test
    void opacitySettingsClampToUnitInterval() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTracerOpacity(-1.0);
        config.setBeaconOpacity(2.0);

        assertEquals(0.0, config.tracerOpacity());
        assertEquals(1.0, config.beaconOpacity());
    }

    @Test
    void disableAllStopsRenderingAndTheDungeonSubsystem() {
        WaypointerConfig config = new WaypointerConfig();
        DungeonConfig dungeonConfig = new DungeonConfig();

        config.disableAllSettings(dungeonConfig);

        assertEquals(0.0, config.beaconOpacity());
        assertFalse(config.showRouteLines());
        assertFalse(config.showDungeonEntryPathToFirstWaypoint());
        assertFalse(dungeonConfig.enabled());
        assertFalse(dungeonConfig.hideCompletedRooms());
        assertFalse(dungeonConfig.autoCompleteRoomsOnGreenCheckmark());
    }

    @Test
    void tracerThicknessDefaultsToHistoricalWidthAndClampsToSafeRange() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(3.0, config.tracerThickness());

        config.setTracerThickness(6.5);
        assertEquals(6.5, config.tracerThickness());

        config.setTracerThickness(0.0);
        assertEquals(1.0, config.tracerThickness());

        config.setTracerThickness(99.0);
        assertEquals(12.0, config.tracerThickness());
    }

    @Test
    void waypointOutlineThicknessDefaultsToHistoricalWidthAndClampsToSafeRange() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(3.0, config.waypointOutlineThickness());

        config.setWaypointOutlineThickness(5.5);
        assertEquals(5.5, config.waypointOutlineThickness());

        config.setWaypointOutlineThickness(0.0);
        assertEquals(1.0, config.waypointOutlineThickness());

        config.setWaypointOutlineThickness(99.0);
        assertEquals(12.0, config.waypointOutlineThickness());
    }

    @Test
    void waypointOutlineThicknessRejectsNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointOutlineThickness(4.0);

        config.setWaypointOutlineThickness(Double.NaN);
        assertEquals(4.0, config.waypointOutlineThickness());

        config.setWaypointOutlineThickness(Double.POSITIVE_INFINITY);
        assertEquals(4.0, config.waypointOutlineThickness());
    }

    @Test
    void tracerThicknessRejectsNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setTracerThickness(4.0);

        config.setTracerThickness(Double.NaN);
        assertEquals(4.0, config.tracerThickness());

        config.setTracerThickness(Double.POSITIVE_INFINITY);
        assertEquals(4.0, config.tracerThickness());
    }

    @Test
    void defaultExportAndDetectionTogglesStayUserFriendly() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.chatCoordDetection());
        assertFalse(config.autoAddChatTempWaypoints());
        assertTrue(config.placeNewWaypointsBelowPlayer());
        assertTrue(config.chatCodecDetection());
        assertTrue(config.dimSequenceContextWaypoints());
        assertTrue(config.exportIncludeNames());
        assertTrue(config.exportIncludeColors());
        assertTrue(config.exportIncludeRadii());
        assertTrue(config.exportIncludeWaypointFlags());
        assertTrue(config.exportIncludeGroupMeta());
    }

    @Test
    void nearHideDefaultsOffWithFiveBlockRadiusAndClamps() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.hideWaypointsNearPlayer());
        assertEquals(5.0, config.hideWaypointsNearRadius());

        config.setHideWaypointsNearPlayer(true);
        config.setHideWaypointsNearRadius(12.5);
        assertTrue(config.hideWaypointsNearPlayer());
        assertEquals(12.5, config.hideWaypointsNearRadius());

        config.setHideWaypointsNearRadius(0.0);
        assertEquals(0.5, config.hideWaypointsNearRadius());

        config.setHideWaypointsNearRadius(Double.NaN);
        assertEquals(0.5, config.hideWaypointsNearRadius());
    }

        @Test
    void labelNearHideDefaultsOffWithFiveBlockRadiusAndClamps() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.hideWaypointLabelsNearPlayer());
        assertEquals(5.0, config.hideWaypointLabelsNearRadius());

        config.setHideWaypointLabelsNearPlayer(true);
        config.setHideWaypointLabelsNearRadius(9.5);
        assertTrue(config.hideWaypointLabelsNearPlayer());
        assertEquals(9.5, config.hideWaypointLabelsNearRadius());

        config.setHideWaypointLabelsNearRadius(0.0);
        assertEquals(0.5, config.hideWaypointLabelsNearRadius());

        config.setHideWaypointLabelsNearRadius(Double.NaN);
        assertEquals(0.5, config.hideWaypointLabelsNearRadius());
    }

        @Test
    void importedRouteColorDefaultsToStaticGreen() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(WaypointGroup.GradientMode.STATIC, config.importedRouteColorMode());
        assertEquals(0x00FF00, config.importedRouteDefaultColor());
    }

        @Test
    void importedRouteColorSettingsNormalizeAndDisableConsistently() {
        WaypointerConfig config = new WaypointerConfig();

        config.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        assertEquals(WaypointGroup.GradientMode.AUTO, config.importedRouteColorMode());

        config.setImportedRouteColorMode(null);
        assertEquals(WaypointGroup.GradientMode.STATIC, config.importedRouteColorMode());

        config.setImportedRouteDefaultColor(0xAA112233);
        assertEquals(0x112233, config.importedRouteDefaultColor());

        config.disableAllSettings();
        assertEquals(WaypointGroup.GradientMode.MANUAL, config.importedRouteColorMode());
        assertEquals(0x112233, config.importedRouteDefaultColor());
    }

    @Test
    void routeNavigationDefaultsCoverVisibleSkipAndConnectorLines() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.skipAheadOnlyVisibleWaypoints());
        assertFalse(config.showRouteLines());
        assertFalse(config.showDungeonEntryPathToFirstWaypoint());
        assertFalse(config.showDungeonEntryPathToFollowingWaypoints());
        assertEquals(0x00FF00, config.dungeonEntryPathColor());
        assertEquals(0x00FF00, config.routeLineColor());

        config.setSkipAheadOnlyVisibleWaypoints(false);
        assertFalse(config.skipAheadOnlyVisibleWaypoints());

        config.setShowRouteLines(true);
        assertTrue(config.showRouteLines());

        config.setShowDungeonEntryPathToFirstWaypoint(true);
        assertTrue(config.showDungeonEntryPathToFirstWaypoint());

        config.setShowDungeonEntryPathToFollowingWaypoints(true);
        assertTrue(config.showDungeonEntryPathToFollowingWaypoints());

        config.setDungeonEntryPathColor(0xCC123456);
        assertEquals(0x123456, config.dungeonEntryPathColor());

        config.setRouteLineColor(0xAA445566);
        assertEquals(0x445566, config.routeLineColor());

        config.disableAllSettings();
        assertFalse(config.skipAheadOnlyVisibleWaypoints());
        assertFalse(config.showRouteLines());
        assertFalse(config.showDungeonEntryPathToFirstWaypoint());
        assertFalse(config.showDungeonEntryPathToFollowingWaypoints());
    }

    @Test
    void configCodecRoundTripsRepresentativeSettings() {
        WaypointerConfig config = new WaypointerConfig();
        config.setDefaultReachRadius(7.5);
        config.setRestartRouteWhenComplete(false);
        config.setDefaultWaypointColor(0x123456);
        config.setTracerColor(0xABCDEF);
        config.setMatchTracerToWaypointColor(false);
        config.setLabelScale(2.25);
        config.setHideWaypointLabelsNearPlayer(true);
        config.setHideWaypointLabelsNearRadius(9.5);
        config.setSkipAheadOnlyVisibleWaypoints(false);
        config.setShowRouteLines(true);
        config.setShowDungeonEntryPathToFirstWaypoint(true);
        config.setShowDungeonEntryPathToFollowingWaypoints(true);
        config.setDungeonEntryPathColor(0x0A0B0C);
        config.setRouteLineColor(0x010203);
        config.setSharpWaypointEdges(true);
        config.setUseBeaconBeamTextures(false);
        config.setEditSounds(false);
        config.setShowEditModeSubtitle(false);
        config.setShowContributorBadges(false);
        config.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
        config.addChatCoordSenderBlacklist("Babbur");
        config.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        config.setImportedRouteDefaultColor(0x445566);
        config.setExportIncludeColors(true);
        config.setExportIncludeRadii(true);
        config.setTempDefaultDurationMin(44);

        String code = WaypointerConfigCodec.encode(config);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(code);

        assertTrue(code.startsWith(WaypointerConfigCodec.MAGIC));
        assertEquals(7.5, decoded.defaultReachRadius());
        assertFalse(decoded.restartRouteWhenComplete());
        assertEquals(0x123456, decoded.defaultWaypointColor());
        assertEquals(0xABCDEF, decoded.tracerColor());
        assertFalse(decoded.matchTracerToWaypointColor());
        assertEquals(2.25, decoded.labelScale());
        assertTrue(decoded.hideWaypointLabelsNearPlayer());
        assertEquals(9.5, decoded.hideWaypointLabelsNearRadius());
        assertFalse(decoded.skipAheadOnlyVisibleWaypoints());
        assertTrue(decoded.showRouteLines());
        assertTrue(decoded.showDungeonEntryPathToFirstWaypoint());
        assertTrue(decoded.showDungeonEntryPathToFollowingWaypoints());
        assertEquals(0x0A0B0C, decoded.dungeonEntryPathColor());
        assertEquals(0x010203, decoded.routeLineColor());
        assertTrue(decoded.sharpWaypointEdges());
        assertFalse(decoded.useBeaconBeamTextures());
        assertFalse(decoded.editSounds());
        assertFalse(decoded.showEditModeSubtitle());
        assertFalse(decoded.showContributorBadges());
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED, decoded.boxStyle());
        assertEquals(List.of("Babbur"), decoded.chatCoordSenderBlacklist());
        assertEquals(WaypointGroup.GradientMode.AUTO, decoded.importedRouteColorMode());
        assertEquals(0x445566, decoded.importedRouteDefaultColor());
        assertTrue(decoded.exportIncludeColors());
        assertTrue(decoded.exportIncludeRadii());
        assertEquals(44, decoded.tempDefaultDurationMin());
    }
    @Test
    void configCodecPreservesSecondGranularityForTempDuration() {
        WaypointerConfig config = new WaypointerConfig();
        config.setTempDefaultDurationSec(75);

        WaypointerConfig decoded = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(config));

        assertEquals(75, decoded.tempDefaultDurationSec());
        assertEquals(76_000L, decoded.defaultTempExpiresAtMillis(1_000L));
    }

    @Test
    void configCodecConsumesRetiredUpdaterTagWithoutRestoringUpdaterState() throws IOException {
        String legacyCode = configCodeForRawPayload((byte) 1, (byte) 51, (byte) 0, (byte) 0);

        WaypointerConfig decoded = WaypointerConfigCodec.decode(legacyCode);

        assertTrue(decoded.showWaypointNames());
    }

    @Test
    void configCodecRejectsBadPrefixVersionAndBody() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointerConfigCodec.decode("WP:abc"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointerConfigCodec.decode("WPC:"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointerConfigCodec.decode("WPC:not-valid-body"));

        String badVersion = configCodeForRawPayload((byte) 99, (byte) 0);
        assertThrows(IllegalArgumentException.class,
                () -> WaypointerConfigCodec.decode(badVersion));
    }

    @Test
    void configCodeReplacementResetsOmittedFieldsToDefaults() {
        WaypointerConfig live = new WaypointerConfig();
        live.setDefaultWaypointColor(0x101112);
        live.setShowRouteLines(true);
        live.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        live.addChatCoordSenderBlacklist("Babbur");

        WaypointerConfig replacement = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(new WaypointerConfig()));
        live.replaceWith(replacement);

        assertEquals(Waypoint.DEFAULT_COLOR, live.defaultWaypointColor());
        assertFalse(live.showRouteLines());
        assertEquals(WaypointGroup.GradientMode.STATIC, live.importedRouteColorMode());
        assertTrue(live.chatCoordSenderBlacklist().isEmpty());
    }

    @Test
    void chatCoordSenderBlacklistTogglesCaseInsensitively() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.addChatCoordSenderBlacklist("Babbur"));
        assertFalse(config.addChatCoordSenderBlacklist("babbur"));
        assertTrue(config.isChatCoordSenderBlacklisted("BABBUR"));
        assertEquals(List.of("Babbur"), config.chatCoordSenderBlacklist());

        assertFalse(config.toggleChatCoordSenderBlacklist("babbur"));
        assertFalse(config.isChatCoordSenderBlacklisted("Babbur"));

        assertTrue(config.toggleChatCoordSenderBlacklist("Babbur"));
        assertTrue(config.isChatCoordSenderBlacklisted("babbur"));
    }

    @Test
    void playerWaypointPlacementTargetsSupportingBlockByDefault() {
        WaypointerConfig config = new WaypointerConfig();

        PlayerWaypointPlacement.BlockPosition fullBlock = PlayerWaypointPlacement.fromPlayer(
                10.9, 65.0, -3.1, config);
        assertEquals(new PlayerWaypointPlacement.BlockPosition(10, 64, -4), fullBlock);

        PlayerWaypointPlacement.BlockPosition partialBlock = PlayerWaypointPlacement.fromPlayer(
                10.9, 64.5, -3.1, config);
        assertEquals(new PlayerWaypointPlacement.BlockPosition(10, 64, -4), partialBlock);
    }

    @Test
    void playerWaypointPlacementCanUseExactFootBlock() {
        WaypointerConfig config = new WaypointerConfig();

        config.setPlaceNewWaypointsBelowPlayer(false);
        PlayerWaypointPlacement.BlockPosition standing = PlayerWaypointPlacement.fromPlayer(
                10.9, 65.0, -3.1, config);
        assertEquals(new PlayerWaypointPlacement.BlockPosition(10, 65, -4), standing);
    }

    @Test
    void waypointTextColorMatchingDefaultsOnButCanBeDisabled() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.matchWaypointTextToWaypointColor());

        config.setMatchWaypointTextToWaypointColor(false);

        assertFalse(config.matchWaypointTextToWaypointColor());
    }

    @Test
    void dungeonFeatureFlagDefaultsOff() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(false, config.dungeonWaypointsFeatureEnabled());
    }

    @Test
    void irisHudFallbackDefaultsOffButCanBeEnabled() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.irisShaderHudFallback());

        config.setIrisShaderHudFallback(true);

        assertTrue(config.irisShaderHudFallback());
    }

    private static String configCodeForRawPayload(byte... raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try (DeflaterOutputStream deflaterOut = new DeflaterOutputStream(out, deflater)) {
            deflaterOut.write(raw);
        } finally {
            deflater.end();
        }
        return WaypointerConfigCodec.MAGIC + AsciiStreamCodec.encode(out.toByteArray());
    }
}
