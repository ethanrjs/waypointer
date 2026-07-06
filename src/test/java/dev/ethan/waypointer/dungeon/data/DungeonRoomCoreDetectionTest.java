package dev.ethan.waypointer.dungeon.data;

import dev.ethan.waypointer.dungeon.Direction;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomCoreDetectionTest {

    @BeforeEach
    @AfterEach
    void clearCustomData() {
        DungeonRoomData.clearAllCustom();
    }

    @Test
    void jsonRoundTripsCoreHashes() {
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "core-room",
                "Core Room",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(123, -456),
                List.of(),
                List.of());

        Map<String, DungeonRoomDefinition> parsed =
                DungeonRoomData.parseDefinitions(DungeonRoomData.toJson(List.of(definition)));

        assertEquals(List.of(123, -456), parsed.get("core-room").coreHashes());
    }

    @Test
    void oldJsonWithoutCoreHashesLoadsWithEmptyCoreHashes() {
        String json = """
                {
                  "schema": 1,
                  "rooms": [
                    {
                      "id": "old-room",
                      "name": "Old Room",
                      "type": "ROOM",
                      "shape": "ONE_BY_ONE",
                      "fingerprints": [],
                      "waypoints": []
                    }
                  ]
                }
                """;

        DungeonRoomDefinition definition = DungeonRoomData.parseDefinitions(json).get("old-room");

        assertTrue(definition.coreHashes().isEmpty());
    }

    @Test
    void coreHashMatchNamesRoomBeforeShapeFallback() {
        DungeonRoom room = roomAt(DungeonRoomType.UNKNOWN, DungeonRoomShape.UNKNOWN);
        DungeonRoomDefinition first = DungeonRoomData.defineRoom("first-room", "First", room);
        DungeonRoomDefinition second = DungeonRoomData.defineRoom("second-room", "Second", room);
        DungeonRoomData.addCoreHash(first.id(), 111);
        DungeonRoomData.addCoreHash(second.id(), 222);

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(room, null, new FixedCoreHashLookup(List.of(222)));

        assertEquals("second-room", matched.roomId());
        assertEquals("Second", matched.displayName());
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

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(
                room,
                null,
                new FixedCoreHashLookup(List.of(587195362, Integer.MIN_VALUE)));

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

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(
                room,
                null,
                new FixedCoreHashLookup(List.of(-318865360, 136252599, 419670099)));

        assertEquals("slime", matched.roomId());
        assertEquals("Slime", matched.displayName());
    }

    @Test
    void definitionForCoreHashFindsBundledLongHall() {
        DungeonRoomDefinition definition = DungeonRoomData.definitionForCoreHash(587195362);

        assertEquals("long-hall", definition.id());
        assertEquals("Long Hall", definition.displayName());
    }

    @Test
    void definitionForCoreHashDoesNotFindRemovedAltarMisScan() {
        DungeonRoomDefinition definition = DungeonRoomData.definitionForCoreHash(-318865360);

        assertNull(definition);
    }

    @Test
    void ambiguousCoreHashMatchesDoNotFallBackToShapeOnlyMatch() {
        DungeonRoom room = roomAt(DungeonRoomType.UNKNOWN, DungeonRoomShape.UNKNOWN);
        DungeonRoomDefinition first = DungeonRoomData.defineRoom("first-room", "First", room);
        DungeonRoomDefinition second = DungeonRoomData.defineRoom("second-room", "Second", room);
        DungeonRoomData.addCoreHash(first.id(), 333);
        DungeonRoomData.addCoreHash(second.id(), 333);

        assertNull(DungeonRoomData.match(room, null, new FixedCoreHashLookup(List.of(333))));
    }

    @Test
    void unmatchedCoreHashDoesNotUseGenericShapeFallbackWhenCatalogHasCoreCandidates() {
        DungeonRoom room = roomAt(DungeonRoomType.ROOM, DungeonRoomShape.ONE_BY_ONE);

        assertNull(DungeonRoomData.match(room, null, new FixedCoreHashLookup(List.of(Integer.MIN_VALUE))));
    }

    @Test
    void customOverrideByBundledIdInheritsBundledCoreHashes() {
        DungeonRoom room = roomAt(DungeonRoomType.ROOM, DungeonRoomShape.ONE_BY_ONE);
        DungeonRoomDefinition customAdmin = DungeonRoomData.defineRoom("admin", "Custom Admin", room);
        DungeonRoomData.addWaypoint(customAdmin.id(), DungeonWaypoint.plain(
                "custom-admin-secret",
                dev.ethan.waypointer.dungeon.DungeonSecretCategory.CHEST,
                16,
                70,
                16,
                "Custom admin secret"));

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(
                room,
                null,
                new FixedCoreHashLookup(List.of(518379920)));

        assertEquals("admin", matched.roomId());
        assertEquals("Custom Admin", matched.displayName());
        assertEquals(1, DungeonRoomData.waypointsFor(matched).size());
    }

    @Test
    void bundledOdinCatalogMapsRepresentativeRoomTypesAndShapes() {
        DungeonRoomDefinition admin = DungeonRoomData.definition("admin");
        DungeonRoomDefinition altar = DungeonRoomData.definition("altar");
        DungeonRoomDefinition kingMidas = DungeonRoomData.definition("king-midas");

        assertEquals(DungeonRoomType.ROOM, admin.type());
        assertEquals(DungeonRoomShape.ONE_BY_ONE, admin.shape());
        assertTrue(admin.coreHashes().contains(518379920));
        assertEquals(DungeonRoomShape.L_SHAPE, altar.shape());
        assertEquals(DungeonRoomType.MINIBOSS, kingMidas.type());
    }

    private static DungeonRoom roomAt(DungeonRoomType type, DungeonRoomShape shape) {
        return new DungeonRoom(
                type,
                shape,
                Direction.NW,
                -8,
                24,
                List.of(DungeonRoom.packSegment(-8, 24)));
    }

    private record FixedCoreHashLookup(List<Integer> coreHashes) implements DungeonRoomData.CoreHashLookup {

        @Override
        public List<Integer> coreHashesFor(DungeonRoom room) {
            return coreHashes;
        }
    }
}
