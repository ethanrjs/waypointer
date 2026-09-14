package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Client-thread-only compass and arrival state; removed markers are not retained or exported. */
public final class CompassMarkerState {
    private static final List<WeakReference<Waypoint>> arrived = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> defocused = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> targets = new ArrayList<>();
    private static final List<WeakReference<Waypoint>> regionExited = new ArrayList<>();

    private CompassMarkerState() {}

    public static boolean arrived(Waypoint waypoint) {
        return contains(arrived, waypoint);
    }

    public static boolean isTarget(Waypoint waypoint) {
        return contains(targets, waypoint);
    }

    public static boolean regionExited(Waypoint waypoint) {
        return contains(regionExited, waypoint);
    }

    /** A stopped marker stays visible, but cannot be selected by automatic tracer rendering. */
    public static boolean navigationStopped(Waypoint waypoint) {
        return arrived(waypoint) || regionExited(waypoint) || defocused(waypoint);
    }

    public static boolean defocused(Waypoint waypoint) {
        return contains(defocused, waypoint);
    }

    static void markDefocused(Waypoint waypoint) {
        if (waypoint != null && !defocused(waypoint)) defocused.add(new WeakReference<>(waypoint));
    }

    static void markRegionExited(Waypoint waypoint) {
        if (!regionExited(waypoint)) regionExited.add(new WeakReference<>(waypoint));
    }

    static void markTarget(Waypoint waypoint) {
        if (!isTarget(waypoint)) targets.add(new WeakReference<>(waypoint));
    }

    private static boolean contains(List<WeakReference<Waypoint>> markers, Waypoint waypoint) {
        if (waypoint == null) return false;
        markers.removeIf(reference -> reference.get() == null);
        for (WeakReference<Waypoint> reference : markers) {
            if (reference.get() == waypoint) return true;
        }
        return false;
    }

    static void markArrived(Waypoint waypoint) {
        if (!arrived(waypoint)) arrived.add(new WeakReference<>(waypoint));
    }
}
