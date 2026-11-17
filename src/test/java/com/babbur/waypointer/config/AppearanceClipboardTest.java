package com.babbur.waypointer.config;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceClipboardTest {

    @AfterEach
    void clear() {
        AppearanceClipboard.clear();
    }

    @Test
    void aRoutesLookPastesOntoAnotherRouteAndKeepsItsOtherSettings() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup from = WaypointGroup.create("From", "hub");
        WaypointGroup to = WaypointGroup.create("To", "hub");
        from.setStyleOverride(WaypointStyle.EMPTY.with("tracerOpacity", "0.25").with("routeLineDashed", "true"));
        to.setStyleOverride(WaypointStyle.EMPTY.with("labelScale", "2.0"));
        AppearanceScope source = AppearanceScope.route(config, from, from::setStyleOverride);
        AppearanceScope target = AppearanceScope.route(config, to, to::setStyleOverride);

        assertEquals(2, AppearanceClipboard.copy(source, "From"));
        assertEquals(2, AppearanceClipboard.paste(target, property -> true));
        assertEquals("0.25", to.styleOverride().values().get("tracerOpacity"));
        assertEquals("true", to.styleOverride().values().get("routeLineDashed"));
        assertEquals("2.0", to.styleOverride().values().get("labelScale"), "pasting merges");
    }

    @Test
    void settingsTheTargetCannotHaveAreSkippedAndInheritedValuesAreNotPinned() {
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(0, 0, 0));
        String inheritedOpacity = WaypointStyles.valueOf(config, "tracerOpacity");
        AppearanceClipboard.copy(WaypointStyle.EMPTY.with("routeLineDashed", "true")
                .with("tracerOpacity", inheritedOpacity), "Somewhere");
        AppearanceScope waypoint = AppearanceScope.waypoint(config, route, 0,
                style -> route.setWaypointStyle(0, style));

        assertEquals(1, AppearanceClipboard.paste(waypoint, property -> !property.equals("routeLineDashed")));
        assertTrue(route.get(0).styleOverride().isEmpty(), "a value it inherits anyway is not pinned");
    }

    @Test
    void globalCopiesOnlyWhatDiffersFromTheDefaultsAndPastesOntoAZone() {
        WaypointerConfig config = new WaypointerConfig();
        assertEquals(0, AppearanceClipboard.copyGlobal(config, "Everywhere"));
        assertNull(AppearanceClipboard.copied(), "nothing changed, nothing copied");

        config.setTracerOpacity(0.1);
        assertEquals(1, AppearanceClipboard.copyGlobal(config, "Everywhere"));
        AppearanceScope zone = AppearanceScope.zone(config, "garden");
        config.setTracerOpacity(0.9);
        assertEquals(1, AppearanceClipboard.paste(zone, property -> true));
        assertEquals("0.1", config.zoneStyle("garden").values().get("tracerOpacity"));
        assertFalse(AppearanceClipboard.copied().style().isEmpty());
    }
}
