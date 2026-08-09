package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DungeonRoomResolver {

    private static final int OVER_ROOM_SUPPRESSION_BLOCKS = 10;
    private static final int UNRESOLVED_RETRY_INITIAL_DELAY_TICKS = 5;
    private static final int UNRESOLVED_RETRY_MAX_DELAY_TICKS = 80;

    private final Map<Long, DungeonRoom> knownRoomsBySegment = new HashMap<>();
    private final Map<Long, DungeonCoreSignature> coreSignaturesBySegment = new HashMap<>();
    private final Map<String, List<RoomAssembly>> assembliesByDefinition = new HashMap<>();
    private final List<RoomAssembly> unresolvedAssemblies = new ArrayList<>();
    private final Set<Long> loggedUnknownCoreSegments = new HashSet<>();
    private final Map<List<Long>, RoomDirectionOverride> directionOverridesByRoom = new HashMap<>();

    private long nextUnresolvedRetryTick = Long.MAX_VALUE;
    private int unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
    private boolean unresolvedRetryRequested;

    void clear() {
        knownRoomsBySegment.clear();
        coreSignaturesBySegment.clear();
        assembliesByDefinition.clear();
        unresolvedAssemblies.clear();
        nextUnresolvedRetryTick = Long.MAX_VALUE;
        unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
        unresolvedRetryRequested = false;
        loggedUnknownCoreSegments.clear();
        directionOverridesByRoom.clear();
    }

    DungeonRoom roomAt(long segment) {
        return knownRoomsBySegment.get(segment);
    }

    DungeonCoreSignature signatureAt(long segment) {
        return coreSignaturesBySegment.get(segment);
    }

    int knownSegmentCount() {
        return knownRoomsBySegment.size();
    }

    int signatureCount() {
        return coreSignaturesBySegment.size();
    }

    List<DungeonRoom> knownRooms() {
        List<DungeonRoom> out = new ArrayList<>();
        for (DungeonRoom room : knownRoomsBySegment.values()) {
            if (!out.contains(room)) out.add(room);
        }
        return out;
    }

    void cache(DungeonRoom room) {
        if (room == null) return;
        for (Long segment : room.segments()) {
            knownRoomsBySegment.put(segment, room);
        }
    }

    boolean hasUnresolvedAssemblies() {
        return !unresolvedAssemblies.isEmpty();
    }

    boolean isAboveRoof(long segment, double playerY) {
        DungeonCoreSignature signature = coreSignaturesBySegment.get(segment);
        return signature != null
                && signature.topY() > 0
                && playerY > signature.topY() - OVER_ROOM_SUPPRESSION_BLOCKS;
    }

    Direction directionOverride(DungeonRoom room) {
        RoomDirectionOverride override = room == null
                ? null
                : directionOverridesByRoom.get(DungeonRoomOrientation.sortedSegments(room.segments()));
        return override == null ? null : override.direction;
    }

    DungeonRoom withDirectionOverride(DungeonRoom room, Direction direction) {
        if (room == null) return null;

        List<Long> roomKey = DungeonRoomOrientation.sortedSegments(room.segments());
        RoomDirectionOverride existing = directionOverridesByRoom.get(roomKey);
        Direction detectedDirection = existing == null ? room.direction() : existing.detectedDirection;
        Direction effectiveDirection = direction == null ? detectedDirection : direction;
        if (direction == null) {
            directionOverridesByRoom.remove(roomKey);
        } else {
            directionOverridesByRoom.put(
                    roomKey,
                    new RoomDirectionOverride(direction, detectedDirection));
        }

        int[] corner = DungeonRoomOrientation.physicalCornerForSegments(
                effectiveDirection,
                room.segments(),
                room.physicalCornerX(),
                room.physicalCornerZ());
        return new DungeonRoom(
                room.type(),
                room.shape(),
                effectiveDirection,
                corner[0],
                corner[1],
                room.segments(),
                room.roomId(),
                room.roomName(),
                room.confidence());
    }

    void scan(LevelChunk chunk, long segment) {
        if (chunk == null || coreSignaturesBySegment.containsKey(segment)) return;

        DungeonCoreSignature signature = DungeonRoomCoreScanner.coreSignatureForChunk(chunk);
        coreSignaturesBySegment.put(segment, signature);
        DungeonRoomCatalogEntry definition = DungeonRoomData.entryForCoreHash(signature.hash());
        if (definition == null) {
            logUnknownCoreHashOnce(segment, signature);
            return;
        }

        RoomAssembly assembly = attachScannedSegment(definition, segment, signature.topY());
        if (assembly.isComplete()) queueAssemblyForResolution(assembly);
    }

    private void logUnknownCoreHashOnce(long segment, DungeonCoreSignature signature) {
        if (!loggedUnknownCoreSegments.add(segment)) return;
        Waypointer.LOGGER.info(
                "Unknown dungeon room core hash {} at segment ({}, {}), roofY={}",
                signature.hash(),
                DungeonRoom.segmentX(segment),
                DungeonRoom.segmentZ(segment),
                signature.topY());
    }

    RoomAssembly attachScannedSegment(
            DungeonRoomCatalogEntry definition,
            long segment,
            int roofY) {
        List<RoomAssembly> assemblies = assembliesByDefinition.computeIfAbsent(
                definition.id(),
                ignored -> new ArrayList<>());

        if (definition.type() != DungeonRoomType.ENTRANCE) {
            List<RoomAssembly> adjacent = new ArrayList<>();
            for (RoomAssembly assembly : assemblies) {
                if (assembly.contains(segment)) return assembly;
                if (assembly.isAdjacent(segment)) adjacent.add(assembly);
            }

            if (!adjacent.isEmpty()) {
                RoomAssembly assembly = adjacent.getFirst();
                if (assembly.canMerge(segment, adjacent)) {
                    assembly.add(segment, roofY);
                    for (int i = 1; i < adjacent.size(); i++) {
                        RoomAssembly absorbed = adjacent.get(i);
                        assembly.merge(absorbed);
                        assemblies.remove(absorbed);
                        unresolvedAssemblies.remove(absorbed);
                    }
                    return assembly;
                }

                for (RoomAssembly candidate : adjacent) {
                    if (!candidate.canAccept(segment)) continue;
                    candidate.add(segment, roofY);
                    return candidate;
                }
            }
        }

        RoomAssembly assembly = new RoomAssembly(definition);
        assembly.add(segment, roofY);
        assemblies.add(assembly);
        return assembly;
    }

    List<RoomAssembly> assemblies(String definitionId) {
        return List.copyOf(assembliesByDefinition.getOrDefault(definitionId, List.of()));
    }

    void queueAssemblyForResolution(RoomAssembly assembly) {
        if (assembly == null || assembly.resolved) return;
        if (!unresolvedAssemblies.contains(assembly)) {
            unresolvedAssemblies.add(assembly);
            unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
        }
        requestRetry();
    }

    void requestRetry() {
        unresolvedRetryRequested = true;
    }

    void retry(DungeonRoomData.BlockLookup lookup, long dungeonTick) {
        if (lookup == null || !unresolvedRetryDue(dungeonTick)) return;
        unresolvedRetryRequested = false;
        boolean resolvedAny = false;
        for (int i = 0; i < unresolvedAssemblies.size(); ) {
            RoomAssembly assembly = unresolvedAssemblies.get(i);
            DungeonRoom room = resolveCoreRoom(assembly, lookup);
            if (room == null) {
                i++;
                continue;
            }
            assembly.resolved = true;
            cache(room);
            unresolvedAssemblies.remove(i);
            resolvedAny = true;
        }
        if (unresolvedAssemblies.isEmpty()) {
            nextUnresolvedRetryTick = Long.MAX_VALUE;
            unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
            return;
        }
        if (resolvedAny) {
            unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
        }
        nextUnresolvedRetryTick = dungeonTick + unresolvedRetryDelayTicks;
        unresolvedRetryDelayTicks = Math.min(
                UNRESOLVED_RETRY_MAX_DELAY_TICKS,
                unresolvedRetryDelayTicks * 2);
    }

    private boolean unresolvedRetryDue(long dungeonTick) {
        return !unresolvedAssemblies.isEmpty()
                && (unresolvedRetryRequested || dungeonTick >= nextUnresolvedRetryTick);
    }

    private DungeonRoom resolveCoreRoom(
            RoomAssembly assembly,
            DungeonRoomData.BlockLookup lookup) {
        if (assembly == null || lookup == null || !assembly.isComplete()) return null;

        List<Long> segments = assembly.sortedSegments();
        DungeonRoomOrientation.RoomMarker marker = markerForResolvedRoom(
                assembly.definition,
                segments,
                assembly.roofY,
                lookup);
        if (marker == null) return null;

        DungeonRoomShape detectedShape = DungeonRoomShape.classifySegments(segments);
        DungeonDetectionConfidence confidence = confidenceForCoreRoom(assembly.definition, detectedShape);
        return new DungeonRoom(
                assembly.definition.type(),
                assembly.definition.shape(),
                marker.direction(),
                marker.anchorX(),
                marker.anchorZ(),
                segments,
                assembly.definition.id(),
                assembly.definition.displayName(),
                confidence);
    }

    private DungeonRoomOrientation.RoomMarker markerForResolvedRoom(
            DungeonRoomCatalogEntry definition,
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        RoomDirectionOverride override = directionOverridesByRoom.get(
                DungeonRoomOrientation.sortedSegments(segments));
        if (override != null) {
            int[] corner = DungeonRoomOrientation.physicalCornerForSegments(
                    override.direction,
                    segments,
                    0,
                    0);
            return new DungeonRoomOrientation.RoomMarker(override.direction, corner[0], corner[1]);
        }
        if (definition.type() == DungeonRoomType.FAIRY && !segments.isEmpty()) {
            long segment = segments.getFirst();
            return new DungeonRoomOrientation.RoomMarker(
                    Direction.NW,
                    DungeonRoom.segmentX(segment),
                    DungeonRoom.segmentZ(segment));
        }
        return DungeonRoomOrientation.detectRoomMarker(segments, markerY, lookup);
    }

    private static DungeonDetectionConfidence confidenceForCoreRoom(
            DungeonRoomCatalogEntry definition,
            DungeonRoomShape detectedShape) {
        if (definition.type() != DungeonRoomType.ROOM) return DungeonDetectionConfidence.CORE_CONFIRMED;
        return detectedShape == definition.shape()
                ? DungeonDetectionConfidence.CORE_CONFIRMED
                : DungeonDetectionConfidence.CORE_MATCHED;
    }

    private static int minTileCount(DungeonRoomCatalogEntry definition) {
        if (definition == null) return 1;
        return switch (definition.shape()) {
            case ONE_BY_TWO -> 2;
            case ONE_BY_THREE, L_SHAPE -> 3;
            case ONE_BY_FOUR, TWO_BY_TWO -> 4;
            case ONE_BY_ONE, UNKNOWN -> 1;
        };
    }

    private static int maxTileCount(DungeonRoomCatalogEntry definition) {
        if (definition == null) return 1;
        return definition.shape() == DungeonRoomShape.L_SHAPE ? 4 : minTileCount(definition);
    }

    private record RoomDirectionOverride(Direction direction, Direction detectedDirection) {}

    static final class RoomAssembly {
        private final DungeonRoomCatalogEntry definition;
        private final Set<Long> segments = new HashSet<>();
        private int roofY;
        private boolean resolved;

        private RoomAssembly(DungeonRoomCatalogEntry definition) {
            this.definition = definition;
        }

        Set<Long> segments() {
            return Set.copyOf(segments);
        }

        private boolean contains(long segment) {
            return segments.contains(segment);
        }

        private boolean canAccept(long segment) {
            if (segments.size() >= maxTileCount(definition) || !isAdjacent(segment)) return false;
            Set<Long> combined = new HashSet<>(segments);
            combined.add(segment);
            return shapeCanContain(definition.shape(), combined);
        }

        private boolean canMerge(long segment, List<RoomAssembly> adjacent) {
            Set<Long> combined = new HashSet<>();
            combined.add(segment);
            for (RoomAssembly assembly : adjacent) combined.addAll(assembly.segments);
            return combined.size() <= maxTileCount(definition)
                    && shapeCanContain(definition.shape(), combined);
        }

        private void add(long segment, int nextRoofY) {
            if (!segments.add(segment)) return;
            roofY = Math.max(roofY, nextRoofY);
            resolved = false;
        }

        private void merge(RoomAssembly other) {
            if (other == null || other == this) return;
            segments.addAll(other.segments);
            roofY = Math.max(roofY, other.roofY);
            resolved = false;
        }

        private boolean isComplete() {
            return segments.size() >= minTileCount(definition);
        }

        private List<Long> sortedSegments() {
            return DungeonRoomOrientation.sortedSegments(segments);
        }

        private boolean isAdjacent(long segment) {
            int x = DungeonRoom.segmentX(segment);
            int z = DungeonRoom.segmentZ(segment);
            for (Long existing : segments) {
                int dx = Math.abs(DungeonRoom.segmentX(existing) - x);
                int dz = Math.abs(DungeonRoom.segmentZ(existing) - z);
                if ((dx == DungeonMapMath.SEGMENT_BLOCKS && dz == 0)
                        || (dx == 0 && dz == DungeonMapMath.SEGMENT_BLOCKS)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean shapeCanContain(DungeonRoomShape shape, Set<Long> segments) {
            if (segments.isEmpty()) return true;
            Set<Integer> xCoordinates = new HashSet<>();
            Set<Integer> zCoordinates = new HashSet<>();
            for (long segment : segments) {
                xCoordinates.add(DungeonRoom.segmentX(segment));
                zCoordinates.add(DungeonRoom.segmentZ(segment));
            }
            return switch (shape) {
                case ONE_BY_THREE, ONE_BY_FOUR -> xCoordinates.size() == 1 || zCoordinates.size() == 1;
                case TWO_BY_TWO -> xCoordinates.size() <= 2 && zCoordinates.size() <= 2;
                case L_SHAPE -> segments.size() < 4
                        || (xCoordinates.size() > 1
                        && zCoordinates.size() > 1
                        && !(xCoordinates.size() == 2 && zCoordinates.size() == 2));
                case ONE_BY_ONE, ONE_BY_TWO, UNKNOWN -> true;
            };
        }
    }
}
