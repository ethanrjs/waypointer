package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import com.babbur.waypointer.dungeon.data.DungeonRoomFingerprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DungeonRoomOrientation {

    private static final int ROOM_MARKER_SEARCH_MIN_Y = 12;
    private static final int ROOM_MARKER_SEARCH_MAX_Y = 160;
    private static final String BLUE_TERRACOTTA_BLOCK_ID = "minecraft:blue_terracotta";
    private static final String AIR_BLOCK_ID = "minecraft:air";
    private static final String CAVE_AIR_BLOCK_ID = "minecraft:cave_air";
    private static final String VOID_AIR_BLOCK_ID = "minecraft:void_air";

    private DungeonRoomOrientation() {}

    static int[] physicalCornerForSegments(
            Direction direction,
            List<Long> segments,
            int fallbackX,
            int fallbackZ) {
        if (segments == null || segments.isEmpty()) {
            return new int[] { fallbackX, fallbackZ };
        }

        int minSegX = Integer.MAX_VALUE;
        int minSegZ = Integer.MAX_VALUE;
        int maxSegX = Integer.MIN_VALUE;
        int maxSegZ = Integer.MIN_VALUE;
        boolean found = false;
        for (Long segment : segments) {
            if (segment == null) continue;
            int x = DungeonRoom.segmentX(segment);
            int z = DungeonRoom.segmentZ(segment);
            if (x < minSegX) minSegX = x;
            if (z < minSegZ) minSegZ = z;
            if (x > maxSegX) maxSegX = x;
            if (z > maxSegZ) maxSegZ = z;
            found = true;
        }
        if (!found) {
            return new int[] { fallbackX, fallbackZ };
        }
        return DungeonMapMath.physicalCorner(direction, minSegX, minSegZ, maxSegX, maxSegZ);
    }

    static Direction detectDirectionFromRoomMarker(
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        RoomMarker marker = detectRoomMarker(segments, markerY, lookup);
        return marker == null ? null : marker.direction;
    }

    static int[] detectRoomMarkerAnchor(
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        RoomMarker marker = detectRoomMarker(segments, markerY, lookup);
        return marker == null ? null : new int[] { marker.anchorX, marker.anchorZ };
    }

    static RoomMarker detectRoomMarker(
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        if (segments == null || segments.isEmpty() || lookup == null) return null;
        List<Long> sorted = sortedSegments(segments);
        if (sorted.isEmpty()) return null;

        // Rooms that fill their rectangle only need the four outer corners checked.
        // This avoids matching decorative blue terracotta inside the room. L-shaped
        // rooms can use an inner corner, so check every segment corner instead.
        if (hasFullBoundingBoxCoverage(sorted)) {
            return detectMarkerAtBoundingBoxCorners(sorted, markerY, lookup);
        }
        return detectMarkerAtSegmentCorners(sorted, markerY, lookup);
    }

    private static boolean hasFullBoundingBoxCoverage(List<Long> sorted) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long segment : sorted) {
            int x = DungeonRoom.segmentX(segment);
            int z = DungeonRoom.segmentZ(segment);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
        int spanX = (maxX - minX) / DungeonMapMath.SEGMENT_BLOCKS + 1;
        int spanZ = (maxZ - minZ) / DungeonMapMath.SEGMENT_BLOCKS + 1;
        return sorted.size() == spanX * spanZ;
    }

    private static RoomMarker detectMarkerAtBoundingBoxCorners(
            List<Long> sorted,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long segment : sorted) {
            int x = DungeonRoom.segmentX(segment);
            int z = DungeonRoom.segmentZ(segment);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }

        RoomMarker matched = null;
        int matchCount = 0;
        for (Direction candidate : Direction.values()) {
            int[] corner = DungeonMapMath.physicalCorner(candidate, minX, minZ, maxX, maxZ);
            if (!roomMarkerMatches(corner[0], markerY, corner[1], sorted.size(), lookup)) {
                continue;
            }
            matched = new RoomMarker(candidate, corner[0], corner[1]);
            matchCount++;
        }
        return matchCount == 1 ? matched : null;
    }

    private static RoomMarker detectMarkerAtSegmentCorners(
            List<Long> sorted,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        RoomMarker matched = null;
        int matchCount = 0;
        int segmentCount = sorted.size();
        for (Long segment : sorted) {
            if (segment == null) continue;
            for (Direction candidate : Direction.values()) {
                int[] corner = componentCorner(candidate, segment);
                if (!roomMarkerMatches(corner[0], markerY, corner[1], segmentCount, lookup)) {
                    continue;
                }
                matched = new RoomMarker(candidate, corner[0], corner[1]);
                matchCount++;
            }
        }
        return matchCount == 1 ? matched : null;
    }

    private static int[] componentCorner(Direction direction, long segment) {
        int segmentX = DungeonRoom.segmentX(segment);
        int segmentZ = DungeonRoom.segmentZ(segment);
        return switch (direction) {
            case NW -> new int[] { segmentX, segmentZ };
            case NE -> new int[] { segmentX + 30, segmentZ };
            case SE -> new int[] { segmentX + 30, segmentZ + 30 };
            case SW -> new int[] { segmentX, segmentZ + 30 };
        };
    }

    private static boolean roomMarkerMatches(
            int cornerX,
            int markerY,
            int cornerZ,
            int segmentCount,
            DungeonRoomData.BlockLookup lookup) {
        if (markerY >= ROOM_MARKER_SEARCH_MIN_Y
                && markerY <= ROOM_MARKER_SEARCH_MAX_Y
                && markerBlockMatches(cornerX, markerY, cornerZ, segmentCount, lookup)) {
            return true;
        }

        int discoveredY = topNonAirY(cornerX, cornerZ, lookup);
        if (discoveredY < 0 || discoveredY == markerY) return false;
        return markerBlockMatches(cornerX, discoveredY, cornerZ, segmentCount, lookup);
    }

    private static boolean markerBlockMatches(
            int cornerX,
            int markerY,
            int cornerZ,
            int segmentCount,
            DungeonRoomData.BlockLookup lookup) {
        String markerBlock = DungeonRoomFingerprint.normalizeBlockId(
                lookup.blockIdAt(cornerX, markerY, cornerZ));
        if (!isBlueTerracotta(markerBlock)) return false;
        if (segmentCount <= 1) return true;
        return markerNeighborBlocksValid(cornerX, markerY, cornerZ, lookup);
    }

    private static int topNonAirY(
            int cornerX,
            int cornerZ,
            DungeonRoomData.BlockLookup lookup) {
        for (int y = ROOM_MARKER_SEARCH_MAX_Y; y >= ROOM_MARKER_SEARCH_MIN_Y; y--) {
            String blockId = DungeonRoomFingerprint.normalizeBlockId(
                    lookup.blockIdAt(cornerX, y, cornerZ));
            if (!isAirBlock(blockId)) return y;
        }
        return -1;
    }

    private static boolean markerNeighborBlocksValid(
            int cornerX,
            int markerY,
            int cornerZ,
            DungeonRoomData.BlockLookup lookup) {
        int[][] offsets = {
                { 1, 0 },
                { -1, 0 },
                { 0, 1 },
                { 0, -1 }
        };
        for (int[] offset : offsets) {
            String neighborBlock = DungeonRoomFingerprint.normalizeBlockId(
                    lookup.blockIdAt(cornerX + offset[0], markerY, cornerZ + offset[1]));
            if (!isAirOrBlueTerracotta(neighborBlock)) return false;
        }
        return true;
    }

    private static boolean isBlueTerracotta(String blockId) {
        return BLUE_TERRACOTTA_BLOCK_ID.equals(blockId);
    }

    private static boolean isAirBlock(String blockId) {
        return AIR_BLOCK_ID.equals(blockId)
                || CAVE_AIR_BLOCK_ID.equals(blockId)
                || VOID_AIR_BLOCK_ID.equals(blockId);
    }

    private static boolean isAirOrBlueTerracotta(String blockId) {
        return AIR_BLOCK_ID.equals(blockId) || BLUE_TERRACOTTA_BLOCK_ID.equals(blockId);
    }

    static Direction detectDirectionFromFingerprints(
            DungeonRoomCatalogEntry definition,
            List<Long> segments,
            DungeonRoomData.BlockLookup lookup) {
        if (definition == null || !definition.hasFingerprints() || lookup == null) return null;

        Direction matched = null;
        int matchCount = 0;
        for (Direction candidate : Direction.values()) {
            int[] corner = physicalCornerForSegments(candidate, segments, 0, 0);
            if (!fingerprintsMatch(candidate, corner[0], corner[1], definition.fingerprints(), lookup)) {
                continue;
            }
            matched = candidate;
            matchCount++;
        }
        return matchCount == 1 ? matched : null;
    }

    private static boolean fingerprintsMatch(
            Direction direction,
            int cornerX,
            int cornerZ,
            List<DungeonRoomFingerprint> fingerprints,
            DungeonRoomData.BlockLookup lookup) {
        for (DungeonRoomFingerprint fingerprint : fingerprints) {
            int[] world = DungeonMapMath.relativeToActual(
                    direction,
                    cornerX,
                    cornerZ,
                    fingerprint.x(),
                    fingerprint.y(),
                    fingerprint.z());
            String actual = DungeonRoomFingerprint.normalizeBlockId(
                    lookup.blockIdAt(world[0], world[1], world[2]));
            if (!fingerprint.blockId().equals(actual)) return false;
        }
        return true;
    }

    static List<Long> sortedSegments(Iterable<Long> segments) {
        List<Long> out = new ArrayList<>();
        if (segments != null) {
            for (Long segment : segments) {
                if (segment != null) out.add(segment);
            }
        }
        Collections.sort(out);
        return out;
    }

    record RoomMarker(Direction direction, int anchorX, int anchorZ) {}
}
