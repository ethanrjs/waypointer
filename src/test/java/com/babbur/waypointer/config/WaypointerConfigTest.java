package com.babbur.waypointer.config;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.core.SequenceVisibility;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerConfigTest {

    @Test
    void corruptConfigIsQuarantinedBeforeDefaultsCanBeSaved(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("config.json");
        String corrupt = "{ definitely not valid JSON";
        Files.writeString(file, corrupt);

        WaypointerConfig config = WaypointerConfig.load(file);

        Path quarantine = dir.resolve("config.json.invalid");
        assertFalse(Files.exists(file));
        assertEquals(corrupt, Files.readString(quarantine));

        config.setShowTracer(false);
        config.flush();

        assertTrue(Files.exists(file));
        assertFalse(WaypointerConfig.load(file).showTracer());
        assertEquals(corrupt, Files.readString(quarantine));
    }

    @Test
    void futureSchemaIsRejectedBeforeDeserialization() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WaypointerConfig.fromJson(
                        "{\"configSchemaVersion\":8,\"unknownFutureSetting\":{}}"));

        assertTrue(failure.getMessage().contains("schema version 8"));
        assertTrue(failure.getMessage().contains("supported version 7"));
    }

    @Test
    void futureSchemaConfigIsPreservedAndAllWritesStayBlocked(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("config.json");
        String future = "{\n  \"configSchemaVersion\": 8,\n"
                + "  \"unknownFutureSetting\": {\"sentinel\": true}\n}\n";
        byte[] futureBytes = future.getBytes(StandardCharsets.UTF_8);
        Files.write(file, futureBytes);

        WaypointerConfig config = WaypointerConfig.load(file);

        assertEquals(7, config.configSchemaVersion());
        assertTrue(config.showTracer());
        config.setShowTracer(false);
        config.save();
        config.flush();
        config.resetToDefaults();
        config.disableAllSettings();
        config.replaceShareableSettingsWith(new WaypointerConfig());
        config.flush();

        assertTrue(Files.exists(file));
        assertArrayEquals(futureBytes, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("config.json.invalid")));
    }

    @Test
    void legacyMigrationIsPersistedWithCurrentSchema(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"configSchemaVersion\":4,\"irisShaderHudFallback\":false}");

        WaypointerConfig config = WaypointerConfig.load(file);
        config.flush();

        String persisted = Files.readString(file);
        assertTrue(persisted.contains("\"configSchemaVersion\": 7"));
        assertTrue(persisted.contains("\"irisShaderHudFallback\": true"));
    }

    @Test
    void failedConfigWriteRemainsDirtyAndFlushCanRetry(@TempDir Path dir)
            throws IOException {
        Path blockedParent = dir.resolve("not-a-directory");
        Files.writeString(blockedParent, "block directory creation");
        Path file = blockedParent.resolve("config.json");
        WaypointerConfig config = WaypointerConfig.load(file);
        config.setShowTracer(false);

        assertThrows(UncheckedIOException.class, config::flush);

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        config.flush();

        assertFalse(WaypointerConfig.load(file).showTracer());
    }

    @Test
    void waypointPainterPalettePersistsAndDefensivelyCopies() {
        WaypointerConfig config = new WaypointerConfig();
        int[] palette = WaypointPaint.defaultPalette(0xAA123456);
        palette[5] = 0xCCABCDEF;

        config.setWaypointPainterPalette(palette);
        palette[5] = 0;
        int[] stored = config.waypointPainterPalette();
        assertEquals(0xABCDEF, stored[5]);
        stored[5] = 0;
        assertEquals(0xABCDEF, config.waypointPainterPalette()[5]);

        WaypointerConfig restarted = WaypointerConfig.fromJson(
                "{\"waypointPainterPalette\":"
                        + Arrays.toString(config.waypointPainterPalette()) + "}");
        assertArrayEquals(config.waypointPainterPalette(), restarted.waypointPainterPalette());

        restarted.resetToDefaults();
        assertArrayEquals(WaypointPaint.defaultPalette(Waypoint.DEFAULT_COLOR),
                restarted.waypointPainterPalette());
    }

    @Test
    void applyAllDefaultPaintSurvivesConfigReloadForFutureRoutes() {
        int[] palette = WaypointPaint.defaultPalette(0x123456);
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.UP, 2, 3)] = 5;
        WaypointPaint paint = new WaypointPaint(palette, pixels);

        WaypointerConfig restarted = WaypointerConfig.fromJson(
                "{\"waypointPainterDefaultPalette\":" + Arrays.toString(palette)
                        + ",\"waypointPainterDefaultPixels\":\"" + paint.pixelsBase64() + "\"}");

        assertEquals(paint, restarted.waypointPainterDefaultPaint());
        restarted.resetToDefaults();
        assertNull(restarted.waypointPainterDefaultPaint());
    }

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
    void freshConfigDefaultsToFilledOutlinedBoxes() {
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED, new WaypointerConfig().boxStyle());
        assertEquals(3, WaypointerConfig.BoxStyle.PAINT.ordinal(),
                "Paint must stay appended because config codes serialize enum ordinals");
    }

    @Test
    void nullBoxStyleFallsBackToTheDefault() {
        WaypointerConfig config = new WaypointerConfig();

        config.setBoxStyle(null);

        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED, config.boxStyle());
    }

    @Test
    void savedOutlinedBoxStyleSurvivesTheDefaultChange() {
        WaypointerConfig config = WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":5,\"boxStyle\":\"OUTLINED\"}");

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
        assertTrue(config.showLabelTextShadow());
        assertEquals(32, config.maxWaypointLabels());
        assertEquals(0.0, config.maxStaticWaypointRenderDistance());
    }

    @Test
    void visualCustomizationTogglesCanBeChanged() {
        WaypointerConfig config = new WaypointerConfig();

        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        config.setBeaconBeamExtendsBelowWaypoint(true);
        config.setShowWaypointDistances(false);
        config.setShowLabelTextShadow(false);

        assertEquals(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE, config.beaconBeamMode());
        assertTrue(config.beaconBeamExtendsBelowWaypoint());
        assertFalse(config.showWaypointDistances());
        assertFalse(config.showLabelTextShadow());
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
    void waypointChatShareButtonsDefaultOnAndResetWithOtherToggles() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.showWaypointChatShareButtons());

        config.disableAllSettings();
        assertFalse(config.showWaypointChatShareButtons());

        config.resetToDefaults();
        assertTrue(config.showWaypointChatShareButtons());
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
        config.setWaypointOutlineColor(0xBB445566);
        config.setSequencePreviousWaypointColor(0xCC778899);
        config.setSequenceCurrentWaypointColor(0xDDAABBCC);
        config.setSequenceNextWaypointColor(0xEE102030);

        assertEquals(0x112233, config.tracerColor());
        assertEquals(0x445566, config.waypointOutlineColor());
        assertEquals(0x778899, config.sequencePreviousWaypointColor());
        assertEquals(0xAABBCC, config.sequenceCurrentWaypointColor());
        assertEquals(0x102030, config.sequenceNextWaypointColor());
    }

    @Test
    void outlineColorPreservesWaypointColorsByDefaultAndSupportsAFlatOverride() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.matchWaypointOutlineToWaypointColor());
        assertEquals(0x123456, config.resolvedWaypointOutlineColor(0x123456));

        config.setMatchWaypointOutlineToWaypointColor(false);
        config.setWaypointOutlineColor(0xABCDEF);
        assertEquals(0xABCDEF, config.resolvedWaypointOutlineColor(0x123456));
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
    void opacitySettingsRejectNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();
        config.setTracerOpacity(0.7);
        config.setBeaconOpacity(0.6);

        config.setTracerOpacity(Double.NaN);
        config.setBeaconOpacity(Double.POSITIVE_INFINITY);

        assertEquals(0.7, config.tracerOpacity());
        assertEquals(0.6, config.beaconOpacity());
    }

    @Test
    void waypointOpacityDefaultsToHalfWithoutOverridingSavedValues() {
        assertEquals(0.33, WaypointerConfig.fromJson("{}").beaconOpacity());
        assertEquals(0.8, WaypointerConfig.fromJson("{\"beaconOpacity\":0.8}").beaconOpacity());
    }

    @Test
    void disableAllStopsRenderingAndTheDungeonSubsystem() {
        WaypointerConfig config = new WaypointerConfig();
        DungeonConfig dungeonConfig = new DungeonConfig();

        config.disableAllSettings(dungeonConfig);

        assertEquals(0.0, config.beaconOpacity());
        assertFalse(config.showRouteLines());
        assertFalse(config.useEtherwarpHeight());
        assertFalse(config.showDungeonEntryPathToFirstWaypoint());
        assertFalse(dungeonConfig.enabled());
        assertFalse(dungeonConfig.hideCompletedRooms());
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
    void waypointOutlineThicknessDefaultsToMarkerWidthAndClampsToSafeRange() {
        WaypointerConfig config = new WaypointerConfig();

        assertEquals(5.0, config.waypointOutlineThickness());

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
    void waypointAppearanceSettingsClampAndRejectNonFiniteValues() {
        WaypointerConfig config = new WaypointerConfig();

        config.setWaypointMarkerScale(2.25);
        config.setWaypointOutlineOpacity(0.4);
        assertEquals(2.25, config.waypointMarkerScale());
        assertEquals(0.4, config.waypointOutlineOpacity());

        config.setWaypointMarkerScale(99.0);
        config.setWaypointOutlineOpacity(-1.0);
        assertEquals(3.0, config.waypointMarkerScale());
        assertEquals(0.0, config.waypointOutlineOpacity());

        config.setWaypointMarkerScale(Double.NaN);
        config.setWaypointOutlineOpacity(Double.POSITIVE_INFINITY);
        assertEquals(3.0, config.waypointMarkerScale());
        assertEquals(0.0, config.waypointOutlineOpacity());
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
        assertFalse(config.exportIncludeNames());
        assertFalse(config.exportIncludeColors());
        assertFalse(config.exportIncludeRadii());
        assertFalse(config.exportIncludeWaypointFlags());
        assertFalse(config.exportIncludeGroupMeta());
        assertFalse(config.exportIncludeZone());
    }

    @Test
    void persistedExportPreferencesRemainAuthoritativeAfterDefaultChange() {
        WaypointerConfig config = WaypointerConfig.fromJson("""
                {
                  "configSchemaVersion": 6,
                  "exportIncludeNames": true,
                  "exportIncludeColors": true,
                  "exportIncludeRadii": true,
                  "exportIncludeWaypointFlags": true,
                  "exportIncludeGroupMeta": true,
                  "exportIncludeZone": true
                }
                """);

        assertTrue(config.exportIncludeNames());
        assertTrue(config.exportIncludeColors());
        assertTrue(config.exportIncludeRadii());
        assertTrue(config.exportIncludeWaypointFlags());
        assertTrue(config.exportIncludeGroupMeta());
        assertTrue(config.exportIncludeZone());
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

        config.setUseEtherwarpHeight(true);
        assertTrue(config.useEtherwarpHeight());

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
        assertFalse(config.useEtherwarpHeight());
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
        config.setWaypointMarkerScale(1.75);
        config.setWaypointOutlineOpacity(0.35);
        config.setMatchWaypointOutlineToWaypointColor(false);
        config.setWaypointOutlineColor(0x654321);
        config.setMatchTracerToWaypointColor(false);
        config.setLabelScale(2.25);
        config.setHideWaypointLabelsNearPlayer(true);
        config.setHideWaypointLabelsNearRadius(9.5);
        config.setSkipAheadOnlyVisibleWaypoints(false);
        config.setShowRouteLines(true);
        config.setUseEtherwarpHeight(true);
        config.setShowDungeonEntryPathToFirstWaypoint(true);
        config.setShowDungeonEntryPathToFollowingWaypoints(true);
        config.setDungeonEntryPathColor(0x0A0B0C);
        config.setRouteLineColor(0x010203);
        config.setUseBeaconBeamTextures(false);
        config.setEditSounds(false);
        config.setShowEditModeSubtitle(false);
        config.setShowContributorBadges(false);
        config.setShowLabelTextShadow(false);
        config.setBoxStyle(WaypointerConfig.BoxStyle.PAINT);
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
        assertEquals(1.75, decoded.waypointMarkerScale());
        assertEquals(0.35, decoded.waypointOutlineOpacity());
        assertFalse(decoded.matchWaypointOutlineToWaypointColor());
        assertEquals(0x654321, decoded.waypointOutlineColor());
        assertFalse(decoded.matchTracerToWaypointColor());
        assertEquals(2.25, decoded.labelScale());
        assertTrue(decoded.hideWaypointLabelsNearPlayer());
        assertEquals(9.5, decoded.hideWaypointLabelsNearRadius());
        assertFalse(decoded.skipAheadOnlyVisibleWaypoints());
        assertTrue(decoded.showRouteLines());
        assertTrue(decoded.useEtherwarpHeight());
        assertTrue(decoded.showDungeonEntryPathToFirstWaypoint());
        assertTrue(decoded.showDungeonEntryPathToFollowingWaypoints());
        assertEquals(0x0A0B0C, decoded.dungeonEntryPathColor());
        assertEquals(0x010203, decoded.routeLineColor());
        assertFalse(decoded.useBeaconBeamTextures());
        assertFalse(decoded.editSounds());
        assertFalse(decoded.showEditModeSubtitle());
        assertFalse(decoded.showContributorBadges());
        assertFalse(decoded.showLabelTextShadow());
        assertEquals(WaypointerConfig.BoxStyle.PAINT, decoded.boxStyle());
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
    void configCodecUsesVersionSixAndStillReadsVersionTwo() throws IOException {
        assertEquals(6, WaypointerConfigCodec.VERSION);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(2);
            out.writeByte(72);
            out.writeDouble(1.75);
            out.writeByte(0);
        }
        String versionTwoCode = configCodeForRawPayload(raw.toByteArray());

        assertEquals(1.75, WaypointerConfigCodec.decode(versionTwoCode).waypointMarkerScale());
    }

    @Test
    void legacyConfigCodesKeepHistoricalExportDefaultsWhenFieldsAreOmitted() throws IOException {
        for (int version = 1; version <= 4; version++) {
            WaypointerConfig decoded = WaypointerConfigCodec.decode(
                    configCodeForRawPayload((byte) version, (byte) 0));

            assertTrue(decoded.exportIncludeNames(), "WPC v" + version + " names");
            assertTrue(decoded.exportIncludeColors(), "WPC v" + version + " colors");
            assertTrue(decoded.exportIncludeRadii(), "WPC v" + version + " radii");
            assertTrue(decoded.exportIncludeWaypointFlags(), "WPC v" + version + " flags");
            assertTrue(decoded.exportIncludeGroupMeta(), "WPC v" + version + " group metadata");
            assertTrue(decoded.exportIncludeZone(), "WPC v" + version + " zone");
        }

        WaypointerConfig explicit = WaypointerConfigCodec.decode(configCodeForRawPayload(
                (byte) 4,
                (byte) 44, (byte) 0,
                (byte) 45, (byte) 0,
                (byte) 46, (byte) 0,
                (byte) 47, (byte) 0,
                (byte) 48, (byte) 0,
                (byte) 69, (byte) 0,
                (byte) 0));

        assertFalse(explicit.exportIncludeNames());
        assertFalse(explicit.exportIncludeColors());
        assertFalse(explicit.exportIncludeRadii());
        assertFalse(explicit.exportIncludeWaypointFlags());
        assertFalse(explicit.exportIncludeGroupMeta());
        assertFalse(explicit.exportIncludeZone());
    }

    @Test
    void versionFiveConfigCodesUseBareExportDefaultsAndHonorExplicitFields() throws IOException {
        WaypointerConfig omitted = WaypointerConfigCodec.decode(
                configCodeForRawPayload((byte) 5, (byte) 0));

        assertFalse(omitted.exportIncludeNames());
        assertFalse(omitted.exportIncludeColors());
        assertFalse(omitted.exportIncludeRadii());
        assertFalse(omitted.exportIncludeWaypointFlags());
        assertFalse(omitted.exportIncludeGroupMeta());
        assertFalse(omitted.exportIncludeZone());

        WaypointerConfig explicit = WaypointerConfigCodec.decode(configCodeForRawPayload(
                (byte) 5,
                (byte) 44, (byte) 1,
                (byte) 45, (byte) 1,
                (byte) 46, (byte) 1,
                (byte) 47, (byte) 1,
                (byte) 48, (byte) 1,
                (byte) 69, (byte) 1,
                (byte) 0));

        assertTrue(explicit.exportIncludeNames());
        assertTrue(explicit.exportIncludeColors());
        assertTrue(explicit.exportIncludeRadii());
        assertTrue(explicit.exportIncludeWaypointFlags());
        assertTrue(explicit.exportIncludeGroupMeta());
        assertTrue(explicit.exportIncludeZone());
    }

    @Test
    void legacyConfigCodeWithoutOpacityKeepsHistoricalDefault() throws IOException {
        String legacyCode = configCodeForRawPayload((byte) 1, (byte) 0);

        assertEquals(0.8, WaypointerConfigCodec.decode(legacyCode).beaconOpacity());
        assertEquals(0.33, WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(new WaypointerConfig())).beaconOpacity());
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
        live.setUseEtherwarpHeight(true);
        live.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        live.addChatCoordSenderBlacklist("Babbur");
        live.setWaypointMarkerScale(2.5);
        live.setWaypointOutlineOpacity(0.25);
        live.setMatchWaypointOutlineToWaypointColor(false);
        live.setWaypointOutlineColor(0x123456);

        WaypointerConfig replacement = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(new WaypointerConfig()));
        live.replaceShareableSettingsWith(replacement);

        assertEquals(Waypoint.DEFAULT_COLOR, live.defaultWaypointColor());
        assertFalse(live.showRouteLines());
        assertFalse(live.useEtherwarpHeight());
        assertEquals(WaypointGroup.GradientMode.STATIC, live.importedRouteColorMode());
        assertTrue(live.chatCoordSenderBlacklist().isEmpty());
        assertEquals(1.0, live.waypointMarkerScale());
        assertEquals(1.0, live.waypointOutlineOpacity());
        assertTrue(live.matchWaypointOutlineToWaypointColor());
        assertEquals(Waypoint.DEFAULT_COLOR, live.waypointOutlineColor());
    }

    @Test
    void shareableConfigReplacementPreservesLocalPainterState() {
        WaypointerConfig live = new WaypointerConfig();
        int[] palette = WaypointPaint.defaultPalette(0x123456);
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[0] = 4;
        WaypointPaint paint = new WaypointPaint(palette, pixels);
        live.setWaypointPainterPalette(palette);
        live.setWaypointPainterDefaultPaint(paint);

        live.replaceShareableSettingsWith(new WaypointerConfig());

        assertArrayEquals(palette, live.waypointPainterPalette());
        assertEquals(paint, live.waypointPainterDefaultPaint());
    }

    @Test
    void resetToDefaultsRestoresWaypointAppearanceSettings() {
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointMarkerScale(2.5);
        config.setWaypointOutlineOpacity(0.25);
        config.setMatchWaypointOutlineToWaypointColor(false);
        config.setWaypointOutlineColor(0x123456);

        config.resetToDefaults();

        assertEquals(1.0, config.waypointMarkerScale());
        assertEquals(1.0, config.waypointOutlineOpacity());
        assertTrue(config.matchWaypointOutlineToWaypointColor());
        assertEquals(Waypoint.DEFAULT_COLOR, config.waypointOutlineColor());
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
    void irisHudFallbackDefaultsOnButCanBeDisabled() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.irisShaderHudFallback());

        config.setIrisShaderHudFallback(false);

        assertFalse(config.irisShaderHudFallback());
    }

    @Test
    void schemaFiveEnablesIrisFallbackOnceWithoutOverridingNewOptOuts() {
        assertTrue(WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":4,\"irisShaderHudFallback\":false}")
                .irisShaderHudFallback());
        assertFalse(WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":5,\"irisShaderHudFallback\":false}")
                .irisShaderHudFallback());
    }

    @Test
    void legacyVisibilityMigratesWithoutChangingExistingRouteContext() {
        WaypointerConfig hiddenPrevious = WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":5,\"showCompleted\":false}");
        WaypointerConfig shownPrevious = WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":5,\"showCompleted\":true}");

        assertEquals(0, hiddenPrevious.sequencePreviousWaypointCount());
        assertTrue(hiddenPrevious.showCurrentSequenceWaypoint());
        assertEquals(1, hiddenPrevious.sequenceNextWaypointCount());
        assertEquals(1, shownPrevious.sequencePreviousWaypointCount());
        assertEquals(1, shownPrevious.sequenceNextWaypointCount());
        assertEquals(1, shownPrevious.sequenceVisibility().previousLimit(80));
        assertEquals(1, shownPrevious.sequenceVisibility().nextLimit(80));
    }

    @Test
    void completedCompatibilityToggleRestoresOnePreviousStep() {
        WaypointerConfig config = new WaypointerConfig();
        assertEquals(1, config.sequencePreviousWaypointCount());
        assertEquals(1, config.sequenceNextWaypointCount());

        config.setShowCompleted(false);
        assertEquals(0, config.sequencePreviousWaypointCount());

        config.setShowCompleted(true);
        assertEquals(1, config.sequencePreviousWaypointCount());

        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config));
        assertEquals(1, decoded.sequencePreviousWaypointCount());
        assertEquals(1, decoded.sequenceNextWaypointCount());
    }

    @Test
    void sequenceVisibilityClampsAndRoundTripsThroughConfigCode() {
        WaypointerConfig config = new WaypointerConfig();
        config.setSequencePreviousWaypointCount(99);
        config.setShowCurrentSequenceWaypoint(false);
        config.setSequenceNextWaypointCount(-4);

        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config));

        assertEquals(32, decoded.sequencePreviousWaypointCount());
        assertFalse(decoded.showCurrentSequenceWaypoint());
        assertEquals(0, decoded.sequenceNextWaypointCount());
    }

    @Test
    void etherwarpAlignmentPreferencePersistsDisablesResetsAndReplaces() {
        WaypointerConfig config = new WaypointerConfig();
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF,
                config.etherwarpAlignmentSound());

        config.setEtherwarpAlignmentSound(WaypointerConfig.EtherwarpAlignmentSound.BELL);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config));
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.BELL,
                decoded.etherwarpAlignmentSound());

        config.resetToDefaults();
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF,
                config.etherwarpAlignmentSound());
        config.disableAllSettings();
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF,
                config.etherwarpAlignmentSound());

        config.setEtherwarpAlignmentSound(WaypointerConfig.EtherwarpAlignmentSound.PLING);
        config.replaceShareableSettingsWith(new WaypointerConfig());
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF,
                config.etherwarpAlignmentSound());
    }

    @Test
    void legacyEtherwarpAlignmentSoundMigrationsPreserveTheExperienceCue() {
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF, WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":5}").etherwarpAlignmentSound());
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE, WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":6,\"etherwarpAlignmentSound\":true}")
                .etherwarpAlignmentSound());
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.OFF, WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":6,\"etherwarpAlignmentSound\":false}")
                .etherwarpAlignmentSound());
        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.PLING, WaypointerConfig.fromJson(
                "{\"configSchemaVersion\":7,\"etherwarpAlignmentSoundType\":\"PLING\"}")
                .etherwarpAlignmentSound());
    }

    @Test
    void legacyConfigCodeBooleanEtherwarpCueDecodesAsExperience() throws IOException {
        WaypointerConfig decoded = WaypointerConfigCodec.decode(configCodeForRawPayload(
                (byte) 4, (byte) 79, (byte) 1, (byte) 0));

        assertEquals(WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE,
                decoded.etherwarpAlignmentSound());
    }

    @Test
    void crystalHollowsDefaultsDisableResetAndRoundTrip() {
        WaypointerConfig config = new WaypointerConfig();
        assertTrue(config.crystalHollowsEnabled());
        assertTrue(config.crystalHollowsStructureWaypoints());
        assertTrue(config.crystalHollowsShowRoughMarkers());
        assertTrue(config.crystalHollowsEntityDetection());
        assertTrue(config.crystalHollowsChatDetection());
        assertTrue(config.crystalHollowsWishingCompassSolver());
        assertTrue(config.crystalHollowsCompassRays());
        assertTrue(config.crystalHollowsAnnounceDetections());
        assertFalse(config.crystalHollowsNucleusWaypoints());

        config.setCrystalHollowsNucleusWaypoints(true);
        WaypointerConfig decoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config));
        assertTrue(decoded.crystalHollowsNucleusWaypoints());

        config.disableAllSettings();
        assertFalse(config.crystalHollowsEnabled());
        assertFalse(config.crystalHollowsCompassRays());
        config.resetToDefaults();
        assertTrue(config.crystalHollowsEnabled());
        assertTrue(config.crystalHollowsCompassRays());
        assertFalse(config.crystalHollowsNucleusWaypoints());
    }

    @Test
    void crystalHollowsFieldsUseDistinctTagsAndRoundTripThroughV10() throws IOException {
        WaypointerConfig config = new WaypointerConfig();
        config.setCrystalHollowsEnabled(false);
        config.setCrystalHollowsStructureWaypoints(false);
        config.setCrystalHollowsShowRoughMarkers(false);
        config.setCrystalHollowsEntityDetection(false);
        config.setCrystalHollowsChatDetection(false);
        config.setCrystalHollowsWishingCompassSolver(false);
        config.setCrystalHollowsCompassRays(false);
        config.setCrystalHollowsAnnounceDetections(false);
        config.setCrystalHollowsNucleusWaypoints(true);

        List<Integer> crystalTags = WaypointerConfigCodec.encodeTaggedFields(config).stream()
                .map(WaypointerConfigCodec.TaggedField::tag)
                .filter(tag -> tag >= 81)
                .toList();
        assertEquals(List.of(81, 82, 83, 84, 85, 86, 87, 88, 89), crystalTags);

        WaypointerConfig decoded = V10ConfigBodyCodec.decode(V10ConfigBodyCodec.encode(config));
        assertEquals(WaypointerConfigCodec.encode(config), WaypointerConfigCodec.encode(decoded));
    }

    @Test
    void backwardProgressionUsesTagNinetyAndFollowsTheFullConfigLifecycle()
            throws IOException {
        WaypointerConfig config = new WaypointerConfig();
        assertFalse(config.allowBackwardProgress());

        config.setAllowBackwardProgress(true);
        assertEquals(List.of(90), WaypointerConfigCodec.encodeTaggedFields(config).stream()
                .map(WaypointerConfigCodec.TaggedField::tag)
                .filter(tag -> tag >= 90)
                .toList());
        assertTrue(WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config)).allowBackwardProgress());
        assertTrue(V10ConfigBodyCodec.decode(
                V10ConfigBodyCodec.encode(config)).allowBackwardProgress());

        WaypointerConfig replacement = new WaypointerConfig();
        replacement.replaceShareableSettingsWith(config);
        assertTrue(replacement.allowBackwardProgress());
        replacement.disableAllSettings();
        assertFalse(replacement.allowBackwardProgress());
        replacement.setAllowBackwardProgress(true);
        replacement.resetToDefaults();
        assertFalse(replacement.allowBackwardProgress());
    }

    @Test
    void sequenceRoleColorsUseTagsNinetyOneThroughNinetyFourAndFollowConfigLifecycle()
            throws IOException {
        WaypointerConfig config = new WaypointerConfig();
        assertFalse(config.colorSequenceWaypointsByRole());

        config.setColorSequenceWaypointsByRole(true);
        config.setSequencePreviousWaypointColor(0x112233);
        config.setSequenceCurrentWaypointColor(0x445566);
        config.setSequenceNextWaypointColor(0x778899);

        assertEquals(List.of(91, 92, 93, 94),
                WaypointerConfigCodec.encodeTaggedFields(config).stream()
                        .map(WaypointerConfigCodec.TaggedField::tag)
                        .filter(tag -> tag >= 91)
                        .toList());
        WaypointerConfig legacyDecoded = WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(config));
        WaypointerConfig v10Decoded = V10ConfigBodyCodec.decode(
                V10ConfigBodyCodec.encode(config));
        for (WaypointerConfig decoded : List.of(legacyDecoded, v10Decoded)) {
            assertTrue(decoded.colorSequenceWaypointsByRole());
            assertEquals(0x112233, decoded.sequencePreviousWaypointColor());
            assertEquals(0x445566, decoded.sequenceCurrentWaypointColor());
            assertEquals(0x778899, decoded.sequenceNextWaypointColor());
        }

        WaypointerConfig replacement = new WaypointerConfig();
        replacement.replaceShareableSettingsWith(config);
        assertTrue(replacement.colorSequenceWaypointsByRole());
        replacement.disableAllSettings();
        assertFalse(replacement.colorSequenceWaypointsByRole());
        replacement.setColorSequenceWaypointsByRole(true);
        replacement.resetToDefaults();
        assertFalse(replacement.colorSequenceWaypointsByRole());
        assertEquals(0x808080, replacement.sequencePreviousWaypointColor());
        assertEquals(Waypoint.DEFAULT_COLOR, replacement.sequenceCurrentWaypointColor());
        assertEquals(0x00BFFF, replacement.sequenceNextWaypointColor());
    }

    @Test
    void configCodecConsumesLegacyRouteTimesField() throws IOException {
        WaypointerConfig decoded = WaypointerConfigCodec.decode(configCodeForRawPayload(
                (byte) 3, (byte) 66, (byte) 1, (byte) 67, (byte) 1, (byte) 0));

        assertTrue(decoded.showRouteIndicesInGui());
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
