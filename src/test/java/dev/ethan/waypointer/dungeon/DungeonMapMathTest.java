package dev.ethan.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonMapMathTest {

    @Test
    void physicalSegmentCorner_snapsToHypixelGridOffset() {
        assertArrayEquals(new int[] { -8, -8 }, DungeonMapMath.physicalSegmentCorner(0.0, 0.0));
        assertArrayEquals(new int[] { -8, -8 }, DungeonMapMath.physicalSegmentCorner(23.49, 23.49));
        assertArrayEquals(new int[] { 24, 24 }, DungeonMapMath.physicalSegmentCorner(23.5, 23.5));
        assertArrayEquals(new int[] { -40, -40 }, DungeonMapMath.physicalSegmentCorner(-24.0, -24.0));
    }

    @Test
    void physicalAndMapCoordinatesRoundTripFromEntranceAnchor() {
        int physEntranceX = -8;
        int physEntranceZ = -8;
        int mapEntranceX = 22;
        int mapEntranceZ = 14;
        int mapRoomSize = 18;

        int[] map = DungeonMapMath.physicalToMap(
                physEntranceX, physEntranceZ,
                mapEntranceX, mapEntranceZ,
                mapRoomSize,
                56, 24);
        assertArrayEquals(new int[] { 66, 36 }, map);

        int[] physical = DungeonMapMath.mapToPhysical(
                mapEntranceX, mapEntranceZ,
                mapRoomSize,
                physEntranceX, physEntranceZ,
                map[0], map[1]);
        assertArrayEquals(new int[] { 56, 24 }, physical);
    }

    @Test
    void physicalCorner_usesInnerFarCornerForRotatedRooms() {
        int minX = -8;
        int minZ = 24;
        int maxX = 56;
        int maxZ = 88;

        assertArrayEquals(new int[] { -8, 24 },
                DungeonMapMath.physicalCorner(Direction.NW, minX, minZ, maxX, maxZ));
        assertArrayEquals(new int[] { 86, 24 },
                DungeonMapMath.physicalCorner(Direction.NE, minX, minZ, maxX, maxZ));
        assertArrayEquals(new int[] { -8, 118 },
                DungeonMapMath.physicalCorner(Direction.SW, minX, minZ, maxX, maxZ));
        assertArrayEquals(new int[] { 86, 118 },
                DungeonMapMath.physicalCorner(Direction.SE, minX, minZ, maxX, maxZ));
    }

    @Test
    void relativeToActual_appliesExpectedRotationForEachDirection() {
        int cornerX = 100;
        int cornerZ = 200;
        int rx = 3;
        int ry = 70;
        int rz = 5;

        assertArrayEquals(new int[] { 103, 70, 205 },
                DungeonMapMath.relativeToActual(Direction.NW, cornerX, cornerZ, rx, ry, rz));
        assertArrayEquals(new int[] { 95, 70, 203 },
                DungeonMapMath.relativeToActual(Direction.NE, cornerX, cornerZ, rx, ry, rz));
        assertArrayEquals(new int[] { 105, 70, 197 },
                DungeonMapMath.relativeToActual(Direction.SW, cornerX, cornerZ, rx, ry, rz));
        assertArrayEquals(new int[] { 97, 70, 195 },
                DungeonMapMath.relativeToActual(Direction.SE, cornerX, cornerZ, rx, ry, rz));
    }

    @Test
    void relativeAndActualCoordinatesAreInversesForAllDirections() {
        int cornerX = -40;
        int cornerZ = 88;
        int[][] samples = {
                { 0, 68, 0 },
                { 12, 70, 29 },
                { -3, 65, 31 },
                { 30, 91, -4 }
        };

        for (Direction direction : Direction.values()) {
            for (int[] relative : samples) {
                int[] actual = DungeonMapMath.relativeToActual(
                        direction,
                        cornerX,
                        cornerZ,
                        relative[0],
                        relative[1],
                        relative[2]);

                assertArrayEquals(relative,
                        DungeonMapMath.actualToRelative(
                                direction,
                                cornerX,
                                cornerZ,
                                actual[0],
                                actual[1],
                                actual[2]),
                        "round trip failed for " + direction);
            }
        }
    }

    @Test
    void label_returnsLowerCaseDirectionName() {
        assertEquals("nw", DungeonMapMath.label(Direction.NW));
        assertEquals("se", DungeonMapMath.label(Direction.SE));
    }
}
