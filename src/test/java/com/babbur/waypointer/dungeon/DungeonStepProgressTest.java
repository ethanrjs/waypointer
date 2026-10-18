package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonStepProgressTest {

    private static final int SUB = Waypoint.FLAG_SUBWAYPOINT;
    private static final int ETHERWARP = Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP;
    private static final int MINE = Waypoint.FLAG_SKIP_ON_MINE;
    private static final int CHEST = Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET;
    private static final int ANY = Waypoint.FLAG_STEP_ANY_ORDER;
    private static final int GUIDE = SUB | Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_HIDE_NAME;

    private static WaypointGroup room() {
        return route(
                ETHERWARP,
                MINE | SUB | ANY,
                MINE | SUB | ANY,
                CHEST | SUB,
                ETHERWARP,
                CHEST | SUB,
                CHEST);
    }

    private static WaypointGroup route(int... flags) {
        WaypointGroup group = WaypointGroup.create("Room", "test-room");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setSkipAheadEnabled(false);
        group.setVisibleMainSteps(1);
        for (int i = 0; i < flags.length; i++) {
            group.add(new Waypoint(i * 4, 70, 0, "", Waypoint.DEFAULT_COLOR, flags[i], 0.0));
        }
        group.resetProgress();
        return group;
    }

    private static List<Integer> visible(WaypointGroup group) {
        List<Integer> out = new ArrayList<>();
        group.forEachVisibleIndex(out::add);
        out.sort(Integer::compare);
        return out;
    }

    @Test
    void theWholeCurrentStepIsShownWithItsSecret() {
        WaypointGroup group = room();
        assertEquals(List.of(0, 1, 2, 3), visible(group));
        group.advancePast(0);
        assertEquals(3, group.currentIndex(), "the cursor passes over any-order actions");
        assertEquals(List.of(1, 2, 3), visible(group));
    }

    @Test
    void anyOrderActionsAreTickedOffWithoutMovingTheCursor() {
        WaypointGroup group = room();
        group.advancePast(0);
        assertTrue(DungeonStepProgress.complete(group, 2));
        assertEquals(3, group.currentIndex());
        assertEquals(List.of(1, 3), visible(group));
        assertFalse(DungeonStepProgress.complete(group, 2), "already done");
    }

    @Test
    void theStepSecretCompletesTheStepFromItsFirstAction() {
        WaypointGroup group = room();
        assertEquals(0, group.currentIndex());
        assertTrue(DungeonStepProgress.complete(group, 3));
        assertEquals(4, group.currentIndex(), "doing the secret skips the etherwarp before it");
    }

    @Test
    void aLaterSecretDoneEarlySkipsItsWholeStep() {
        WaypointGroup group = room();
        assertTrue(DungeonStepProgress.complete(group, 5));
        assertEquals(0, group.currentIndex(), "the current step is unchanged");
        assertTrue(group.isStepMemberDone(5));
        DungeonStepProgress.complete(group, 3);
        assertEquals(6, group.currentIndex(), "step 2 is skipped, its secret is already done");
    }

    @Test
    void aLaterSingleSecretStepIsSkippedToo() {
        WaypointGroup group = room();
        DungeonStepProgress.complete(group, 6);
        DungeonStepProgress.complete(group, 3);
        DungeonStepProgress.complete(group, 5);
        assertTrue(group.isComplete());
    }

    @Test
    void candidatesStartWithTheCursorAndOnlyReachLaterStepsForSecrets() {
        WaypointGroup group = room();
        List<Integer> stepOnly = new ArrayList<>();
        DungeonStepProgress.forEachCandidate(group, false, i -> stepOnly.add(i) || true);
        assertEquals(List.of(0, 1, 2, 3), stepOnly);

        List<Integer> withSecrets = new ArrayList<>();
        DungeonStepProgress.forEachCandidate(group, true, i -> withSecrets.add(i) || true);
        assertEquals(List.of(0, 1, 2, 3, 5, 6), withSecrets, "later etherwarps never count");
    }

    @Test
    void jumpingBackRedoesLaterSecrets() {
        WaypointGroup group = room();
        DungeonStepProgress.complete(group, 3);
        DungeonStepProgress.complete(group, 6);
        group.setCurrentIndex(0);
        assertFalse(group.isStepMemberDone(6));
    }

    @Test
    void doneMarksResetWithProgress() {
        WaypointGroup group = room();
        DungeonStepProgress.complete(group, 5);
        group.resetProgress();
        assertFalse(group.isStepMemberDone(5));
    }

    @Test
    void doneMarksSurviveARebuiltRoomCopy() {
        WaypointGroup previous = room();
        previous.advancePast(0);
        DungeonStepProgress.complete(previous, 1);
        WaypointGroup next = room();
        next.setCurrentIndex(0);
        DungeonRoomRouteSync.carryOverProgress(previous, next);
        assertTrue(next.isStepMemberDone(1));
        assertEquals(3, next.currentIndex());
    }

    @Test
    void stepRulesOnlyApplyToSequencedRoomRoutes() {
        WaypointGroup group = room();
        assertTrue(DungeonStepProgress.appliesTo(group));
        group.setVisibleMainSteps(0);
        assertFalse(DungeonStepProgress.appliesTo(group));
        WaypointGroup regular = WaypointGroup.create("Hub", "hub");
        regular.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, CHEST, 0.0));
        assertFalse(DungeonStepProgress.appliesTo(regular));
    }

    @Test
    void pathPointsAreNotShownAsBoxes() {
        WaypointGroup group = route(ETHERWARP, CHEST | SUB, GUIDE, GUIDE, GUIDE, ETHERWARP);
        assertEquals(List.of(0, 1), visible(group));
    }

    @Test
    void editingShowsTheWholeRouteEvenWhenFinished() {
        WaypointGroup group = room();
        group.setShowAllWaypoints(true);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), visible(group));
        group.setCurrentTargetIndex(group.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), visible(group));

        com.babbur.waypointer.core.ActiveGroupManager manager =
                new com.babbur.waypointer.core.ActiveGroupManager();
        WaypointGroup mirror = new WaypointGroup("dungeon:auto:test-room", "Room", "test-room");
        mirror.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        mirror.setRuntimeOnly(true);
        mirror.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, CHEST, 0.0));
        mirror.setCurrentTargetIndex(1);
        manager.add(mirror);
        manager.onZoneChanged(new com.babbur.waypointer.core.Zone("test-room", "Room"));
        assertEquals(List.of(), manager.activeGroups(), "a finished room hides");
        mirror.setShowAllWaypoints(true);
        manager.fireTransientDataChanged();
        assertEquals(List.of(mirror), manager.activeGroups(), "but not while editing");
    }

    @Test
    void floorRoutesMadeOfActionsGoStepByStepAndClearWhenDone() {
        WaypointGroup boss = WaypointGroup.create("Boss", "dungeon_f7");
        boss.setSkipAheadEnabled(false);
        boss.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, MINE, 0.0));
        boss.add(new Waypoint(4, 70, 0, "", Waypoint.DEFAULT_COLOR, MINE | SUB | ANY, 0.0));
        boss.add(new Waypoint(8, 70, 0, "", Waypoint.DEFAULT_COLOR, ETHERWARP | SUB, 0.0));
        boss.add(new Waypoint(12, 70, 0, "", Waypoint.DEFAULT_COLOR, GUIDE, 0.0));
        assertTrue(boss.usesDungeonSteps());
        assertTrue(DungeonStepProgress.appliesTo(boss));
        assertEquals(List.of(0, 1, 2), visible(boss));
        DungeonStepProgress.complete(boss, 2);
        assertTrue(boss.isComplete());
        assertEquals(List.of(), visible(boss));
    }

    @Test
    void handMadeFloorRoutesStayPlain() {
        WaypointGroup floor = WaypointGroup.create("Terminals", "dungeon_f7");
        floor.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        floor.add(new Waypoint(4, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        assertFalse(floor.usesDungeonSteps());
        assertFalse(DungeonStepProgress.appliesTo(floor));
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, MINE, 0.0));
        assertFalse(hub.usesDungeonSteps(), "only Catacombs floors");
        floor.set(1, floor.get(1).withFlags(MINE));
        assertTrue(floor.usesDungeonSteps(), "recomputed after an edit");
    }

    @Test
    void anyOrderOnAMainWaypointIsIgnored() {
        WaypointGroup group = route(ETHERWARP | ANY, CHEST | SUB);
        assertFalse(group.isAnyOrderStepMember(0));
        group.advancePast(0);
        assertEquals(1, group.currentIndex());
    }
}
