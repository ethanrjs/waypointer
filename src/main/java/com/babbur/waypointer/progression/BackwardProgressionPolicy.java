package com.babbur.waypointer.progression;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

/** Pure proximity policy for moving a sequence route to an earlier main waypoint. */
public final class BackwardProgressionPolicy {

    private BackwardProgressionPolicy() {}

    public static int reachedEarlierIndex(WaypointGroup group, boolean enabled,
                                          double px, double py, double pz) {
        if (!enabled || group == null || !group.enabled() || group.temp()
                || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE
                || group.proximitySuppressedIndex() >= 0) {
            return -1;
        }

        int from = Math.min(group.currentIndex(), group.size());
        if (from <= 0) return -1;

        int[] target = { -1 };
        group.forEachNearbyIndex(px, py, pz, group.maxEffectiveRadius(), index -> {
            if (index >= from || index <= target[0]
                    || group.isWaypointDisabled(index)
                    || group.isSubwaypoint(index)
                    || group.isProximitySuppressed(index)) {
                return true;
            }

            Waypoint waypoint = group.get(index);
            if (requiresDungeonEvent(group, waypoint)
                    || !isWithinReach(group, waypoint, px, py, pz)) {
                return true;
            }
            target[0] = index;
            return true;
        });
        return target[0];
    }

    public static boolean retreatIfReached(WaypointGroup group, boolean enabled,
                                           double px, double py, double pz) {
        int target = reachedEarlierIndex(group, enabled, px, py, pz);
        if (target < 0) return false;
        group.setCurrentTargetIndex(target);
        if (group.currentIndex() != target) return false;
        group.suppressProximityUntilExit(target);
        return true;
    }

    private static boolean requiresDungeonEvent(WaypointGroup group, Waypoint waypoint) {
        return group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                && (waypoint.flags() & Waypoint.DUNGEON_COMPLETION_FLAGS) != 0;
    }

    private static boolean isWithinReach(WaypointGroup group, Waypoint waypoint,
                                         double px, double py, double pz) {
        double radius = group.effectiveRadius(waypoint);
        double dx = waypoint.centerX() - px;
        double dy = waypoint.centerY() - py;
        double dz = waypoint.centerZ() - pz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
