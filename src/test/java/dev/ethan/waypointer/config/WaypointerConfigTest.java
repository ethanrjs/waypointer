package dev.ethan.waypointer.config;

import org.junit.jupiter.api.Test;

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

        config.setFocusTempWaypoints(true);
        assertTrue(config.focusTempWaypoints());

        config.setTempDefaultMode(1);
        assertEquals(1, config.tempDefaultMode());

        config.setTempDefaultMode(3);
        assertEquals(3, config.tempDefaultMode());

        config.setTempDefaultMode(0);
        assertEquals(2, config.tempDefaultMode());

        config.setTempDefaultMode(4);
        assertEquals(2, config.tempDefaultMode());
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
    void defaultExportAndDetectionTogglesStayUserFriendly() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.chatCoordDetection());
        assertTrue(config.autoAddChatTempWaypoints());
        assertTrue(config.chatCodecDetection());
        assertTrue(config.dimSequenceContextWaypoints());
        assertFalse(config.exportIncludeColors());
        assertTrue(config.exportIncludeGroupMeta());
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
}
