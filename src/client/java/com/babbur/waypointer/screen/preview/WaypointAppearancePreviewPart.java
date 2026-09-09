package com.babbur.waypointer.screen.preview;

import java.util.Collection;

/** Stable setting identities keep preview guidance independent of translated section titles. */
public enum WaypointAppearancePreviewPart {
    DISTANCE("maxStaticWaypointRenderDistance"), MARKER("boxStyle"), STATES("currentWaypointOpacity"),
    LABELS("labelScale"), TRACERS("showTracer"), BEAMS("beaconBeamMode"),
    CONNECTORS("showRouteLines"), PROGRESSION("routeMinRenderDistance");

    private final String setting;
    WaypointAppearancePreviewPart(String setting) { this.setting = setting; }
    public static WaypointAppearancePreviewPart forSettings(Collection<String> settings) {
        for (var part : values()) if (settings.contains(part.setting)) return part;
        return null;
    }
}
