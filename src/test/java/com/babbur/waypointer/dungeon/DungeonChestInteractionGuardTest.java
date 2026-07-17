package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonChestInteractionGuardTest {

    @Test
    void lockedMessageCancelsActionsThroughTickFive() {
        DungeonChestInteractionGuard guard = new DungeonChestInteractionGuard();
        AtomicInteger commits = new AtomicInteger();
        Object level = new Object();
        guard.deferForLevel(level, commits::incrementAndGet);
        guard.deferForLevel(level, commits::incrementAndGet);

        for (int i = 0; i < 5; i++) guard.advanceTick();

        assertEquals(0, commits.get());
        assertTrue(guard.cancelLockedActions("  THAT CHEST IS LOCKED!  "));
        guard.advanceTick();
        assertEquals(0, commits.get());
        assertEquals(0, guard.pendingCount());
    }

    @Test
    void actionCommitsOnlyAfterFiveTickWindowExpires() {
        DungeonChestInteractionGuard guard = new DungeonChestInteractionGuard();
        AtomicInteger commits = new AtomicInteger();
        guard.deferForLevel(new Object(), commits::incrementAndGet);

        for (int i = 0; i < 5; i++) guard.advanceTick();
        assertEquals(0, commits.get());

        guard.advanceTick();
        assertEquals(1, commits.get());
        assertFalse(guard.cancelLockedActions("That chest is locked!"));
    }

    @Test
    void changingLevelsClearsPendingActionsBeforeNewOnesAreStaged() {
        DungeonChestInteractionGuard guard = new DungeonChestInteractionGuard();
        AtomicInteger commits = new AtomicInteger();
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        guard.deferForLevel(firstLevel, commits::incrementAndGet);

        guard.deferForLevel(secondLevel, commits::incrementAndGet);
        for (int i = 0; i < 6; i++) guard.advanceTick();

        assertEquals(1, commits.get());
        assertEquals(0, guard.pendingCount());
    }

    @Test
    void lockMessageMatchIsExactApartFromCaseAndWhitespace() {
        assertTrue(DungeonChestInteractionGuard.isLockedChestMessage("That chest is locked!"));
        assertTrue(DungeonChestInteractionGuard.isLockedChestMessage(" that CHEST is LOCKED! "));
        assertFalse(DungeonChestInteractionGuard.isLockedChestMessage("That chest is locked"));
        assertFalse(DungeonChestInteractionGuard.isLockedChestMessage("[NPC] That chest is locked!"));
        assertFalse(DungeonChestInteractionGuard.isLockedChestMessage(null));
    }
}
