package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomCoreScannerTest {

    @Test
    void extractsHashesOnlyWhenEverySegmentHasAUsableSignature() {
        assertEquals(
                List.of(123, -456),
                DungeonRoomCoreScanner.coreHashesFromSignatures(List.of(
                        new DungeonCoreSignature(123, 100, 120),
                        new DungeonCoreSignature(-456, 98, 118))));

        assertTrue(DungeonRoomCoreScanner.coreHashesFromSignatures(List.of(
                new DungeonCoreSignature(123, 100, 120),
                DungeonCoreSignature.UNKNOWN)).isEmpty());
        assertTrue(DungeonRoomCoreScanner.coreHashesFromSignatures(List.of(
                new DungeonCoreSignature(123, 0, 120))).isEmpty());
    }
}
