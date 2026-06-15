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
    /*[[AI-FN-DOC
Function:
DungeonRoomCoreDetectionTest.coreHashMatchUsesCurrentSegmentBeforeShapeWhenMapOverMergesRoom
Purpose:
Verify that a known room core can name the current room even when the map-derived footprint shape says the room is larger than the catalog definition.
Why this exists:
The reported dungeon snapshot showed Long Hall detected as a 1x2 unmatched room, and the matcher must prefer Devonian-style core identity over brittle shape gating.
When to use:
Run as part of the dungeon room data test suite whenever room-core matching behavior changes.
Inputs:
No external inputs; the test constructs a synthetic two-segment normal room and supplies the bundled Long Hall core hash as the first observed hash.
Outputs:
Passes when the synthetic room resolves to the bundled long-hall definition and fails if shape filtering blocks the core match.
Side effects:
Reads the static bundled room catalog; does not mutate custom definitions or write files.
Failure modes:
Fails if the Long Hall bundled core hash changes without updating the fixture, if core matching is shape-gated again, or if primary segment priority is removed.
Important invariants:
The first observed hash represents the segment the player is standing in; the second hash represents an adjacent over-merged segment and must not prevent a unique primary match.
Internal logic:
Build a ONE_BY_TWO DungeonRoom with two packed segments, ask the matcher to resolve it with observed hashes [Long Hall, bogus], then assert the id and display name.
Pseudocode:
create a normal ONE_BY_TWO room with two segments
create a fixed lookup returning Long Hall's bundled core first and an unmatched hash second
call withMatchedDefinition with no block lookup and the fixed core lookup
assert the matched room id is long-hall
assert the matched display name is Long Hall
Implementation notes:
The fixture intentionally uses the existing bundled catalog instead of copying Devonian's GPL room data.
AI self-check:
Verify this test reproduces the snapshot's shape mismatch, exercises current-segment priority, and has no hidden dependency on custom room state.
]]*/
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
    /*[[AI-FN-DOC
Function:
DungeonRoomCoreDetectionTest.definitionForCoreHashFindsBundledLongHall
Purpose:
Verify that the direct core-hash lookup used by instant room detection resolves a known bundled room hash.
Why this exists:
DungeonStateTracker now starts from the current segment core instead of map shape, so the catalog needs a tested direct hash-to-definition boundary.
When to use:
Run with the dungeon room data tests whenever core catalog parsing or instant detection lookup behavior changes.
Inputs:
No external inputs; the test uses the bundled Long Hall core hash from the Waypointer catalog.
Outputs:
Passes when definitionForCoreHash returns the Long Hall definition for hash 587195362.
Side effects:
Reads the static bundled room catalog; does not mutate custom definitions or write files.
Failure modes:
Fails if the bundled hash is removed, if core hash parsing breaks, or if direct lookup becomes shape-gated.
Important invariants:
The lookup must not require a live DungeonRoom shape because instant detection only has a segment hash at this stage.
Internal logic:
Call definitionForCoreHash with the Long Hall hash, then assert id and display name.
Pseudocode:
definition = DungeonRoomData.definitionForCoreHash(587195362)
assert definition id equals long-hall
assert definition displayName equals Long Hall
Implementation notes:
This test deliberately exercises the bundled catalog rather than generated custom data so it protects the runtime path the user hit.
AI self-check:
Verify the test is deterministic, uses no client classes, and catches accidental removal of direct core lookup behavior.
]]*/
    void definitionForCoreHashFindsBundledLongHall() {
        DungeonRoomDefinition definition = DungeonRoomData.definitionForCoreHash(587195362);

        assertEquals("long-hall", definition.id());
        assertEquals("Long Hall", definition.displayName());
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
        /*[[AI-FN-DOC
Function:
DungeonRoomCoreDetectionTest.FixedCoreHashLookup.coreHashesFor
Purpose:
Return deterministic test core hashes for a detected room without reading a Minecraft client world.
Why this exists:
Room matching tests need to exercise core-hash logic in pure JVM tests, where no ClientLevel-backed DungeonRoomCoreScanner is available.
When to use:
Use this test double when a test needs exact observed core hashes; do not use it in production code or tests that should validate real block scanning.
Inputs:
room is the DungeonRoom passed by the matcher and may be any value; this test double ignores it because each test preconfigures the desired hash sequence.
Outputs:
Returns the immutable list of configured integer core hashes in priority order.
Side effects:
None.
Failure modes:
No runtime failure is expected as long as the record was constructed with a non-null list; null construction would naturally throw when callers use the returned value.
Important invariants:
The returned order must be preserved because the matcher treats the first hash as the current/player segment.
Internal logic:
Return the record component directly.
Pseudocode:
return coreHashes
Implementation notes:
Keeping the test double as a record makes each test's supplied hash sequence visible at the call site.
AI self-check:
Verify this method is pure, preserves ordering, and does not inspect or mutate the DungeonRoom argument.
]]*/
        public List<Integer> coreHashesFor(DungeonRoom room) {
            return coreHashes;
        }
    }
}
