package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class CompassMarkerState {
    private static final List<WeakReference<Waypoint>> arrived = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> defocused = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> targets = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> regionExited = new ArrayList<>();

    private static int version;

    private CompassMarkerState() {}

    public static int version() {
        return version;
    }

    public static boolean arrived(Waypoint waypoint) {
        return contains(arrived, waypoint);
    }

    public static boolean isTarget(Waypoint waypoint) {
        return contains(targets, waypoint);
    }

    public static boolean regionExited(Waypoint waypoint) {
        return contains(regionExited, waypoint);
    }

    public static boolean navigationStopped(Waypoint waypoint) {
        return arrived(waypoint) || regionExited(waypoint) || defocused(waypoint);
    }

    public static boolean defocused(Waypoint waypoint) {
        return contains(defocused, waypoint);
    }

    static void markDefocused(Waypoint waypoint) {
        mark(defocused, waypoint);
    }

    static void markRegionExited(Waypoint waypoint) {
        mark(regionExited, waypoint);
    }

    static void markTarget(Waypoint waypoint) {
        mark(targets, waypoint);
    }

    static void markArrived(Waypoint waypoint) {
        mark(arrived, waypoint);
    }

    private static void mark(List<WeakReference<Waypoint>> markers, Waypoint waypoint) {
        if (waypoint == null || contains(markers, waypoint)) return;
        markers.removeIf(reference -> reference.get() == null);
        markers.add(new WeakReference<>(waypoint));
        version++;
    }

    private static boolean contains(List<WeakReference<Waypoint>> markers, Waypoint waypoint) {
        if (waypoint == null || markers.isEmpty()) return false;
        for (WeakReference<Waypoint> reference : markers) {
            if (reference.get() == waypoint) return true;
        }
        return false;
    }
}
