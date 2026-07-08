package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.Waypoint;
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

/**
 * Pure math for translating between three coordinate systems used by Hypixel
 * Skyblock dungeons:
 *
 * <ol>
 *   <li><b>Physical world coords</b> -- standard Minecraft block positions.</li>
 *   <li><b>Map-pixel coords</b> -- 0..127 indexes into the 128x128 dungeon
 *       map item the player carries in inventory slot 9.</li>
 *   <li><b>Room-local coords</b> -- coordinates relative to the room's
 *       canonical NW corner, rotated by the room's {@link Direction} so the
 *       same room layout reads identically regardless of the world rotation
 *       Hypixel applied.</li>
 * </ol>
 *
 * <p>The algorithms here are a re-implementation of Skyblocker's
 * {@code DungeonMapUtils} (LGPL-3.0). Every public method has a 1:1 analogue
 * in that class; the math is identical because dungeons are a fixed grid and
 * there's only one correct answer.
 *
 * <p>{@code MapItemSavedData} stores its 128x128 byte array as a single flat
 * row-major buffer indexed by {@code x + (z << 7)}; that addressing is also
 * fixed by the Minecraft format, not chosen by us.
 */
public final class DungeonMapMath {

    /** Map color for an entrance-room pixel. Constant since MC stabilised the {@code MapColor} ids. */
    public static final byte ENTRANCE_COLOR = DungeonRoomType.ENTRANCE.packedColor;

    /** Block size of a dungeon room segment. Hypixel pins rooms to a 32-block grid. */
    public static final int SEGMENT_BLOCKS = 32;

    /** Offset Hypixel applied to dungeons in Skyblock 0.12.3 -- room corners sit on a {@code grid + 8} lattice. */
    public static final int DUNGEON_BLOCK_OFFSET = 8;

    /** Gap (in map pixels) between adjacent rooms on the dungeon map. */
    public static final int MAP_ROOM_GAP_PX = 4;

    private DungeonMapMath() {}

    // ---- map-side: read player + room positions off the map item -------

    /** Player position in map-pixel coords, or {@code null} if no player decoration is on the map. */
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

