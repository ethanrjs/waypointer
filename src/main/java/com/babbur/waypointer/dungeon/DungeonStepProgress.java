package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.function.IntPredicate;

public final class DungeonStepProgress {

    private DungeonStepProgress() {}

    public static boolean appliesTo(WaypointGroup group) {
        return group != null
                && !group.temp()
                && group.usesDungeonSteps()
                && group.visibleStepCount() > 0
                && group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                && !group.isComplete();
    }

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
