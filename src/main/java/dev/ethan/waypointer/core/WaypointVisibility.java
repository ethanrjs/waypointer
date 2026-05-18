package dev.ethan.waypointer.core;

/** Shared render/progression visibility helpers for waypoint-distance checks. */
public final class WaypointVisibility {

    private WaypointVisibility() {
    }

    public static double squaredRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) return 0.0;
        return radius * radius;
    }

    public static boolean isHiddenNearPlayer(Waypoint waypoint,
                                             double playerX,
                                             double playerY,
                                             double playerZ,
                                             double radiusSquared) {
        if (waypoint == null || radiusSquared <= 0.0) return false;

        double dx = waypoint.x() + 0.5 - playerX;
        double dy = waypoint.y() + 0.5 - playerY;
        double dz = waypoint.z() + 0.5 - playerZ;
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }
}
