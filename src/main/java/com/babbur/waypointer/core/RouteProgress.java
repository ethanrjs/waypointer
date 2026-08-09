package com.babbur.waypointer.core;

import com.babbur.waypointer.util.MathUtil;

import java.util.Locale;
import java.util.Objects;

/**
 * Small formatting and counting helpers for route progress displays.
 */
public final class RouteProgress {

    private RouteProgress() {}

    public static final class Snapshot {
        public final int completed;
        public final int currentOrdinal;
        public final int total;
        public final double percentComplete;
        public final boolean complete;

        private Snapshot(int completed, int currentOrdinal, int total,
                         double percentComplete, boolean complete) {
            this.completed = completed;
            this.currentOrdinal = currentOrdinal;
            this.total = total;
            this.percentComplete = percentComplete;
            this.complete = complete;
        }
    }

    public static Snapshot snapshot(WaypointGroup group) {
        Objects.requireNonNull(group, "group");

        int total = group.mainWaypointCount();
        if (total == 0) {
            return new Snapshot(0, 0, 0, 0.0, false);
        }

        int completed;
        int currentOrdinal;
        if (group.isComplete()) {
            completed = total;
            currentOrdinal = total;
        } else if (group.loadMode() == WaypointGroup.LoadMode.STATIC) {
            if (group.hasStaticReachState()) {
                completed = countStaticReachedMainWaypoints(group);
                currentOrdinal = Math.min(total, completed + 1);
            } else {
                currentOrdinal = MathUtil.clamp(group.currentMainOrdinal(), 1, total);
                completed = currentOrdinal - 1;
            }
        } else {
            currentOrdinal = MathUtil.clamp(group.currentMainOrdinal(), 1, total);
            completed = currentOrdinal - 1;
        }

        completed = MathUtil.clamp(completed, 0, total);
        double percent = (completed * 100.0) / total;
        return new Snapshot(completed, currentOrdinal, total, percent, completed >= total);
    }

    public static String summary(WaypointGroup group) {
        Snapshot snapshot = snapshot(group);
        if (snapshot.total == 0) return "0 pts";
        if (snapshot.complete) return "complete";
        return snapshot.completed + "/" + snapshot.total + " "
                + formatPercent(snapshot.percentComplete);
    }

    public static String nextTargetLabel(int currentOrdinal, int total) {
        if (total <= 0) return "Next";
        int ordinal = MathUtil.clamp(currentOrdinal, 1, total);
        int completed = ordinal - 1;
        double percent = (completed * 100.0) / total;
        return "Next (" + ordinal + "/" + total + ", " + formatPercent(percent) + ")";
    }

    private static int countStaticReachedMainWaypoints(WaypointGroup group) {
        int count = 0;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i)) continue;
            if (group.isStaticWaypointReached(i)) count++;
        }
        return count;
    }

    private static String formatPercent(double percent) {
        double safePercent = Double.isFinite(percent) ? percent : 0.0;
        return String.format(Locale.ROOT, "%.1f%%", safePercent);
    }

}
