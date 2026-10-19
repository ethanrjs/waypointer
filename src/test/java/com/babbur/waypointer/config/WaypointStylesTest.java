package com.babbur.waypointer.config;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointStylesTest {
    @Test void existingLabelOpacityBecomesIndependentBackdropStartingValue() {
        WaypointerConfig config = WaypointerConfig.fromJson("{\"labelOpacity\":0.4}");
        assertEquals(0.4, config.labelBackdropOpacity());
        config.setLabelOpacity(0.8);
        assertEquals(0.4, config.labelBackdropOpacity());
        assertEquals(0.7, WaypointerConfig.fromJson("{\"labelOpacity\":0.4,\"labelBackdropOpacity\":0.7}").labelBackdropOpacity());
    }
    @Test void inheritanceIsSparseCachedAndInvalidatedByGlobalEdits() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Test", "hub");
        Waypoint point = Waypoint.at(1, 2, 3);
        assertSame(config, WaypointStyles.resolve(config, route, point));
        config.putNamedStyle("Preferred", WaypointStyle.EMPTY.with("labelScale", 2));
        config.setPreferredStyleId("Preferred");
        config.putNamedStyle("Small", WaypointStyle.EMPTY.with("labelScale", 0.5));
        route.setStylePresetId("Small");
        route.setStyleOverride(WaypointStyle.EMPTY.with("labelOpacity", 0.4));
        point = point.withStyleOverride(WaypointStyle.EMPTY.with("labelScale", 3));
        WaypointerConfig resolved = WaypointStyles.resolve(config, route, point);
        assertEquals(3, resolved.labelScale());
        assertEquals(0.4, resolved.labelOpacity());
        assertEquals(1, config.labelScale());
        assertSame(resolved, WaypointStyles.resolve(config, route, point));
        config.setRouteLineOpacity(0.7);
        assertNotSame(resolved, WaypointStyles.resolve(config, route, point));
        assertEquals(0.7, WaypointStyles.resolve(config, route, point).routeLineOpacity());
        config.putNamedStyle("Small", WaypointStyle.EMPTY.with("labelScale", 1.5));
        assertEquals(1.5, WaypointStyles.resolve(config, route, point.withStyleOverride(null)).labelScale());
    }
    @Test void lineOfSightOnlyCanBeSetEverywhereForARouteOrForOneWaypoint() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Gems", "crystal_hollows");
        Waypoint point = Waypoint.at(1, 2, 3);
        assertFalse(WaypointStyles.resolve(config, route, point).lineOfSightOnly());
        config.setLineOfSightOnly(true);
        assertTrue(WaypointStyles.resolve(config, route, point).lineOfSightOnly());
        route.setStyleOverride(WaypointStyle.EMPTY.with("lineOfSightOnly", false));
        assertFalse(WaypointStyles.resolve(config, route, point).lineOfSightOnly(), "routes beat Everywhere");
        Waypoint seen = point.withStyleOverride(WaypointStyle.EMPTY.with("lineOfSightOnly", true));
        assertTrue(WaypointStyles.resolve(config, route, seen).lineOfSightOnly(), "waypoints beat routes");
    }
    @Test void aRouteCanHideItsLabelsAndLimitTheirDistanceWithoutTouchingOtherRoutes() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup quiet = WaypointGroup.create("Quiet", "hub");
        WaypointGroup other = WaypointGroup.create("Other", "hub");
        quiet.setStyleOverride(WaypointStyle.EMPTY.with("showLabels", false).with("labelMaxDistance", 48.0));
        assertFalse(WaypointStyles.resolve(config, quiet, null).showLabels());
        assertEquals(48.0, WaypointStyles.resolve(config, quiet, null).labelMaxDistance());
        assertTrue(WaypointStyles.resolve(config, other, null).showLabels());
        assertEquals(0.0, WaypointStyles.resolve(config, other, null).labelMaxDistance());
    }
    @Test void zoneStylesSitBetweenGlobalAndRouteAndCoverZoneFamilies() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup shaft = WaypointGroup.create("Shaft", "mineshaft_crystal");
        WaypointGroup hollows = WaypointGroup.create("Gems", "crystal_hollows");
        config.setZoneStyle("mineshaft", WaypointStyle.EMPTY.with("showTracer", true).with("labelScale", 2));
        config.setZoneStyle("mineshaft_crystal", WaypointStyle.EMPTY.with("labelScale", 3));
        config.setZoneStyle("crystal_hollows", WaypointStyle.EMPTY.with("showWaypointNames", false));
        assertEquals(List.of("mineshaft", "mineshaft_crystal"), WaypointStyles.zoneKeys("mineshaft_crystal"));
        assertEquals(List.of("crystal_hollows"), WaypointStyles.zoneKeys("crystal_hollows"));
        WaypointerConfig resolvedShaft = WaypointStyles.resolve(config, shaft, null);
        assertTrue(resolvedShaft.showTracer());
        assertEquals(3, resolvedShaft.labelScale(), "the exact zone beats its family");
        assertFalse(WaypointStyles.resolve(config, hollows, null).showWaypointNames());
        assertTrue(WaypointStyles.resolve(config, WaypointGroup.create("Hub", "hub"), null).showWaypointNames());
        shaft.setStyleOverride(WaypointStyle.EMPTY.with("labelScale", 1.25));
        assertEquals(1.25, WaypointStyles.resolve(config, shaft, null).labelScale(), "routes beat zones");
        config.setZoneStyle("crystal_hollows", WaypointStyle.EMPTY);
        assertTrue(config.zoneStyles().keySet().stream().noneMatch("crystal_hollows"::equals));
    }
    @Test void zoneStylesSurviveSaveAndLoadAndResetToDefaultsClearsThem() {
        WaypointerConfig loaded = WaypointerConfig.fromJson("""
                {"zoneStyles":{"mineshaft":{"values":{"showRouteLines":"true","bogus":"x"}},"":{"values":{}}}}
                """);
        assertEquals(Map.of("showRouteLines", "true"), loaded.zoneStyle("mineshaft").values());
        assertEquals(1, loaded.zoneStyles().size());
        loaded.resetToDefaults();
        assertTrue(loaded.zoneStyles().isEmpty());
    }
    @Test void colorOverridesPreserveOriginalColorsUnlessExplicit() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Test", "hub");
        Waypoint waypoint = Waypoint.at(1, 2, 3).withColor(0x123456);
        assertEquals(0x123456, WaypointStyles.waypointColor(config, route, waypoint));
        route.setStyleOverride(WaypointStyle.EMPTY.with("defaultWaypointColor", 0xABCDEF));
        assertEquals(0xABCDEF, WaypointStyles.waypointColor(config, route, waypoint));
        waypoint = waypoint.withStyleOverride(WaypointStyle.EMPTY.with("defaultWaypointColor", "invalid"));
        assertEquals(0xABCDEF, WaypointStyles.waypointColor(config, route, waypoint));
    }
    @Test void localStorageAndPointMutationsPreserveAppearance() {
        WaypointStyle style = WaypointStyle.EMPTY.with("waypointIcon", "STAR");
        Waypoint original = Waypoint.at(1, 2, 3).withStyleOverride(style);
        assertEquals(style, original.withName("A").withColor(5).withFlags(1).withRadius(2)
                .withPos(4, 5, 6).withPreciseSixteenths(8, 9, 10).withTemp(1, 20).styleOverride());
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.setStylePresetId("Personal");
        group.setStyleOverride(WaypointStyle.EMPTY.with("routeLineWidth", 4));
        group.add(original);
        WaypointGroup restored = Storage.groupFromJson(Storage.groupToJson(group));
        assertEquals("Personal", restored.stylePresetId());
        assertEquals(group.styleOverride(), restored.styleOverride());
        assertEquals(style, restored.get(0).styleOverride());
        assertTrue(Storage.waypointFromJson(JsonParser.parseString(
                "{\"x\":1,\"y\":2,\"z\":3,\"styleOverride\":[]}").getAsJsonObject()).styleOverride().isEmpty());
    }
    @Test void nonFinitePersistedAppearanceValuesRemainSaveable() {
        WaypointerConfig loaded = WaypointerConfig.fromJson("""
                {"routeLineOpacity":"NaN","beaconMotionSpeed":"Infinity","waypointIcon":"invalid"}
                """);
        assertEquals(1, loaded.routeLineOpacity());
        assertEquals(1, loaded.beaconMotionSpeed());
        assertEquals("NONE", loaded.waypointIcon());
        assertDoesNotThrow(() -> WaypointStyles.apply(loaded, WaypointStyle.EMPTY));
    }

    @Test void corruptOptionalStyleEntriesDoNotDiscardValidConfig() {
        WaypointerConfig loaded = WaypointerConfig.fromJson("""
                {"labelScale":2,"preferredStyleId":[],"namedStyles":{
                  "Broken":[],"Also broken":{"values":[]},
                  "Good":{"values":{"labelScale":"1.5","beaconMotionSpeed":"NaN",
                    "showTracer":{},"crystalHollowsSkyblockerSharing":"false"}}},
                 "crystalHollowsStyles":{"temple":{"values":{"waypointIcon":"STAR"}}}}
                """);
        assertEquals(2, loaded.labelScale());
        assertEquals("", loaded.preferredStyleId());
        assertEquals(1, loaded.namedStyles().size());
        assertEquals(Map.of("labelScale", "1.5"), loaded.namedStyles().get("Good").values());
        assertEquals("STAR", loaded.crystalHollowsStyle("temple").values().get("waypointIcon"));
    }
    @Test void importingLegacySettingsKeepsLocalStylesAndResetDisablesThemWithoutDeletingLibrary() {
        WaypointerConfig live = new WaypointerConfig();
        WaypointStyle saved = WaypointStyle.EMPTY.with("labelScale", 2);
        live.putNamedStyle("Saved", saved);
        live.setPreferredStyleId("Saved");
        live.setCrystalHollowsStyle("temple", saved);
        live.setRouteLineOpacity(0.4);
        live.setWaypointIcon("STAR");
        live.replaceShareableSettingsWith(WaypointerConfigCodec.decode(
                WaypointerConfigCodec.encode(new WaypointerConfig())));
        assertEquals(saved, live.namedStyles().get("Saved"));
        assertEquals("Saved", live.preferredStyleId());
        assertEquals(saved, live.crystalHollowsStyle("temple"));
        assertEquals(0.4, live.routeLineOpacity());
        assertEquals("STAR", live.waypointIcon());
        live.resetToDefaults();
        assertEquals(saved, live.namedStyles().get("Saved"));
        assertEquals("", live.preferredStyleId());
        assertTrue(live.crystalHollowsStyle("temple").isEmpty());
        assertEquals(1, live.routeLineOpacity());
        assertEquals("NONE", live.waypointIcon());
    }
    @Test void newDefaultsDoNotEnableFeaturesAndInvalidOverridesCannotChangeBehavior() {
        WaypointerConfig config = WaypointerConfig.fromJson("{}");
        assertFalse(config.routeLineDashed());
        assertFalse(config.tracerEdgeArrow());
        assertFalse(config.preventLabelOverlap());
        assertFalse(config.showWaypointRoleBadge());
        assertEquals("NONE", config.waypointIcon());
        assertEquals(1, config.routeLineOpacity());
        WaypointerConfig preview = WaypointStyles.preview(config, WaypointStyle.EMPTY
                .with("routeLineOpacity", 100).with("beaconMotionSpeed", "NaN")
                .with("crystalHollowsSkyblockerSharing", false).with("showTracer", "garbage"));
        assertEquals(1, preview.routeLineOpacity());
        assertEquals(1, preview.beaconMotionSpeed());
        assertTrue(preview.crystalHollowsSkyblockerSharing());
        assertTrue(preview.showTracer());
        assertTrue(WaypointStyles.capture(config).values().containsKey("waypointIcon"));
        assertFalse(WaypointStyles.supportedProperties().contains("crystalHollowsSkyblockerSharing"));
        assertFalse(WaypointStyles.supportedProperties().contains("renderAntialiasing"));
    }
}
