package dev.ethan.waypointer.config;

import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.diana.DianaRareMob;
import dev.ethan.waypointer.diana.DianaWarp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerConfigTest {

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
        assertEquals(0, config.maxWaypointLabels());
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
    void performanceBudgetsDefaultToUnlimitedAndClampToDisabled() {
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
                  "configSchemaVersion": 6,
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
    void oldDianaSubsettingsMigrateBackToSimpleDefaults() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 2,
                  "dianaShowStartBurrows": false,
                  "dianaShowMobBurrows": false,
                  "dianaShowTreasureBurrows": false,
                  "dianaSpadeEstimateWaypoints": false,
                  "dianaWarpPrompt": false,
                  "dianaEstimateWaypointName": "Skyhook",
                  "dianaEstimateMinSamples": 24,
                  "dianaEstimateStabilityRadius": 16.0
                }
                """);

        assertTrue(config.dianaShowStartBurrows());
        assertTrue(config.dianaShowMobBurrows());
        assertTrue(config.dianaShowTreasureBurrows());
        assertTrue(config.dianaSpadeEstimateWaypoints());
        assertTrue(config.dianaWarpPrompt());
        assertEquals("Burrow", config.dianaEstimateWaypointName());
        assertEquals(8, config.dianaEstimateMinSamples());
        assertEquals(4.5, config.dianaEstimateStabilityRadius());
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
    void colorSettersMaskAlphaChannel() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTracerColor(0xAA112233);

        assertEquals(0x112233, config.tracerColor());
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
        assertTrue(config.dianaBurrowWaypoints());
        assertTrue(config.dianaShowStartBurrows());
        assertTrue(config.dianaShowMobBurrows());
        assertTrue(config.dianaShowTreasureBurrows());
        assertTrue(config.dianaSpadeEstimateWaypoints());
        assertEquals("Burrow", config.dianaEstimateWaypointName());
        assertEquals(0x4FE05A, config.dianaEstimateWaypointColor());
        assertEquals(0x4FE05A, config.dianaStartBurrowColor());
        assertEquals(0xFF4040, config.dianaMobBurrowColor());
        assertEquals(0xFFB02E, config.dianaTreasureBurrowColor());
        assertEquals(8, config.dianaEstimateMinSamples());
        assertEquals(4.5, config.dianaEstimateStabilityRadius());
        assertTrue(config.dianaWarpAssist());
        assertTrue(config.dianaWarpPrompt());
        assertEquals(45.0, config.dianaWarpMinSavings());
        assertTrue(config.dianaWarpEnabled(DianaWarp.HUB));
        assertTrue(config.dianaWarpEnabled(DianaWarp.CASTLE));
        assertTrue(config.dianaWarpEnabled(DianaWarp.MUSEUM));
        assertTrue(config.dianaWarpEnabled(DianaWarp.WIZARD));
        assertTrue(config.dianaWarpEnabled(DianaWarp.STONKS));
        assertFalse(config.dianaWarpEnabled(DianaWarp.DA));
        assertFalse(config.dianaWarpEnabled(DianaWarp.CRYPT));
        assertEquals(5, config.dianaEnabledWarpCount());
        assertTrue(config.dianaHideStartBurrowsUntilChainComplete());
        assertFalse(config.dianaSpadeDebugLogging());
        assertTrue(config.dianaRareMobWaypoints());
        assertTrue(config.dianaRareMobPartySharing());
        assertTrue(config.dianaRareMobShareEnabled(DianaRareMob.MINOS_INQUISITOR));
        assertFalse(config.dianaRareMobShareEnabled(DianaRareMob.SIAMESE_LYNX));
        assertEquals(1, config.dianaRareMobShareEnabledCount());
        assertTrue(config.placeNewWaypointsBelowPlayer());
        assertFalse(config.deleteTempWaypointsWhenReached());
        assertTrue(config.chatCodecDetection());
        assertTrue(config.dimSequenceContextWaypoints());
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
    void tempWaypointDeleteWhenReachedCanBeEnabled() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.deleteTempWaypointsWhenReached());

        config.setDeleteTempWaypointsWhenReached(true);

        assertTrue(config.deleteTempWaypointsWhenReached());
    }

    @Test
    void dianaAppearanceAndEstimateTuningClampToUsefulBounds() {
        WaypointerConfig config = new WaypointerConfig();

        config.setDianaEstimateWaypointName("  &2Pretty Burrow  ");
        config.setDianaEstimateWaypointColor(0xFF1E5E32);
        config.setDianaEstimateMinSamples(2);
        config.setDianaEstimateStabilityRadius(0.1);

        assertEquals("&2Pretty Burrow", config.dianaEstimateWaypointName());
        assertEquals(0xFF1E5E32, config.dianaEstimateWaypointColor());
        assertEquals(4, config.dianaEstimateMinSamples());
        assertEquals(0.5, config.dianaEstimateStabilityRadius());

        config.setDianaEstimateWaypointName("");
        config.setDianaEstimateMinSamples(99);
        config.setDianaEstimateStabilityRadius(99.0);

        assertEquals("Burrow", config.dianaEstimateWaypointName());
        assertEquals(24, config.dianaEstimateMinSamples());
        assertEquals(16.0, config.dianaEstimateStabilityRadius());
    }

    @Test
    void oldDianaEstimateColorMigratesToBrightGreen() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 3,
                  "dianaEstimateWaypointColor": 1990194
                }
                """);

        assertEquals(0x4FE05A, config.dianaEstimateWaypointColor());
    }

    @Test
    void oldDianaWarpSavingsThresholdMigratesToFortyFiveBlocks() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 4,
                  "dianaWarpMinSavings": 30.0
                }
                """);

        assertEquals(45.0, config.dianaWarpMinSavings());
    }

    @Test
    void dianaWarpSettingsCanBeTunedAndClampToUsefulBounds() {
        WaypointerConfig config = new WaypointerConfig();

        config.setDianaWarpAssist(false);
        config.setDianaWarpPrompt(false);
        config.setDianaWarpMinSavings(-10.0);
        config.setDianaWarpEnabled(DianaWarp.DA, true);
        config.setDianaWarpEnabled(DianaWarp.HUB, false);

        assertFalse(config.dianaWarpAssist());
        assertFalse(config.dianaWarpPrompt());
        assertEquals(0.0, config.dianaWarpMinSavings());
        assertTrue(config.dianaWarpEnabled(DianaWarp.DA));
        assertFalse(config.dianaWarpEnabled(DianaWarp.HUB));
        assertEquals(5, config.dianaEnabledWarpCount());

        config.setDianaWarpMinSavings(500.0);
        assertEquals(300.0, config.dianaWarpMinSavings());

        config.setDianaWarpMinSavings(42.0);
        config.setDianaWarpMinSavings(Double.NaN);
        assertEquals(42.0, config.dianaWarpMinSavings());
    }

    @Test
    void dianaSpadeDebugLoggingDefaultsOffButCanBeEnabled() {
        WaypointerConfig config = new WaypointerConfig();

        assertFalse(config.dianaSpadeDebugLogging());

        config.setDianaSpadeDebugLogging(true);

        assertTrue(config.dianaSpadeDebugLogging());
    }

    @Test
    void dianaRareMobPartySharingCanSelectMobs() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.dianaRareMobPartySharing());
        assertFalse(config.dianaRareMobShareEnabled(DianaRareMob.SIAMESE_LYNX));

        config.setDianaRareMobPartySharing(false);
        config.setDianaRareMobShareEnabled(DianaRareMob.SIAMESE_LYNX, true);

        assertFalse(config.dianaRareMobPartySharing());
        assertTrue(config.dianaRareMobShareEnabled(DianaRareMob.SIAMESE_LYNX));
        assertEquals(2, config.dianaRareMobShareEnabledCount());
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

    @Test
    void disableAllFeaturesTurnsOffFeatureToggles() {
        WaypointerConfig config = new WaypointerConfig();
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        config.setDianaRareMobWaypoints(true);

        config.disableAllFeatures();

        assertFalse(config.showWaypointNames());
        assertFalse(config.showWaypointDistances());
        assertFalse(config.showTracer());
        assertFalse(config.chatCoordDetection());
        assertFalse(config.chatCodecDetection());
        assertFalse(config.deleteTempWaypointsWhenReached());
        assertFalse(config.dianaBurrowWaypoints());
        assertFalse(config.dianaWarpAssist());
        assertFalse(config.dianaSpadeDebugLogging());
        for (DianaWarp warp : DianaWarp.values()) {
            assertFalse(config.dianaWarpEnabled(warp));
        }
        assertFalse(config.dianaHideStartBurrowsUntilChainComplete());
        assertFalse(config.dianaRareMobWaypoints());
        assertFalse(config.dianaRareMobPartySharing());
        assertFalse(config.checkForUpdates());
        assertEquals(WaypointerConfig.BeaconBeamMode.OFF, config.beaconBeamMode());
    }
}
