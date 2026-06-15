package dev.ethan.waypointer.core;

/** Shared render/progression visibility helpers for waypoint-distance checks. */
public final class WaypointVisibility {

    private WaypointVisibility() {
    }

    public static double squaredRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) return 0.0;
        return radius * radius;
    }

    /*[[AI-FN-DOC
Function:
isHiddenNearPlayer.
Purpose:
Determine whether a waypoint should be hidden because the player is within a configured near-hide radius.
Why this exists:
World boxes, labels, tracers, and connector lines need shared near-hide distance logic so they disappear consistently.
When to use:
Use from render or tracer code when applying near-hide behavior. Do not use for route progression, which uses effective reach radius instead.
Inputs:
waypoint is the marker to test and may be null; playerX/playerY/playerZ are player world coordinates; radiusSquared is the precomputed squared hide radius.
Outputs:
Returns true when a non-null waypoint is within radiusSquared of the player, false otherwise.
Side effects:
None.
Failure modes:
Null waypoint or non-positive radiusSquared returns false.
Important invariants:
Distance must use waypoint.centerX/Y/Z so precise small waypoint hiding matches the rendered marker rather than the old whole-block center.
Internal logic:
Guard null/disabled radius, subtract player position from waypoint center, and compare squared distance to radiusSquared.
Pseudocode:
if waypoint null or radiusSquared <= 0, return false
dx = waypoint.centerX - playerX
dy = waypoint.centerY - playerY
dz = waypoint.centerZ - playerZ
return dx*dx + dy*dy + dz*dz <= radiusSquared
Implementation notes:
Default block waypoints still evaluate as x/y/z + 0.5 because their precise center fields default to block center.
AI self-check:
Verify this remains pure and shared callers do not need to know whether a waypoint is small.
]]*/
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
