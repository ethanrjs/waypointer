package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Client-thread-only arrival state; removed markers are not retained or exported. */
public final class CompassMarkerState {
    private static final List<WeakReference<Waypoint>> arrived = new ArrayList<>();

    private CompassMarkerState() {}

    public static boolean arrived(Waypoint waypoint) {
        arrived.removeIf(reference -> reference.get() == null);
        for (WeakReference<Waypoint> reference : arrived) {
            if (reference.get() == waypoint) return true;
        }
        return false;
    }

    static void markArrived(Waypoint waypoint) {
        if (!arrived(waypoint)) arrived.add(new WeakReference<>(waypoint));
    }
}
