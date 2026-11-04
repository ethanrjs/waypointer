package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonRoomTimerTest {

    @Test
    void timesFromFirstSightToFinishAndReportsOnce() {
        DungeonRoomTimer.Clock clock = new DungeonRoomTimer.Clock();
        assertEquals(0, clock.observe("room", false, 1_000));
        assertEquals(0, clock.observe("room", false, 5_000));
        assertEquals(11_500, clock.observe("room", true, 12_500));
        assertEquals(0, clock.observe("room", true, 13_000));
    }

    @Test
    void aRouteAlreadyFinishedWhenSeenIsNotTimed() {
        DungeonRoomTimer.Clock clock = new DungeonRoomTimer.Clock();
        assertEquals(0, clock.observe("room", true, 1_000));
    }

    @Test
    void restartingAFinishedRouteStartsANewClock() {
        DungeonRoomTimer.Clock clock = new DungeonRoomTimer.Clock();
        clock.observe("room", false, 0);
        clock.observe("room", true, 4_000);
        clock.observe("room", false, 10_000);
        assertEquals(3_000, clock.observe("room", true, 13_000));
    }

    @Test
    void leavingTheDungeonForgetsRunningClocks() {
        DungeonRoomTimer.Clock clock = new DungeonRoomTimer.Clock();
        clock.observe("room", false, 0);
        clock.clear();
        clock.observe("room", false, 50_000);
        assertEquals(1_000, clock.observe("room", true, 51_000));
    }

    @Test
    void secondsUseOneDecimal() {
        assertEquals("12.4s", DungeonRoomTimer.seconds(12_440));
        assertEquals("0.1s", DungeonRoomTimer.seconds(90));
    }
}
