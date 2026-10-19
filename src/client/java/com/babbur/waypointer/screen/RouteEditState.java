package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.*;
import java.util.List;
import java.util.stream.IntStream;

record RouteEditState(String name, String zoneId, boolean enabled, WaypointGroup.LoadMode loadMode,
                      WaypointGroup.GradientMode gradientMode, int staticColor,
                      int startColor, int endColor, double radius, boolean skipAhead,
                      List<Waypoint> points, List<Integer> manualColors, int currentIndex, List<Integer> reached,
                      String preset, WaypointStyle style, WaypointPaint paint, boolean paintEnabled) {
    static RouteEditState capture(WaypointGroup group) {
        return new RouteEditState(group.name(), group.zoneId(), group.enabled(), group.loadMode(), group.gradientMode(),
                group.staticColor(), group.gradientStartColor(), group.gradientEndColor(),
                group.defaultRadius(), group.skipAheadEnabled(), List.copyOf(group.waypoints()),
                group.manualColorSnapshot(), group.currentIndex(),
                IntStream.range(0, group.size()).filter(group::isStaticWaypointReached).boxed().toList(),
                group.stylePresetId(),
                group.styleOverride(), group.paint(), group.paintEnabled());
    }

    void restore(WaypointGroup group) {
        group.setName(name);
        group.setZoneId(zoneId);
        group.setEnabled(enabled);
        group.setLoadMode(loadMode);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setStaticColor(staticColor);
        group.setGradientStartColor(startColor);
        group.setGradientEndColor(endColor);
        group.replaceWaypoints(points);
        group.setManualColorSnapshot(manualColors);
        group.setGradientMode(gradientMode);
        group.setDefaultRadius(radius);
        group.setSkipAheadEnabled(skipAhead);
        group.setCurrentIndex(currentIndex);
        group.resetStaticReachState();
        reached.forEach(group::markStaticWaypointReached);
        group.setStylePresetId(preset);
        group.setStyleOverride(style);
        group.setPaint(paint);
        group.setPaintEnabled(paintEnabled);
    }
}
