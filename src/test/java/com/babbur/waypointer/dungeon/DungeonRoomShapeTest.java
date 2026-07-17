package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonRoomShapeTest {

    @Test
    void classify_treatsEmptyAndSingleSegmentAsOneByOne() {
        assertEquals(DungeonRoomShape.ONE_BY_ONE, DungeonRoomShape.classify(0, 0, 0));
        assertEquals(DungeonRoomShape.ONE_BY_ONE, DungeonRoomShape.classify(1, 1, 1));
    }

    @Test
    void classify_twoSegmentsAsOneByTwoRegardlessOfAxis() {
        assertEquals(DungeonRoomShape.ONE_BY_TWO, DungeonRoomShape.classify(2, 1, 2));
        assertEquals(DungeonRoomShape.ONE_BY_TWO, DungeonRoomShape.classify(2, 2, 1));
    }

    @Test
    void classify_threeSegmentsDistinguishesLineFromElbow() {
        assertEquals(DungeonRoomShape.ONE_BY_THREE, DungeonRoomShape.classify(3, 1, 3));
        assertEquals(DungeonRoomShape.ONE_BY_THREE, DungeonRoomShape.classify(3, 3, 1));
        assertEquals(DungeonRoomShape.L_SHAPE, DungeonRoomShape.classify(3, 2, 2));
    }

    @Test
    void classify_fourSegmentsDistinguishesLineSquareAndElbow() {
        assertEquals(DungeonRoomShape.ONE_BY_FOUR, DungeonRoomShape.classify(4, 1, 4));
        assertEquals(DungeonRoomShape.ONE_BY_FOUR, DungeonRoomShape.classify(4, 4, 1));
        assertEquals(DungeonRoomShape.TWO_BY_TWO, DungeonRoomShape.classify(4, 2, 2));
        assertEquals(DungeonRoomShape.L_SHAPE, DungeonRoomShape.classify(4, 2, 3));
    }

    @Test
    void classify_unknownForLargerOrImpossibleFootprints() {
        assertEquals(DungeonRoomShape.UNKNOWN, DungeonRoomShape.classify(5, 2, 3));
        assertEquals(DungeonRoomShape.UNKNOWN, DungeonRoomShape.classify(9, 3, 3));
    }
}
