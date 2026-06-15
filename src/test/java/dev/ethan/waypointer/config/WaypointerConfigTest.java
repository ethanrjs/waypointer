package dev.ethan.waypointer.config;

import dev.ethan.waypointer.codec.AsciiStreamCodec;
import dev.ethan.waypointer.placement.PlayerWaypointPlacement;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
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
    void colorSettersMaskAlphaChannel() {
        WaypointerConfig config = new WaypointerConfig();

        config.setTracerColor(0xAA112233);

        assertEquals(0x112233, config.tracerColor());
    }

    /*[[AI-FN-DOC
Function:
defaultWaypointColorDefaultsMasksResetsAndLoadsFromJson
Purpose:
Verify the new default waypoint color setting defaults correctly, stores only RGB bits, resets, and loads from persisted JSON.
Why this exists:
Default waypoint color now feeds future manual/temp waypoint creation, so it needs the same masking and default behavior as other color settings.
When to use:
Run with the config unit tests after changing WaypointerConfig color fields or JSON schema.
Inputs:
No parameters. Creates in-memory config instances and one JSON fixture.
Outputs:
No return value. Assertions fail if default, masking, reset, or JSON loading behavior regresses.
Side effects:
Mutates only local WaypointerConfig test instances.
Failure modes:
Fails if alpha bits persist, reset misses the field, or Gson loading does not populate the field.
Important invariants:
The stored color must always be a 24-bit RGB value and reset must restore Waypoint.DEFAULT_COLOR.
Internal logic:
Assert a fresh default, set an ARGB color and assert masked RGB, reset and assert default, then load a schema-3 JSON value and assert it persists.
Pseudocode:
config = new config
assert defaultWaypointColor equals Waypoint.DEFAULT_COLOR
set defaultWaypointColor to ARGB
assert low RGB bits
reset to defaults
assert Waypoint.DEFAULT_COLOR
loaded = fromJson with configSchemaVersion 3 and defaultWaypointColor
assert loaded color
Implementation notes:
JSON load covers persistence without needing to touch the filesystem or AsyncSaver.
AI self-check:
Confirm this test does not assert any imported-route color behavior.
]]*/
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
        assertFalse(config.exportIncludeColors());
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

    /*[[AI-FN-DOC
Function:
routeNavigationDefaultsCoverVisibleSkipAndConnectorLines
Purpose:
Verify the default and setter behavior for the new visible-only skip and route connector settings.
Why this exists:
These settings control new navigation behavior and render density, so defaults must preserve safe behavior while exposing customization.
When to use:
Run with the config test suite after changing WaypointerConfig progression or route display fields.
Inputs:
No parameters. Creates a fresh in-memory WaypointerConfig.
Outputs:
No return value. Assertions fail the test if defaults, masks, or disable behavior regress.
Side effects:
None outside the test object.
Failure modes:
Fails if visible-only skip is not default-on, route lines are not default-off, color masking breaks, or disableAllSettings leaves route lines enabled.
Important invariants:
Automatic skip should be visibility-limited by default; connector lines should be opt-in; connector color should stay 24-bit RGB.
Internal logic:
Assert defaults, toggle values, verify color masking, then disable all settings and assert the relevant booleans are off.
Pseudocode:
create config
assert skip visible only true
assert route lines false
assert route line color green
set skip visible only false and assert false
enable route lines and assert true
set ARGB route color and assert low RGB bits
disable all settings
assert skip visible only false and route lines false
Implementation notes:
This mirrors the imported-route color default test without requiring disk persistence.
AI self-check:
Confirm the test covers all new WaypointerConfig fields added for this feature request.
]]*/
    @Test
    void routeNavigationDefaultsCoverVisibleSkipAndConnectorLines() {
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(config.skipAheadOnlyVisibleWaypoints());
        assertFalse(config.showRouteLines());
        assertEquals(0x00FF00, config.routeLineColor());

        config.setSkipAheadOnlyVisibleWaypoints(false);
        assertFalse(config.skipAheadOnlyVisibleWaypoints());

        config.setShowRouteLines(true);
        assertTrue(config.showRouteLines());

        config.setRouteLineColor(0xAA445566);
        assertEquals(0x445566, config.routeLineColor());

        config.disableAllSettings();
        assertFalse(config.skipAheadOnlyVisibleWaypoints());
        assertFalse(config.showRouteLines());
    }

    /*[[AI-FN-DOC
Function:
configCodecRoundTripsRepresentativeSettings
Purpose:
Verify WPC: config codes preserve representative booleans, numbers, enums, colors, and lists.
Why this exists:
The compact config codec is a new public sharing surface and must round-trip the settings it claims to replace.
When to use:
Run with config tests after changing WaypointerConfigCodec tags or adding config fields.
Inputs:
No parameters. Creates a non-default in-memory config and decodes its encoded code.
Outputs:
No return value. Assertions fail if encoded values are missing or decoded incorrectly.
Side effects:
None outside local config instances.
Failure modes:
Fails if encoding omits a field, decode maps a tag incorrectly, or setters normalize differently than expected.
Important invariants:
The code starts with WPC: and decoded settings are applied over defaults, not over the source instance.
Internal logic:
Mutate a broad sample of config values, encode, decode, then assert every sampled setting survived.
Pseudocode:
config = defaults
set representative non-default fields
code = encode config
assert code prefix
decoded = decode code
assert every sampled getter equals source value
Implementation notes:
The sample includes the new default waypoint color plus import/export, route-line, label, temp, enum, and list settings.
AI self-check:
Verify at least one field from each major settings page is covered.
]]*/
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
        config.setRouteLineColor(0x010203);
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
        assertEquals(0x010203, decoded.routeLineColor());
        assertEquals(WaypointerConfig.BoxStyle.FILLED_OUTLINED, decoded.boxStyle());
        assertEquals(List.of("Babbur"), decoded.chatCoordSenderBlacklist());
        assertEquals(WaypointGroup.GradientMode.AUTO, decoded.importedRouteColorMode());
        assertEquals(0x445566, decoded.importedRouteDefaultColor());
        assertTrue(decoded.exportIncludeColors());
        assertTrue(decoded.exportIncludeRadii());
        assertEquals(44, decoded.tempDefaultDurationMin());
    }

    /*[[AI-FN-DOC
Function:
configCodecRejectsBadPrefixVersionAndBody
Purpose:
Verify malformed WPC: config codes are rejected before they can be applied.
Why this exists:
Config-code import replaces all settings, so decode failures must be explicit and safe.
When to use:
Run with config codec tests after changing prefix validation, compression, or version handling.
Inputs:
No parameters. Builds invalid string fixtures and one unsupported-version fixture.
Outputs:
No return value. Assertions fail if decode accepts malformed inputs.
Side effects:
Allocates a tiny unsupported-version payload through the test helper.
Failure modes:
Fails if the codec no longer rejects wrong prefixes, empty bodies, corrupt bodies, or unsupported versions.
Important invariants:
Decode returns only for fully valid codes; callers can safely replace config after successful return.
Internal logic:
Assert IllegalArgumentException for wrong prefix, empty body, corrupt body, and a validly-compressed payload with a bad version byte.
Pseudocode:
assert decode WP: throws
assert decode WPC: throws
assert decode WPC:not-valid throws
badVersion = configCodeForRawPayload(version 99, END)
assert decode badVersion throws
Implementation notes:
The unsupported-version fixture uses the same ASCII stream alphabet and raw DEFLATE wrapper as production encoding so the failure reaches version validation.
AI self-check:
Confirm every assertion expects IllegalArgumentException.
]]*/
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

    /*[[AI-FN-DOC
Function:
configCodeReplacementResetsOmittedFieldsToDefaults
Purpose:
Verify importing a sparse/default config snapshot replaces old values instead of merging with them.
Why this exists:
The requested config-code import behavior is complete replacement, meaning omitted fields must reset to defaults.
When to use:
Run after changing WaypointerConfig.replaceWith or WaypointerConfigCodec default-diff behavior.
Inputs:
No parameters. Builds one live config with non-default values and one decoded default replacement.
Outputs:
No return value. Assertions fail if old values survive replacement.
Side effects:
Mutates only local WaypointerConfig instances.
Failure modes:
Fails if replaceWith merges fields, misses a new field, or decoded default codes no longer produce defaults.
Important invariants:
Replacement copies from the decoded snapshot and performs one save on the live object.
Internal logic:
Set live values away from defaults, decode a default config code, replace live with decoded, and assert the old values reset.
Pseudocode:
live = config with non-default defaultWaypointColor, showRouteLines, importedRouteColorMode, blacklist
replacement = decode encode defaults
live.replaceWith(replacement)
assert fields equal default values and blacklist is empty
Implementation notes:
This models the UI import callback without needing clipboard or Minecraft screen classes.
AI self-check:
Verify both a color field and a boolean/enum/list field reset.
]]*/
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

    /*[[AI-FN-DOC
Function:
configCodeForRawPayload
Purpose:
Build a syntactically valid WPC: code around an arbitrary raw binary payload for decoder rejection tests.
Why this exists:
Unsupported-version tests need the payload to pass ASCII decoding and raw DEFLATE inflation so decode reaches its version validation branch.
When to use:
Use only inside WaypointerConfigTest for deliberately malformed or future-version payload fixtures.
Inputs:
raw is the exact uncompressed binary payload to wrap; callers supply small byte arrays.
Outputs:
Returns a WPC: string containing the compressed and ASCII-stream-encoded payload.
Side effects:
Allocates compression buffers and ends the Deflater.
Failure modes:
Throws IOException if the compression stream fails unexpectedly.
Important invariants:
The helper must use the same raw nowrap DEFLATE setting as WaypointerConfigCodec.deflate.
Internal logic:
Create an output stream, compress raw bytes with a raw DeflaterOutputStream, end the deflater, ASCII-encode the compressed bytes, and add the WPC: prefix.
Pseudocode:
out = byte array stream
deflater = new raw deflater
try deflater stream:
  write raw
finally:
  end deflater
return MAGIC + AsciiStreamCodec.encode(out bytes)
Implementation notes:
This duplicates only enough production behavior to target a specific decode branch; the production deflate helper remains private.
AI self-check:
Verify callers pass a version byte followed by END when testing unsupported versions.
]]*/
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
