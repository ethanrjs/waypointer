package dev.ethan.waypointer.dungeon.data;

import dev.ethan.waypointer.dungeon.Direction;
import dev.ethan.waypointer.dungeon.DungeonHighlight;
import dev.ethan.waypointer.dungeon.DungeonMapMath;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import dev.ethan.waypointer.dungeon.DungeonWaypointTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomDataTest {

    @BeforeEach
    @AfterEach
    void clearRuntimeData() {
        DungeonRoomData.clearAllCustom();
    }

    @Test
    void demoWaypointsCoverEveryRoomShapeWithParentAndChildren() {
        Map<DungeonRoomShape, List<DungeonWaypoint>> demos = DungeonRoomData.demoWaypoints();

        assertEquals(DungeonRoomShape.values().length, demos.size());
        for (DungeonRoomShape shape : DungeonRoomShape.values()) {
            List<DungeonWaypoint> waypoints = demos.get(shape);

            assertEquals(1, waypoints.size(), "one demo waypoint for " + shape);
            DungeonWaypoint waypoint = waypoints.get(0);
            assertEquals("demo:" + shape.name(), waypoint.id());
            assertEquals(DungeonSecretCategory.CHEST, waypoint.category());
            assertEquals(2, waypoint.highlights().size());
            assertTrue(waypoint.hasHighlights());
        }
    }

    @Test
    void demoCollectionsAreImmutable() {
        Map<DungeonRoomShape, List<DungeonWaypoint>> demos = DungeonRoomData.demoWaypoints();
        List<DungeonWaypoint> oneByOne = demos.get(DungeonRoomShape.ONE_BY_ONE);

        assertThrows(UnsupportedOperationException.class, demos::clear);
        assertThrows(UnsupportedOperationException.class, oneByOne::clear);
    }

    @Test
    void waypointsForNullOrUnknownRoomIsEmpty() {
        assertTrue(DungeonRoomData.waypointsFor(null).isEmpty());
        assertTrue(DungeonRoomData.waypointsFor(twoByTwoRoomAt(-8, 24)).isEmpty());
    }

    @Test
    void bundledCatalogDoesNotShipAuthoredRoomRoutes() {
        DungeonRoomDefinition altar = DungeonRoomData.definition("altar");
        DungeonRoom matched = roomAt(-8, 24).withDefinition(altar.id(), altar.displayName());

        assertTrue(altar.waypoints().isEmpty());
        assertTrue(DungeonRoomData.allDefinitions().stream()
                .allMatch(definition -> definition.waypoints().isEmpty()));
        assertTrue(DungeonRoomData.waypointsFor(matched).isEmpty());
    }

    @Test
    void addCustomStoresWaypointsByRoomIdentityKeyInOrder() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonWaypoint first = waypoint("first");
        DungeonWaypoint second = waypoint("second");

        DungeonRoomData.addCustom(room.identityKey(), first);
        DungeonRoomData.addCustom(room.identityKey(), second);

        assertEquals(List.of(first, second), DungeonRoomData.waypointsFor(room));
    }

    @Test
    void customBucketsAreIndependentPerRoom() {
        DungeonRoom firstRoom = roomAt(-8, 24);
        DungeonRoom secondRoom = twoByTwoRoomAt(24, 24);
        DungeonWaypoint first = waypoint("first");
        DungeonWaypoint second = waypoint("second");

        DungeonRoomDefinition firstDefinition = DungeonRoomData.defineRoom("first-room", "First", firstRoom);
        DungeonRoomDefinition secondDefinition = DungeonRoomData.defineRoom("second-room", "Second", secondRoom);
        DungeonRoomData.addWaypoint(firstDefinition.id(), first);
        DungeonRoomData.addWaypoint(secondDefinition.id(), second);

        assertEquals(List.of(first), DungeonRoomData.waypointsFor(
                firstRoom.withDefinition(firstDefinition.id(), firstDefinition.displayName())));
        assertEquals(List.of(second), DungeonRoomData.waypointsFor(
                secondRoom.withDefinition(secondDefinition.id(), secondDefinition.displayName())));
    }

    @Test
    void customWaypointListsReturnedToCallersAreImmutable() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomData.addCustom(room.identityKey(), waypoint("first"));

        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);

        assertThrows(UnsupportedOperationException.class, waypoints::clear);
    }

    @Test
    void clearCustomRemovesOnlyOneRoomBucket() {
        DungeonRoom keep = roomAt(-8, 24);
        DungeonRoom clear = twoByTwoRoomAt(24, 24);
        DungeonWaypoint keepWaypoint = waypoint("keep");

        DungeonRoomDefinition keepDefinition = DungeonRoomData.defineRoom("keep-room", "Keep", keep);
        DungeonRoomDefinition clearDefinition = DungeonRoomData.defineRoom("clear-room", "Clear", clear);
        DungeonRoomData.addWaypoint(keepDefinition.id(), keepWaypoint);
        DungeonRoomData.addWaypoint(clearDefinition.id(), waypoint("clear"));
        DungeonRoomData.clearCustom(clearDefinition.id());

        assertEquals(List.of(keepWaypoint), DungeonRoomData.waypointsFor(
                keep.withDefinition(keepDefinition.id(), keepDefinition.displayName())));
        assertTrue(DungeonRoomData.waypointsFor(
                clear.withDefinition(clearDefinition.id(), clearDefinition.displayName())).isEmpty());
    }

    @Test
    void clearWaypointsKeepsRoomDefinitionButEmptiesRoutes() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("clear-waypoints", "Clear Waypoints", room);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first"));

        DungeonRoomDefinition cleared = DungeonRoomData.clearWaypoints(definition.id());

        assertEquals(definition.id(), cleared.id());
        assertEquals(definition.displayName(), cleared.displayName());
        assertTrue(cleared.waypoints().isEmpty());
        assertTrue(DungeonRoomData.isCustomDefinition(definition.id()));
        assertTrue(DungeonRoomData.definition(definition.id()).waypoints().isEmpty());
    }

    @Test
    void clearAllCustomRemovesEveryRuntimeWaypoint() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("clear-all", "Clear All", room);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first"));
        DungeonRoom matched = room.withDefinition(definition.id(), definition.displayName());

        assertFalse(DungeonRoomData.waypointsFor(matched).isEmpty());
        DungeonRoomData.clearAllCustom();

        assertTrue(DungeonRoomData.waypointsFor(matched).isEmpty());
    }

    @Test
    void defineRoomCreatesStableCustomMatchAcrossPhysicalInstances() {
        DungeonRoom firstRun = roomAt(-8, 24);
        DungeonRoom secondRun = roomAt(56, 88);

        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("crypt-a", "Crypt A", firstRun);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first"));

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(secondRun, null);

        assertEquals("crypt-a", matched.roomId());
        assertEquals("Crypt A", matched.displayName());
        assertEquals(List.of(waypoint("first")), DungeonRoomData.waypointsFor(matched));
    }

    @Test
    void fingerprintMatchWinsWhenShapeHasMultipleCandidates() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomDefinition first = DungeonRoomData.defineRoom("first-room", "First", room);
        DungeonRoomDefinition second = DungeonRoomData.defineRoom("second-room", "Second", room);

        DungeonRoomData.addFingerprint(first.id(),
                new DungeonRoomFingerprint(1, 70, 1, "minecraft:stone"));
        DungeonRoomData.addFingerprint(second.id(),
                new DungeonRoomFingerprint(1, 70, 1, "minecraft:gold_block"));

        DungeonRoom matched = DungeonRoomData.withMatchedDefinition(room,
                (x, y, z) -> x == -7 && y == 70 && z == 25
                        ? "minecraft:gold_block"
                        : "minecraft:air");

        assertEquals("second-room", matched.roomId());
    }

    @Test
    void fingerprintMatchTransformsRoomLocalCoordinatesForEveryDirection() {
        int rx = 3;
        int ry = 70;
        int rz = 5;

        for (Direction direction : Direction.values()) {
            DungeonRoomData.clearAllCustom();
            DungeonRoom room = roomAt(direction, 100, 200);
            DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                    "rotated-" + direction.name().toLowerCase(), "Rotated " + direction, room);
            DungeonRoomData.addFingerprint(definition.id(),
                    new DungeonRoomFingerprint(rx, ry, rz, "minecraft:gold_block"));
            int[] expectedWorld = DungeonMapMath.relativeToActual(
                    direction, room.physicalCornerX(), room.physicalCornerZ(), rx, ry, rz);

            DungeonRoom matched = DungeonRoomData.withMatchedDefinition(room,
                    (x, y, z) -> x == expectedWorld[0] && y == expectedWorld[1] && z == expectedWorld[2]
                            ? "minecraft:gold_block"
                            : "minecraft:air");

            assertEquals(definition.id(), matched.roomId(), "direction " + direction);
        }
    }

    @Test
    void ambiguousFingerprintMatchesDoNotUseUnfingerprintedFallback() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomDefinition first = DungeonRoomData.defineRoom("first-room", "First", room);
        DungeonRoomDefinition second = DungeonRoomData.defineRoom("second-room", "Second", room);
        DungeonRoomData.defineRoom("fallback-room", "Fallback", room);

        DungeonRoomFingerprint fingerprint =
                new DungeonRoomFingerprint(1, 70, 1, "minecraft:stone");
        DungeonRoomData.addFingerprint(first.id(), fingerprint);
        DungeonRoomData.addFingerprint(second.id(), fingerprint);

        assertNull(DungeonRoomData.match(room,
                (x, y, z) -> x == -7 && y == 70 && z == 25
                        ? "minecraft:stone"
                        : "minecraft:air"));
    }

    @Test
    void ambiguousUnfingerprintedRoomsDoNotMatchArbitrarily() {
        DungeonRoom room = roomAt(-8, 24);

        DungeonRoomData.defineRoom("first-room", "First", room);
        DungeonRoomData.defineRoom("second-room", "Second", room);

        assertNull(DungeonRoomData.match(room, null));
    }

    @Test
    void jsonRoundTripsDefinitionsWaypointsAndHighlights() {
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "round-trip",
                "Round Trip",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(new DungeonRoomFingerprint(1, 70, 2, "stone")),
                List.of(waypoint("first")));

        Map<String, DungeonRoomDefinition> parsed =
                DungeonRoomData.parseDefinitions(DungeonRoomData.toJson(List.of(definition)));

        assertEquals(definition, parsed.get("round-trip"));
    }

    @Test
    void jsonRoundTripsRoomCountsAndWaypointColors() {
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "counted",
                "Counted",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(),
                List.of(waypoint("first").withCustomColor(0xABCDEF)),
                6, 2, 1);

        Map<String, DungeonRoomDefinition> parsed =
                DungeonRoomData.parseDefinitions(DungeonRoomData.toJson(List.of(definition)));

        DungeonRoomDefinition roundTripped = parsed.get("counted");
        assertEquals(definition, roundTripped);
        assertEquals(6, roundTripped.secretCount());
        assertEquals(2, roundTripped.cryptCount());
        assertEquals(1, roundTripped.trappedChestCount());
        assertEquals(0xABCDEF, roundTripped.waypoints().get(0).color());
    }

    @Test
    void bundledRoomsCarrySecretCounts() {
        DungeonRoomDefinition altar = DungeonRoomData.definition("altar");
        assertNotNull(altar);
        assertTrue(altar.hasSecretCount(), "bundled catalog should include Odin's secret counts");
        assertEquals(6, altar.secretCount());
    }

    @Test
    void waypointTriggerCanBeUpdatedAndPersistsThroughJson() {
        DungeonRoom room = roomAt(-8, 24);
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("trigger-room", "Trigger Room", room);
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first"));

        DungeonRoomData.setWaypointTrigger(definition.id(), 0, DungeonWaypointTrigger.DUNGEONBREAKER);
        String json = DungeonRoomData.toJson(DungeonRoomData.customDefinitions());
        Map<String, DungeonRoomDefinition> parsed = DungeonRoomData.parseDefinitions(json);

        assertEquals(DungeonWaypointTrigger.DUNGEONBREAKER,
                parsed.get(definition.id()).waypoints().get(0).trigger());
    }

    @Test
    void customStorePersistsDefinitions(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dungeon_rooms.json");
        DungeonRoomData.loadCustomStore(file);

        DungeonRoomDefinition definition = DungeonRoomData.defineRoom("persisted", "Persisted", roomAt(-8, 24));
        DungeonRoomData.addWaypoint(definition.id(), waypoint("first"));
        DungeonRoomData.flush();
        DungeonRoomData.clearAllCustom();

        DungeonRoomData.loadCustomStore(file);

        assertTrue(Files.exists(file));
        assertNotNull(DungeonRoomData.definition("persisted"));
        assertEquals(1, DungeonRoomData.definition("persisted").waypoints().size());
    }

    private static DungeonRoom roomAt(int x, int z) {
        return roomAt(Direction.NW, x, z);
    }

    private static DungeonRoom roomAt(Direction direction, int x, int z) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                direction,
                x,
                z,
                List.of(DungeonRoom.packSegment(x, z)));
    }

    private static DungeonRoom twoByTwoRoomAt(int x, int z) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.TWO_BY_TWO,
                Direction.NW,
                x,
                z,
                List.of(
                        DungeonRoom.packSegment(x, z),
                        DungeonRoom.packSegment(x + 32, z),
                        DungeonRoom.packSegment(x, z + 32),
                        DungeonRoom.packSegment(x + 32, z + 32)));
    }

    private static DungeonWaypoint waypoint(String id) {
        return new DungeonWaypoint(
                id,
                1,
                DungeonSecretCategory.CHEST,
                16,
                70,
                16,
                id,
                List.of(DungeonHighlight.outline(15, 70, 15)));
    }
}
