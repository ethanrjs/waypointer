package dev.ethan.waypointer.color;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.WaypointGroup;

import java.util.List;

/**
 * Shared route color post-processing for imports and UI route edits.
 */
public final class RouteColorPolicy {

        private RouteColorPolicy() {
    }

        public static void applyImportedRouteDefaults(List<WaypointGroup> groups, WaypointerConfig config) {
        WaypointGroup.GradientMode mode = config.importedRouteColorMode();
        int color = config.importedRouteDefaultColor();
        for (WaypointGroup group : groups) {
            applyRouteColorMode(group, mode, color);
        }
    }

        public static void applyRouteColorMode(WaypointGroup group,
                                           WaypointGroup.GradientMode mode,
                                           int staticColor) {
        WaypointGroup.GradientMode safeMode = mode == null ? WaypointGroup.GradientMode.STATIC : mode;
        if (safeMode == WaypointGroup.GradientMode.STATIC) {
            group.setStaticColor(staticColor);
            group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        } else if (safeMode == WaypointGroup.GradientMode.AUTO) {
            group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        } else {
            group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        }
    }
}
