package com.babbur.waypointer.core;

import java.util.ArrayList;
import java.util.List;

/** One batch appearance edit; undo skips changed or ambiguous points and preserves route colors. */
public final class WaypointAppearanceUndo {
    private record Change(Waypoint before, Waypoint after) {}
    private final List<Change> changes;
    private WaypointAppearanceUndo(List<Change> changes) { this.changes = List.copyOf(changes); }
    public static WaypointAppearanceUndo between(List<Waypoint> before, List<Waypoint> after) {
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            Waypoint old = before.get(i), next = after.get(i);
            if (!old.styleOverride().equals(next.styleOverride()) && old.withStyleOverride(next.styleOverride()).equals(next)) changes.add(new Change(old, next));
        }
        return new WaypointAppearanceUndo(changes);
    }
    public boolean isEmpty() { return changes.isEmpty(); }
    public int undo(WaypointGroup group) {
        int restored = 0;
        for (Change change : changes) {
            int match = -1;
            for (int i = 0; i < group.size(); i++) {
                Waypoint candidate = group.get(i);
                boolean unchanged = group.gradientMode() == WaypointGroup.GradientMode.MANUAL
                        ? change.after.equals(candidate) : change.after.withColor(candidate.color()).equals(candidate);
                if (!unchanged) continue;
                if (match >= 0) { match = -2; break; }
                match = i;
            }
            if (match >= 0) {
                group.setWaypointStyle(match, change.before.styleOverride());
                restored++;
            }
        }
        return restored;
    }
}
