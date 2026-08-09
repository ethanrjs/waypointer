package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts between world blocks, dungeon-map pixels, and rotated room-local coordinates. */
public final class DungeonMapMath {

    public static final byte ENTRANCE_COLOR = DungeonRoomType.ENTRANCE.packedColor;

    public static final int SEGMENT_BLOCKS = 32;

    public static final int DUNGEON_BLOCK_OFFSET = 8;

    public static final int MAP_ROOM_GAP_PX = 4;

    private DungeonMapMath() {}

    /** Returns the player's map-pixel position, or {@code null} before it appears. */
    public static int[] getMapPlayerPos(MapItemSavedData map) {
        for (MapDecoration decoration : map.getDecorations()) {
            if (decoration.type().value().equals(MapDecorationTypes.FRAME.value())) {
                int px = (decoration.x() >> 1) + 64;
                int py = (decoration.y() >> 1) + 64;
                return new int[] { px, py };
            }
        }
        return null;
    }

    public static byte getColor(MapItemSavedData map, int x, int z) {
        if (x < 0 || z < 0 || x >= 128 || z >= 128) return -1;
        return map.colors[x + (z << 7)];
    }

    public static boolean isEntranceColor(MapItemSavedData map, int x, int z) {
        return getColor(map, x, z) == ENTRANCE_COLOR;
    }

    /** Returns {@code [entranceX, entranceZ, roomSize]}, or {@code null} before the map is ready. */
    public static int[] findEntranceAndRoomSize(MapItemSavedData map) {
        int[] start = getMapPlayerPos(map);
        if (start == null) return null;

        Deque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(start);
        seen.add(packPx(start[0], start[1]));

        int[] cur;
        while ((cur = queue.poll()) != null) {
            if (isEntranceColor(map, cur[0], cur[1])) {
                int[] cornerAndSize = entranceCornerAndSizeAt(map, cur[0], cur[1]);
                if (cornerAndSize[2] > 0) return cornerAndSize;
            }
            offer(queue, seen, cur[0] - 10, cur[1]);
            offer(queue, seen, cur[0],      cur[1] - 10);
            offer(queue, seen, cur[0] + 10, cur[1]);
            offer(queue, seen, cur[0],      cur[1] + 10);
        }
        return null;
    }

    private static int[] entranceCornerAndSizeAt(MapItemSavedData map, int startX, int startZ) {
        int x = startX, z = startZ;
        while (isEntranceColor(map, x - 1, z)) x--;
        while (isEntranceColor(map, x, z - 1)) z--;
        int size = 0;
        while (isEntranceColor(map, x + size, z)) size++;
        return new int[] { x, z, size > 5 ? size : 0 };
    }

    private static void offer(Deque<int[]> q, Set<Long> seen, int x, int z) {
        if (x < 0 || z < 0 || x >= 128 || z >= 128) return;
        long packed = packPx(x, z);
        if (seen.add(packed)) q.add(new int[] { x, z });
    }

    private static long packPx(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** Snaps a world position to its 32x32 room-segment corner. */
    public static int[] physicalSegmentCorner(double x, double z) {
        int px = (int) Math.floor(x + 0.5) + DUNGEON_BLOCK_OFFSET;
        int pz = (int) Math.floor(z + 0.5) + DUNGEON_BLOCK_OFFSET;
        int cx = px - Math.floorMod(px, SEGMENT_BLOCKS) - DUNGEON_BLOCK_OFFSET;
        int cz = pz - Math.floorMod(pz, SEGMENT_BLOCKS) - DUNGEON_BLOCK_OFFSET;
        return new int[] { cx, cz };
    }

    public static int[] physicalToMap(int physEntranceX, int physEntranceZ,
                                      int mapEntranceX, int mapEntranceZ,
                                      int mapRoomSize, int physX, int physZ) {
        int dx = (physX - physEntranceX) / SEGMENT_BLOCKS;
        int dz = (physZ - physEntranceZ) / SEGMENT_BLOCKS;
        return new int[] {
                dx * (mapRoomSize + MAP_ROOM_GAP_PX) + mapEntranceX,
                dz * (mapRoomSize + MAP_ROOM_GAP_PX) + mapEntranceZ
        };
    }

    public static int[] mapToPhysical(int mapEntranceX, int mapEntranceZ,
                                      int mapRoomSize,
                                      int physEntranceX, int physEntranceZ,
                                      int mapX, int mapZ) {
        int dx = (mapX - mapEntranceX) / (mapRoomSize + MAP_ROOM_GAP_PX);
        int dz = (mapZ - mapEntranceZ) / (mapRoomSize + MAP_ROOM_GAP_PX);
        return new int[] {
                dx * SEGMENT_BLOCKS + physEntranceX,
                dz * SEGMENT_BLOCKS + physEntranceZ
        };
    }

    /** Finds all same-colored room segments connected across map gaps. */
    public static List<int[]> floodSegments(MapItemSavedData map, int mapX, int mapZ,
                                            int mapRoomSize, byte color) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        if (seen.add(packPx(mapX, mapZ))) {
            out.add(new int[] { mapX, mapZ });
            queue.add(new int[] { mapX, mapZ });
        }
        // The probe is already one pixel into the room gap.
        int backStep = mapRoomSize + 3;
        int[] cur;
        while ((cur = queue.poll()) != null) {
            tryHop(map, queue, seen, out, cur[0] - 1,             cur[1],                 -backStep,            0,                color);
            tryHop(map, queue, seen, out, cur[0],                 cur[1] - 1,             0,                    -backStep,        color);
            tryHop(map, queue, seen, out, cur[0] + mapRoomSize,   cur[1],                 +MAP_ROOM_GAP_PX,     0,                color);
            tryHop(map, queue, seen, out, cur[0],                 cur[1] + mapRoomSize,   0,                    +MAP_ROOM_GAP_PX, color);
        }
        return out;
    }