    /**
     * Locate the entrance room on the map and measure how many pixels wide a
     * room is. Returned as {@code [entranceX, entranceZ, roomSize]} or
     * {@code null} if the player isn't shown on the map (rare, but happens
     * during the initial 1-2 frames after world join).
     *
     * <p>Walks outward from the player's map position in 10-pixel steps,
     * looking for an entrance-colored pixel; from there scans left/up to
     * find the entrance's NW corner and right to measure the room size.
     * Direct port of Skyblocker's {@code getMapEntrancePosAndRoomSize}.
     */
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
        long packed = packPx(x, z);
        if (seen.add(packed)) q.add(new int[] { x, z });
    }

    private static long packPx(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    // ---- map<->physical -------------------------------------------------

    /**
     * Snap a world-space position to the NW corner of the 32x32 segment it
     * falls inside. Hypixel offsets dungeons by 8 blocks (see
     * {@link #DUNGEON_BLOCK_OFFSET}); the {@code +0.5} centres the rounding so
     * room borders split evenly between adjacent segments.
     *
     * <p>Direct port of Skyblocker's {@code getPhysicalRoomPos(double, double)}.
     */
    public static int[] physicalSegmentCorner(double x, double z) {
        int px = (int) Math.floor(x + 0.5) + DUNGEON_BLOCK_OFFSET;
        int pz = (int) Math.floor(z + 0.5) + DUNGEON_BLOCK_OFFSET;
        int cx = px - Math.floorMod(px, SEGMENT_BLOCKS) - DUNGEON_BLOCK_OFFSET;
        int cz = pz - Math.floorMod(pz, SEGMENT_BLOCKS) - DUNGEON_BLOCK_OFFSET;
        return new int[] { cx, cz };
    }

    /**
     * Map a physical room-corner back to its NW pixel on the dungeon map.
     * Both the physical-entrance and map-entrance reference points are needed
     * because the entrance is the only room with a known one-to-one anchor
     * between the two coordinate systems.
     */
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

    /**
     * Inverse of {@link #physicalToMap} -- given a NW map pixel, recover the
     * physical NW corner of the corresponding room segment.
     */
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

    // ---- segment flood-fill ---------------------------------------------

    /**
     * Find every map pixel that belongs to the same room as {@code (mapX, mapZ)}
     * by flooding outward across same-colored cells, jumping the {@code +4}
     * pixel gap that the dungeon map draws between adjacent rooms. Returned
     * positions are NW-corner pixels of each segment.
     *
     * <p>Direct port of Skyblocker's {@code getRoomSegments}.
     */
    public static List<int[]> floodSegments(MapItemSavedData map, int mapX, int mapZ,
                                            int mapRoomSize, byte color) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        if (seen.add(packPx(mapX, mapZ))) {
            out.add(new int[] { mapX, mapZ });
            queue.add(new int[] { mapX, mapZ });
        }
        // Distance from this cell's NW corner to its left/up neighbour's NW
        // corner: hop the room body (mapRoomSize) plus the 4px gap, but the
        // probe pixel is already one step in the direction we're hopping, so
        // the residual jump is (mapRoomSize + 4) - 1 = mapRoomSize + 3.
        int backStep = mapRoomSize + 3;
        int[] cur;
        while ((cur = queue.poll()) != null) {
            // probe-pixel sits inside the inter-room gap; if it carries the
            // room color, the cell on the other side of the gap is part of
            // the same room.
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

    // ---- direction-aware room-local <-> world ---------------------------

    /**
     * Pick the physical corner that corresponds to the canonical NW origin of
     * the room data for the given direction, given the bounding box of the
     * room's segments.
     *
     * <p>Mirror of Skyblocker's {@code getPhysicalCornerPos}.
     */
    public static int[] physicalCorner(Direction dir,
                                       int minSegmentX, int minSegmentZ,
                                       int maxSegmentX, int maxSegmentZ) {
        // The +30 offset is verbatim from Skyblocker's getPhysicalCornerPos; the
        // canonical origin of a rotated room is the *inner* opposite corner of
        // the segment, not the outer +32 edge. Curated room data is authored
        // against that origin, so changing the offset would shift every secret
        // by 1-2 blocks. Don't touch without reading Skyblocker's room data.
        int farX = maxSegmentX + 30;
        int farZ = maxSegmentZ + 30;
        return switch (dir) {
            case NW -> new int[] { minSegmentX, minSegmentZ };
            case NE -> new int[] { farX,        minSegmentZ };
            case SW -> new int[] { minSegmentX, farZ        };
            case SE -> new int[] { farX,        farZ        };
        };
    }

    /**
     * Project a room-local block position to a world block position. Inverse
     * is {@link #actualToRelative}. The four-way switch is the same rotation
     * Skyblocker uses; Y is unaffected because rooms are aligned to the
     * world's vertical axis.
     */
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

    /** Inverse of {@link #relativeToActual} -- world coords back into the room's frame. */
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
     * Precise-coordinate ({@link Waypoint#PRECISE_SCALE} 16ths of a block)
     * variant of {@link #relativeToActual}. Negated axes mirror through
     * {@code corner*16 + 15 - p} rather than plain negation: a block cell
     * {@code [16b, 16b+16)} rotates onto the cell the block math selects, and
     * the sub-block offset flips within it ({@code f -> 15 - f}), so
     * {@code Math.floorDiv(precise, 16)} of the result always equals the
     * block-coordinate projection of the waypoint's block position.
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

    /** Exact inverse of {@link #relativePreciseToActual}. */
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

    // ---- misc utility ---------------------------------------------------

    /** Lower-case label for a direction; handy when serialising. */
    public static String label(Direction dir) {
        return dir.name().toLowerCase(Locale.ROOT);
    }
}
