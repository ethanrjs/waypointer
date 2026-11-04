package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;

import java.util.Objects;

final class WaypointColorPickerState {

    private final int targetIndex;
    private final boolean applyToSubwaypointsVisible;
    private boolean applyToSubwaypoints;

    private WaypointColorPickerState(int targetIndex, boolean applyToSubwaypointsVisible) {
        this.targetIndex = targetIndex;
        this.applyToSubwaypointsVisible = applyToSubwaypointsVisible;
    }

    static WaypointColorPickerState forTarget(WaypointGroup group, int targetIndex) {
        Objects.requireNonNull(group, "group");
        if (targetIndex < 0 || targetIndex >= group.size()) {
            throw new IllegalArgumentException("targetIndex is outside the route");
        }
        return new WaypointColorPickerState(
                targetIndex, ownsSubwaypoints(group, targetIndex));
    }

    boolean applyToSubwaypointsVisible() {
        return applyToSubwaypointsVisible;
    }

    boolean applyToSubwaypoints() {
        return applyToSubwaypoints;
    }

    void setApplyToSubwaypoints(boolean selected) {
        applyToSubwaypoints = applyToSubwaypointsVisible && selected;
    }

    boolean applyColor(WaypointGroup group, int rgb) {
        if (group == null || targetIndex < 0 || targetIndex >= group.size()) return false;

        group.lockWaypointColor(targetIndex, rgb);
        if (applyToSubwaypoints && ownsSubwaypoints(group, targetIndex)) {
            int end = group.childEndExclusive(targetIndex);
            for (int child = targetIndex + 1; child < end; child++) {
                group.lockWaypointColor(child, rgb);
            }
        }
        return true;
    }

    private static boolean ownsSubwaypoints(WaypointGroup group, int index) {
        return index >= 0
                && index < group.size()
                && !group.isSubwaypoint(index)
                && group.childEndExclusive(index) > index + 1;
    }

}