    private static void tryHop(MapItemSavedData map, Deque<int[]> queue, Set<Long> seen, List<int[]> out,
                               int probeX, int probeZ, int dx, int dz, byte color) {
        if (getColor(map, probeX, probeZ) != color) return;
        int nx = probeX + dx, nz = probeZ + dz;
        if (seen.add(packPx(nx, nz))) {
            out.add(new int[] { nx, nz });
            queue.add(new int[] { nx, nz });
        }
    }

    /** Finds the physical corner used as the room data's local origin. */
    public static int[] physicalCorner(Direction dir,
                                       int minSegmentX, int minSegmentZ,
                                       int maxSegmentX, int maxSegmentZ) {
        // Authored routes use the inner corner at +30, not the outer +32 edge.
        int farX = maxSegmentX + 30;
        int farZ = maxSegmentZ + 30;
        return switch (dir) {
            case NW -> new int[] { minSegmentX, minSegmentZ };
            case NE -> new int[] { farX,        minSegmentZ };
            case SW -> new int[] { minSegmentX, farZ        };
            case SE -> new int[] { farX,        farZ        };
        };
    }

    /** Converts a room-local block to a world block. */
    public static int[] relativeToActual(Direction dir,
                                         int physicalCornerX, int physicalCornerZ,
                                         int rx, int ry, int rz) {
        return switch (dir) {
            case NW -> new int[] {  rx + physicalCornerX, ry,  rz + physicalCornerZ };
            case NE -> new int[] { -rz + physicalCornerX, ry,  rx + physicalCornerZ };
            case SW -> new int[] {  rz + physicalCornerX, ry, -rx + physicalCornerZ };
            case SE -> new int[] { -rx + physicalCornerX, ry, -rz + physicalCornerZ };
        };
    }

    public static void relativeToActual(Direction dir,
                                        int physicalCornerX, int physicalCornerZ,
                                        int rx, int ry, int rz,
                                        int[] out) {
        switch (dir) {
            case NW -> {
                out[0] = rx + physicalCornerX;
                out[1] = ry;
                out[2] = rz + physicalCornerZ;
            }
            case NE -> {
                out[0] = -rz + physicalCornerX;
                out[1] = ry;
                out[2] = rx + physicalCornerZ;
            }
            case SW -> {
                out[0] = rz + physicalCornerX;
                out[1] = ry;
                out[2] = -rx + physicalCornerZ;
            }
            case SE -> {
                out[0] = -rx + physicalCornerX;
                out[1] = ry;
                out[2] = -rz + physicalCornerZ;
            }
        }
    }

    public static int[] actualToRelative(Direction dir,
                                         int physicalCornerX, int physicalCornerZ,
                                         int wx, int wy, int wz) {
        return switch (dir) {
            case NW -> new int[] {  wx - physicalCornerX, wy,  wz - physicalCornerZ };
            case NE -> new int[] {  wz - physicalCornerZ, wy, -wx + physicalCornerX };
            case SW -> new int[] { -wz + physicalCornerZ, wy,  wx - physicalCornerX };
            case SE -> new int[] { -wx + physicalCornerX, wy, -wz + physicalCornerZ };
        };
    }

    /**
     * Converts room-local sixteenth-block coordinates to world coordinates.
     * Mirrored axes also flip the position inside the block.
     */
    public static int[] relativePreciseToActual(Direction dir,
                                                int physicalCornerX, int physicalCornerZ,
                                                int rx, int ry, int rz) {
        int cx = physicalCornerX * Waypoint.PRECISE_SCALE;
        int cz = physicalCornerZ * Waypoint.PRECISE_SCALE;
        int mirror = Waypoint.PRECISE_SCALE - 1;
        return switch (dir) {
            case NW -> new int[] {  rx + cx,            ry,  rz + cz            };
            case NE -> new int[] {  cx + mirror - rz,   ry,  rx + cz            };
            case SW -> new int[] {  rz + cx,            ry,  cz + mirror - rx   };
            case SE -> new int[] {  cx + mirror - rx,   ry,  cz + mirror - rz   };
        };
    }

    public static int[] actualPreciseToRelative(Direction dir,
                                                int physicalCornerX, int physicalCornerZ,
                                                int px, int py, int pz) {
        int cx = physicalCornerX * Waypoint.PRECISE_SCALE;
        int cz = physicalCornerZ * Waypoint.PRECISE_SCALE;
        int mirror = Waypoint.PRECISE_SCALE - 1;
        return switch (dir) {
            case NW -> new int[] {  px - cx,            py,  pz - cz            };
            case NE -> new int[] {  pz - cz,            py,  cx + mirror - px   };
            case SW -> new int[] {  cz + mirror - pz,   py,  px - cx            };
            case SE -> new int[] {  cx + mirror - px,   py,  cz + mirror - pz   };
        };
    }

    public static String label(Direction dir) {
        return dir.name().toLowerCase(Locale.ROOT);
    }
}
