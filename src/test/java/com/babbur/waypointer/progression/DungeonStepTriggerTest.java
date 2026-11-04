package com.babbur.waypointer.progression;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonStepTriggerTest {

    private static final int SUB = Waypoint.FLAG_SUBWAYPOINT;
    private static final int ETHERWARP = Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP;
    private static final int MINE = Waypoint.FLAG_SKIP_ON_MINE;
    private static final int CHEST = Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET;
    private static final int ANY = Waypoint.FLAG_STEP_ANY_ORDER;

    private static WaypointGroup route(int... flags) {
        WaypointGroup group = WaypointGroup.create("Room", "test-room");
        group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        group.setSkipAheadEnabled(false);
        group.setVisibleMainSteps(1);
        for (int i = 0; i < flags.length; i++) {
            group.add(new Waypoint(i * 4, 70, 0, "", Waypoint.DEFAULT_COLOR, flags[i], 0.0));
        }
        group.resetProgress();
        return group;
    }

    private static final class Blocks implements ProximityTracker.MineBlockLookup {
        final Map<Integer, Integer> states = new HashMap<>();

        @Override
        public int stateAt(int x, int y, int z) {
            return states.getOrDefault(x, ProximityTracker.MINE_BLOCK_PRESENT);
        }
    }

    @Test
    void stonkBlocksMinedInAnyOrderAllCount() {
        WaypointGroup group = route(ETHERWARP, MINE | SUB | ANY, MINE | SUB | ANY, CHEST | SUB);
        group.advancePast(0);
        Blocks blocks = new Blocks();
        Set<ProximityTracker.MineTarget> observed = new HashSet<>();
        Set<ProximityTracker.MineTarget> live = new HashSet<>();
        assertFalse(ProximityTracker.updateMinedStepProgress(group, blocks, observed, live));

        blocks.states.put(8, ProximityTracker.MINE_BLOCK_AIR);
        assertTrue(ProximityTracker.updateMinedStepProgress(group, blocks, observed, live));
        assertTrue(group.isStepMemberDone(2));
        assertEquals(3, group.currentIndex());
    }

    @Test
    void aMineBlockAlreadyGoneDoesNotHoldTheRoute() {
        WaypointGroup group = route(MINE, CHEST | SUB);
        Blocks blocks = new Blocks();
        blocks.states.put(0, ProximityTracker.MINE_BLOCK_AIR);
        assertTrue(ProximityTracker.updateMinedStepProgress(
                group, blocks, new HashSet<>(), new HashSet<>()));
        assertEquals(1, group.currentIndex());
    }

    @Test
    void miningAheadInTheStepSkipsEarlierOrderedBlocks() {
        WaypointGroup group = route(MINE, MINE | SUB, MINE | SUB, CHEST | SUB);
        Blocks blocks = new Blocks();
        blocks.states.put(8, ProximityTracker.MINE_BLOCK_AIR);
        assertTrue(ProximityTracker.updateMinedStepProgress(
                group, blocks, new HashSet<>(), new HashSet<>()));
        assertEquals(3, group.currentIndex());
    }

    @Test
    void openingTheStepChestOrALaterSecretChestIsFound() {
        WaypointGroup group = route(ETHERWARP, CHEST | SUB, ETHERWARP, CHEST | SUB, ETHERWARP);
        assertEquals(1, ProximityTracker.stepInteractedIndex(group, 4, 70, 0, false));
        assertEquals(3, ProximityTracker.stepInteractedIndex(group, 12, 70, 0, false));
        assertEquals(-1, ProximityTracker.stepInteractedIndex(group, 16, 70, 0, false));
    }

    @Test
    void superboomWallsOnlyCountWithSuperboomInHand() {
        int superboom = Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SUPERBOOM;
        WaypointGroup group = route(ETHERWARP, superboom | SUB, CHEST | SUB);
        assertEquals(-1, ProximityTracker.stepInteractedIndex(group, 4, 70, 0, false));
        assertEquals(1, ProximityTracker.stepInteractedIndex(group, 4, 70, 0, true));
    }

    @Test
    void secretsPassedCountsEarlySecretsForTheCompletionSound() {
        WaypointGroup group = route(ETHERWARP, CHEST | SUB, ETHERWARP, CHEST | SUB);
        assertEquals(0, ProximityTracker.secretsPassed(group));
        group.markStepMemberDone(3);
        assertEquals(1, ProximityTracker.secretsPassed(group));
        group.advancePast(1);
        assertEquals(2, ProximityTracker.secretsPassed(group));
    }
}
