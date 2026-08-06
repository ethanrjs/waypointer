package com.babbur.waypointer.screen.preview;

/** Fail-closed state so preview failures never disable export actions. */
public final class RoutePreviewAvailability {

    private static String routeId = "";
    private static volatile boolean unavailable;

    private RoutePreviewAvailability() {}

    public static void beginScene(String nextRouteId) {
        String safe = nextRouteId == null ? "" : nextRouteId;
        if (!safe.equals(routeId)) {
            routeId = safe;
            unavailable = false;
        }
    }

    public static void markUnavailable() {
        unavailable = true;
    }

    public static boolean unavailable() {
        return unavailable;
    }

    public static void reset() {
        routeId = "";
        unavailable = false;
    }
}
