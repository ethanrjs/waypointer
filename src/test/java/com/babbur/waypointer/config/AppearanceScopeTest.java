package com.babbur.waypointer.config;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceScopeTest {

    @Test void routeScopeReadsInheritedValuesAndNamesTheirSource() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Ruby loop", "mineshaft_crystal");
        config.setZoneStyle("mineshaft", WaypointStyle.EMPTY.with("labelScale", 2.0));
        config.setZoneStyle("mineshaft_crystal", WaypointStyle.EMPTY.with("showTracer", false));
        AppearanceScope scope = AppearanceScope.route(config, route, route::setStyleOverride);

        assertEquals(2.0, scope.effective().labelScale());
        assertFalse(scope.effective().showTracer());
        assertEquals("mineshaft", scope.source("labelScale").key());
        assertEquals("mineshaft_crystal", scope.source("showTracer").key());
        assertNull(scope.source("labelOpacity"), "untouched values come from global settings");
        assertEquals(0, scope.count());
        assertEquals(1.0, config.labelScale(), "reading a scope never changes the global settings");
    }

    @Test void capturingAValueStoresItOnlyWhenItDiffersFromTheInheritedOne() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        AppearanceScope scope = AppearanceScope.route(config, route, route::setStyleOverride);

        WaypointerConfig draft = scope.effective();
        draft.setShowTracer(!config.showTracer());
        scope.capture(draft, "showTracer");
        assertTrue(scope.isSetHere("showTracer"));
        assertEquals(1, scope.count());
        assertEquals(!config.showTracer(), WaypointStyles.resolve(config, route, null).showTracer(),
                "route edits render live through the normal cascade");

        draft.setShowTracer(config.showTracer());
        scope.capture(draft, "showTracer");
        assertFalse(scope.isSetHere("showTracer"), "setting a value back means inheriting it");
        assertTrue(route.styleOverride().isEmpty());
    }

    @Test void inheritAndClearRemoveOnlyThisLevelsValues() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.setStyleOverride(new WaypointStyle(Map.of(
                "labelScale", "2.0", "showTracer", "false", "futureProperty", "7")));
        AppearanceScope scope = AppearanceScope.route(config, route, route::setStyleOverride);

        scope.inherit("labelScale");
        assertFalse(route.styleOverride().values().containsKey("labelScale"));
        assertEquals(1, scope.count());

        scope.clear();
        assertEquals(Map.of("futureProperty", "7"), route.styleOverride().values(),
                "values written by a newer version survive a reset");
        assertEquals(0, scope.count());
    }

    @Test void waypointScopeInheritsTheRouteAndWritesOnlyThatWaypoint() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3));
        route.add(Waypoint.at(4, 5, 6));
        route.setStyleOverride(WaypointStyle.EMPTY.with("labelScale", 2.0));
        AppearanceScope scope = AppearanceScope.waypoint(config, route, 1,
                style -> route.setWaypointStyle(1, style));

        assertEquals(AppearanceScope.Level.ROUTE, scope.source("labelScale").level());
        WaypointerConfig draft = scope.effective();
        assertEquals(2.0, draft.labelScale());
        draft.setLabelScale(3.0);
        scope.capture(draft, "labelScale");

        assertEquals(3.0, WaypointStyles.resolve(config, route, route.get(1)).labelScale());
        assertEquals(2.0, WaypointStyles.resolve(config, route, route.get(0)).labelScale());
        assertTrue(route.get(0).styleOverride().isEmpty());
    }

    @Test void zoneScopeWritesTheZoneStyleAndInheritsItsFamily() {
        WaypointerConfig config = new WaypointerConfig();
        config.setZoneStyle("dungeon", WaypointStyle.EMPTY.with("showRouteLines", false));
        AppearanceScope family = AppearanceScope.zone(config, "dungeon");
        AppearanceScope floor = AppearanceScope.zone(config, "dungeon_f7");

        assertTrue(family.isSetHere("showRouteLines"));
        assertTrue(family.parents().isEmpty());
        assertEquals(List.of("dungeon"), floor.parents().stream().map(AppearanceScope.Layer::key).toList());
        assertFalse(floor.effective().showRouteLines());

        WaypointerConfig draft = floor.effective();
        draft.setLabelScale(1.5);
        floor.capture(draft, "labelScale");
        assertEquals("1.5", config.zoneStyle("dungeon_f7").values().get("labelScale"));
        assertEquals(1.5, WaypointStyles.resolve(config, WaypointGroup.create("R", "dungeon_f7"), null).labelScale());
    }

    @Test void valueOfReadsOnlyAppearanceProperties() {
        WaypointerConfig config = new WaypointerConfig();
        assertEquals(String.valueOf(config.labelScale()), WaypointStyles.valueOf(config, "labelScale"));
        assertNull(WaypointStyles.valueOf(config, "crystalHollowsSkyblockerSharing"));
    }
}
