package com.babbur.waypointer.dungeon;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DungeonMapMathTest {

    @Test
    void findEntranceAndRoomSize_returnsNullWhenEntranceIsMissing() {
        MapItemSavedData map = mapWithPlayerAtCenter();

        assertTimeoutPreemptively(Duration.ofMillis(250),
                () -> assertNull(DungeonMapMath.findEntranceAndRoomSize(map)));
    }

    @Test
    void findEntranceAndRoomSize_returnsNullWhenEntranceIsIncomplete() {
        MapItemSavedData map = mapWithPlayerAtCenter();
        for (int z = 60; z < 65; z++) {
            for (int x = 50; x < 55; x++) {
                map.setColor(x, z, DungeonMapMath.ENTRANCE_COLOR);
            }
        }

        assertTimeoutPreemptively(Duration.ofMillis(250),
                () -> assertNull(DungeonMapMath.findEntranceAndRoomSize(map)));
    }

    @Test
    void findEntranceAndRoomSize_findsCompleteEntrance() {
        MapItemSavedData map = mapWithPlayerAtCenter();
        for (int z = 60; z < 76; z++) {
            for (int x = 40; x < 56; x++) {
                map.setColor(x, z, DungeonMapMath.ENTRANCE_COLOR);
            }
        }

        assertArrayEquals(new int[] { 40, 60, 16 },
                DungeonMapMath.findEntranceAndRoomSize(map));
    }

    private static MapItemSavedData mapWithPlayerAtCenter() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MapItemSavedData map = MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD);
        map.addClientSideDecorations(List.of(new MapDecoration(
                MapDecorationTypes.FRAME,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                Optional.empty())));
        return map;
    }

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
