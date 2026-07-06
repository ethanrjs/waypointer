package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.dungeon.data.DungeonRoomFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DungeonDirectionResolutionTest {

    @Test
    void roomLocalWaypointsProjectOntoExpectedEastSideWhenDirectionIsNe() {
        int[] corner = DungeonMapMath.physicalCorner(
                Direction.NE,
                -104,
                -168,
                -104,
                -168);
        DungeonWaypoint first = waypointAt(25, 77, 2);
        DungeonWaypoint second = waypointAt(5, 59, 15);

        int[] firstWorld = DungeonMapMath.relativeToActual(
                Direction.NE,
                corner[0],
                corner[1],
                first.x(),
                first.y(),
                first.z());
        int[] secondWorld = DungeonMapMath.relativeToActual(
                Direction.NE,
                corner[0],
                corner[1],
                second.x(),
                second.y(),
                second.z());

        assertArrayEquals(new int[] { -76, 77, -143 }, firstWorld);
        assertArrayEquals(new int[] { -89, 59, -163 }, secondWorld);
    }

    @Test
    void fingerprintsAutoDetectUniqueNeDirection() {
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "fingerprinted",
                "Fingerprinted",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(new DungeonRoomFingerprint(25, 77, 2, "gold_block")),
                List.of());

        Direction direction = DungeonStateTracker.detectDirectionFromFingerprints(
                definition,
                List.of(DungeonRoom.packSegment(-104, -168)),
                new MapBlockLookup(Map.of(
                        MapBlockLookup.key(-76, 77, -143),
                        "minecraft:gold_block")));

        assertEquals(Direction.NE, direction);
    }

    @Test
    void fingerprintDirectionDetectionReturnsNullForAmbiguousMatches() {
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "ambiguous",
                "Ambiguous",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(new DungeonRoomFingerprint(15, 70, 15, "minecraft:gold_block")),
                List.of());

        Direction direction = DungeonStateTracker.detectDirectionFromFingerprints(
                definition,
                List.of(DungeonRoom.packSegment(-104, -168)),
                new MapBlockLookup(Map.of(
                        MapBlockLookup.key(-89, 70, -153),
                        "minecraft:gold_block")));

        assertNull(direction);
    }

    @Test
    void dropMarkerAutoDetectsNeDirection() {
        Direction direction = DungeonStateTracker.detectDirectionFromRoomMarker(
                List.of(DungeonRoom.packSegment(-104, -168)),
                69,
                new MapBlockLookup(Map.of(
                        MapBlockLookup.key(-74, 69, -168),
                        "minecraft:blue_terracotta")));

        assertEquals(Direction.NE, direction);
    }

    @Test
    void markerDetectionFallsBackFromUnreadyTopLayerToVisibleMarker() {
        Direction direction = DungeonStateTracker.detectDirectionFromRoomMarker(
                List.of(DungeonRoom.packSegment(-200, -136)),
                11,
                new MapBlockLookup(Map.of(
                        MapBlockLookup.key(-200, 69, -106),
                        "minecraft:blue_terracotta")));

        assertEquals(Direction.SW, direction);
    }

    @Test
    void markerDirectionDetectionReturnsNullForAmbiguousMarkers() {
        Direction direction = DungeonStateTracker.detectDirectionFromRoomMarker(
                List.of(DungeonRoom.packSegment(-104, -168)),
                69,
                new MapBlockLookup(Map.of(
                        MapBlockLookup.key(-104, 69, -168),
                        "minecraft:blue_terracotta",
                        MapBlockLookup.key(-74, 69, -168),
                        "minecraft:blue_terracotta")));

        assertNull(direction);
    }

    @Test
    void lShapeMarkerUsesComponentCornerAsAnchor() {
        List<Long> segments = List.of(
                DungeonRoom.packSegment(-200, -200),
                DungeonRoom.packSegment(-168, -200),
                DungeonRoom.packSegment(-200, -168));
        MapBlockLookup lookup = new MapBlockLookup(Map.of(
                MapBlockLookup.key(-138, 72, -200),
                "minecraft:blue_terracotta"));

        Direction direction = DungeonStateTracker.detectDirectionFromRoomMarker(segments, 72, lookup);
        int[] anchor = DungeonStateTracker.detectRoomMarkerAnchor(segments, 72, lookup);

        assertEquals(Direction.NE, direction);
        assertArrayEquals(new int[] { -138, -200 }, anchor);
    }

    @Test
    void odinPhysicalSegmentCornerMatchesOdinGridCenters() {
        assertArrayEquals(new int[] { -200, -200 },
                DungeonStateTracker.odinPhysicalSegmentCorner(-185.0, -185.0));
        assertArrayEquals(new int[] { -168, -136 },
                DungeonStateTracker.odinPhysicalSegmentCorner(-153.0, -121.0));
    }

    @Test
    void odinPhysicalSegmentCornerUsesOdinTruncationAtNegativeBoundary() {
        assertArrayEquals(new int[] { -168, -168 },
                DungeonStateTracker.odinPhysicalSegmentCorner(-169.1, -169.1));
    }

    @Test
    void manualDirectionOverrideRecomputesPhysicalCornerFromSegments() {
        DungeonStateTracker tracker = new DungeonStateTracker(
                new ActiveGroupManager(),
                new DungeonConfig());
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -104,
                -168,
                List.of(DungeonRoom.packSegment(-104, -168)),
                "drop",
                "Drop",
                DungeonDetectionConfidence.CORE_CONFIRMED);
        tracker.setCurrentRoom(room);

        tracker.setDirectionOverride(Direction.NE);

        DungeonRoom rotated = tracker.currentRoom();
        assertNotNull(rotated);
        assertEquals(Direction.NE, rotated.direction());
        assertEquals(-74, rotated.physicalCornerX());
        assertEquals(-168, rotated.physicalCornerZ());

        DungeonWaypoint first = waypointAt(25, 77, 2);
        int[] firstWorld = DungeonMapMath.relativeToActual(
                rotated.direction(),
                rotated.physicalCornerX(),
                rotated.physicalCornerZ(),
                first.x(),
                first.y(),
                first.z());

        assertArrayEquals(new int[] { -76, 77, -143 }, firstWorld);
    }

    private static DungeonWaypoint waypointAt(int x, int y, int z) {
        return DungeonWaypoint.plain("projection-fixture", DungeonSecretCategory.DEFAULT, x, y, z, "");
    }

    private static final class MapBlockLookup implements DungeonRoomData.BlockLookup {
        private final Map<String, String> blocks;

        private MapBlockLookup(Map<String, String> blocks) {
            this.blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
        }

        @Override
        public String blockIdAt(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), "minecraft:air");
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }
}
