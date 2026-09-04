package com.babbur.waypointer.core;

/** Resolves a sequential waypoint's visual role without renderer dependencies. */
public final class SequenceRoleColor {

    public enum Role {
        PREVIOUS,
        CURRENT,
        NEXT,
        NONE
    }

    private SequenceRoleColor() {}

    public static Role roleFor(WaypointGroup group, int waypointIndex) {
        if (group == null
                || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE
                || waypointIndex < 0
                || waypointIndex >= group.size()) {
            return Role.NONE;
        }

        int currentIndex = group.currentIndex();
        int activeSubwaypointParent = group.activeSubwaypointParentIndex();
        if (!group.isSubwaypoint(waypointIndex)) {
            if (waypointIndex == activeSubwaypointParent) return Role.CURRENT;
            if (waypointIndex < currentIndex) return Role.PREVIOUS;
            if (waypointIndex == currentIndex) return Role.CURRENT;
            return Role.NEXT;
        }

        int parentIndex = group.parentMainIndex(waypointIndex);
        if (parentIndex == activeSubwaypointParent
                || waypointIndex == currentIndex
                || parentIndex == currentIndex) {
            return Role.CURRENT;
        }
        return parentIndex < currentIndex ? Role.PREVIOUS : Role.NEXT;
    }

    public static int resolve(
            WaypointGroup group,
            int waypointIndex,
            boolean enabled,
            int previousColor,
            int currentColor,
            int nextColor,
            int fallbackColor) {
        if (!enabled) return fallbackColor & 0xFFFFFF;
        return switch (roleFor(group, waypointIndex)) {
            case PREVIOUS -> previousColor & 0xFFFFFF;
            case CURRENT -> currentColor & 0xFFFFFF;
            case NEXT -> nextColor & 0xFFFFFF;
            case NONE -> fallbackColor & 0xFFFFFF;
        };
    }
}
