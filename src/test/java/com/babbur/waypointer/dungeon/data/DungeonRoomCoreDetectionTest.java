package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomCoreDetectionTest {

    @Test
    void parsesWaypointFreeCatalogMetadata() {
        Map<String, DungeonRoomCatalogEntry> entries = DungeonRoomData.parseEntries("""
                {"rooms":[{"id":"core-room","name":"Core Room","type":"ROOM",
                "shape":"ONE_BY_ONE","coreHashes":[123,-456],"fingerprints":[],
                "waypoints":[{"id":"legacy-route-data-is-ignored"}]}]}
                """);

        DungeonRoomCatalogEntry entry = entries.get("core-room");
        assertEquals(List.of(123, -456), entry.coreHashes());
        assertEquals(DungeonRoomShape.ONE_BY_ONE, entry.shape());
    }

    @Test
    void coreHashMatchUsesCurrentSegmentBeforeShapeWhenMapOverMergesRoom() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_TWO,
                Direction.NW,
                -72,
                -104,
                List.of(DungeonRoom.packSegment(-72, -104), DungeonRoom.packSegment(-40, -104)));

        DungeonRoom matched = DungeonRoomData.withMatchedEntry(
                room, null, ignored -> List.of(587195362, Integer.MIN_VALUE));

        assertEquals("long-hall", matched.roomId());
        assertEquals("Long Hall", matched.displayName());
    }

    @Test
    void coreHashMatchPrefersShapeMatchWhenCurrentSegmentHashBelongsToNeighbor() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_THREE,
                Direction.NW,
                -168,
                -200,
                List.of(
                        DungeonRoom.packSegment(-168, -200),
                        DungeonRoom.packSegment(-136, -200),
                        DungeonRoom.packSegment(-104, -200)));

        DungeonRoom matched = DungeonRoomData.withMatchedEntry(
                room, null, ignored -> List.of(-318865360, 136252599, 419670099));

        assertEquals("slime", matched.roomId());
        assertEquals("Slime", matched.displayName());
    }

    @Test
    void entryForCoreHashFindsBundledLongHall() {
        DungeonRoomCatalogEntry entry = DungeonRoomData.entryForCoreHash(587195362);

        assertEquals("long-hall", entry.id());
        assertEquals("Long Hall", entry.displayName());
    }

    @Test
    void entryForCoreHashRejectsAmbiguousOrRemovedHash() {
        assertNull(DungeonRoomData.entryForCoreHash(-318865360));
    }

    @Test
    void unmatchedCoreHashDoesNotUseGenericShapeFallbackWhenCatalogHasCoreCandidates() {
        DungeonRoom room = roomAt(DungeonRoomType.ROOM, DungeonRoomShape.ONE_BY_ONE);

        assertNull(DungeonRoomData.match(room, null, ignored -> List.of(Integer.MIN_VALUE)));
    }

    @Test
    void bundledOdinCatalogMapsRepresentativeRoomTypesAndShapes() {
        DungeonRoomCatalogEntry admin = DungeonRoomData.entry("admin");
        DungeonRoomCatalogEntry altar = DungeonRoomData.entry("altar");
        DungeonRoomCatalogEntry kingMidas = DungeonRoomData.entry("king-midas");

        assertEquals(DungeonRoomType.ROOM, admin.type());
        assertEquals(DungeonRoomShape.ONE_BY_ONE, admin.shape());
        assertTrue(admin.coreHashes().contains(518379920));
        assertEquals(DungeonRoomShape.L_SHAPE, altar.shape());
        assertEquals(DungeonRoomType.MINIBOSS, kingMidas.type());
    }

    private static DungeonRoom roomAt(DungeonRoomType type, DungeonRoomShape shape) {
        return new DungeonRoom(
                type, shape, Direction.NW, -8, 24,
                List.of(DungeonRoom.packSegment(-8, 24)));
    }
}
