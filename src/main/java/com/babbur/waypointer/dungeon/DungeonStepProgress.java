package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.function.IntPredicate;

/**
 * Step-aware completion for dungeon room routes. A step is a main waypoint and its
 * subwaypoints. While a step is current, any of its remaining actions can be done:
 * a later ordered action skips ahead to it, an any-order action is simply ticked off,
 * and a secret from a later step is remembered so that step is skipped when reached.
 */
public final class DungeonStepProgress {

    private DungeonStepProgress() {}

    /** Whether events on this route use step rules rather than the plain route cursor. */
    public static boolean appliesTo(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.usesDungeonSteps()
                && group.visibleStepCount() > 0
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && !group.isComplete();
    }

    /**
     * Calls {@code visitor} for each waypoint an event may complete, nearest first: the
     * cursor, the rest of the current step, then (when {@code laterSecrets}) secrets of
     * later steps. Stops when the visitor returns {@code false}.
     */
    public static void forEachCandidate(WaypointGroup group, boolean laterSecrets,
                                        IntPredicate visitor) {
        if (group == null || visitor == null || group.isComplete()) return;
        int cursor = group.currentIndex();
        int start = group.currentStepStart();
        if (start < 0) return;
        int end = group.childEndExclusive(start);
        if (isCandidate(group, cursor) && !visitor.test(cursor)) return;
        for (int i = start; i < end; i++) {
            if (i == cursor || !isCandidate(group, i)) continue;
            if (i < cursor && !group.isAnyOrderStepMember(i)) continue;
            if (!visitor.test(i)) return;
        }
        if (!laterSecrets) return;
        for (int i = end; i < group.size(); i++) {
            if (!isCandidate(group, i)) continue;
            if (!group.get(i).hasFlag(Waypoint.FLAG_DUNGEON_SECRET)) continue;
            if (!visitor.test(i)) return;
        }
    }

    /** The first candidate accepted by {@code matches}, or -1. */
    public static int findCandidate(WaypointGroup group, boolean laterSecrets,
                                    IntPredicate matches) {
        int[] found = { -1 };
        forEachCandidate(group, laterSecrets, i -> {
            if (!matches.test(i)) return true;
            found[0] = i;
            return false;
        });
        return found[0];
    }

    /**
     * Completes one candidate. Returns true when progress changed. A later ordered action
     * in the current step moves the cursor past it; anything else is remembered as done.
     */
    public static boolean complete(WaypointGroup group, int index) {
        if (group == null || index < 0 || index >= group.size()) return false;
        int cursor = group.currentIndex();
        int start = group.currentStepStart();
        int end = start < 0 ? start : group.childEndExclusive(start);
        boolean inStep = start >= 0 && index >= start && index < end;
        if (index == cursor || inStep && index > cursor && !group.isAnyOrderStepMember(index)) {
            group.advancePast(index);
            return true;
        }
        return group.markStepMemberDone(index);
    }

    private static boolean isCandidate(WaypointGroup group, int index) {
        if (index < 0 || index >= group.size()) return false;
        if (!group.isWaypointEnabled(index) || group.isStepMemberDone(index)) return false;
        Waypoint waypoint = group.get(index);
        return (waypoint.flags() & Waypoint.DUNGEON_COMPLETION_FLAGS) != 0
                && !waypoint.hasFlag(Waypoint.FLAG_DUNGEON_PEARL_TARGET);
    }
}
