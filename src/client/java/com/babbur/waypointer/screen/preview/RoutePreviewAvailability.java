package com.babbur.waypointer.screen.preview;

public final class RoutePreviewAvailability {

    static final long RETRY_NANOS = 2_000_000_000L;

    private String routeId = "";
    private boolean unavailable;
    private long retryAfterNanos;

    public synchronized void beginScene(String nextRouteId) {
        String safe = nextRouteId == null ? "" : nextRouteId;
        if (!safe.equals(routeId)) {
            routeId = safe;
            unavailable = false;
            retryAfterNanos = 0L;
        }
    }

    public void markUnavailable() {
        markUnavailableAt(System.nanoTime());
    }

    synchronized void markUnavailableAt(long nowNanos) {
        unavailable = true;
        retryAfterNanos = nowNanos + RETRY_NANOS;
    }

    public boolean unavailable() {
        return unavailableAt(System.nanoTime());
    }

    synchronized boolean unavailableAt(long nowNanos) {
        if (unavailable && nowNanos - retryAfterNanos >= 0L) {
            unavailable = false;
            retryAfterNanos = 0L;
        }
        return unavailable;
    }

    public synchronized void reset() {
        routeId = "";
        unavailable = false;
        retryAfterNanos = 0L;
    }
}
