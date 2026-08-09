package com.babbur.waypointer.core;

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

        double dx = waypoint.centerX() - playerX;
        double dy = waypoint.centerY() - playerY;
        double dz = waypoint.centerZ() - playerZ;
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }
}
