package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonDetectionMetadataTest {

    @Test
    void geometryConstructorMarksRoomsAsMapFallback() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of(DungeonRoom.packSegment(0, 0)));

        assertEquals(DungeonDetectionConfidence.MAP_FALLBACK, room.confidence());
    }

    @Test
    void legacyDefinitionConstructorMarksRoomsAsCoreMatched() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_TWO,
                Direction.NW,
                -72,
                -104,
                List.of(DungeonRoom.packSegment(-72, -104), DungeonRoom.packSegment(-40, -104)),
                "long-hall",
                "Long Hall");

        assertEquals(DungeonDetectionConfidence.CORE_MATCHED, room.confidence());
    }

    @Test
    void withDefinitionPreservesExistingConfidence() {
        DungeonRoom fallback = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of(DungeonRoom.packSegment(0, 0)));

        DungeonRoom named = fallback.withDefinition("generic-room", "Generic Room");

        assertEquals(DungeonDetectionConfidence.MAP_FALLBACK, named.confidence());
    }

    @Test
    void nullExplicitConfidenceNormalizesToUnknown() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.UNKNOWN,
                DungeonRoomShape.UNKNOWN,
                Direction.NW,
                0,
                0,
                List.of(),
                "",
                "",
                null);

        assertEquals(DungeonDetectionConfidence.UNKNOWN, room.confidence());
    }

    @Test
    void classifySegmentsDeduplicatesPackedCoordinates() {
        DungeonRoomShape shape = DungeonRoomShape.classifySegments(List.of(
                DungeonRoom.packSegment(-32, 64),
                DungeonRoom.packSegment(0, 64),
                DungeonRoom.packSegment(-32, 64)));

        assertEquals(DungeonRoomShape.ONE_BY_TWO, shape);
    }

    @Test
    void coreSignatureClampsMetadataButPreservesHash() {
        DungeonCoreSignature signature = new DungeonCoreSignature(-123, -4, -9);

        assertEquals(-123, signature.hash());
        assertEquals(0, signature.topY());
        assertEquals(0, signature.sampleCount());
    }
}
