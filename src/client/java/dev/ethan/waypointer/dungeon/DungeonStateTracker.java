package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.dungeon.data.DungeonRoomFingerprint;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Detects what room the player is standing in inside Catacombs and exposes
 * that as a {@link DungeonRoom} read by the renderer.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Subscribe to {@link ActiveGroupManager}'s zone listener; only do work
 *       when the resolved zone id starts with {@code "dungeon_"}.</li>
 *   <li>Hash Odin-grid room-center chunks once when the client receives the
 *       chunk-load event, never by polling unloaded map-visible cells.</li>
 *   <li>Attach adjacent cells that resolve to the same catalog definition, then
 *       resolve direction and origin from the blue terracotta component corner.</li>
 *   <li>On ticks, look up the player's current tile in the resolved room cache
 *       and suppress context switches when the player is flying above the roof.</li>
 * </ol>
 *
 * <p>State mutates on the client tick thread; the public read accessors are
 * safe to call from the render thread because the {@code currentRoom}
 * reference is volatile and {@link DungeonRoom} is immutable.
 *
 * <p><b>Remaining follow-ups:</b>
 *
 * <ul>
 *   <li>Per-secret found tracking driven by chat / interaction events.</li>
 * </ul>
 */
public final class DungeonStateTracker {

    /**
     * Check the already-resolved current-room tile every tick so HUD/editor
     * context changes feel immediate; identity work is driven by chunk events.
     */
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final int ODIN_ROOM_SIZE_SHIFT = 5;
    private static final int ODIN_ROOM_CENTER_START = -185;
    private static final int ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET = 15;
    private static final int ODIN_ROOM_CENTER_CHUNK_MIN = -12;
    private static final int ODIN_ROOM_CENTER_CHUNK_MAX = -2;
    private static final int ROOM_CENTER_CHUNK_OFFSET = 7;
    private static final int OVER_ROOM_SUPPRESSION_BLOCKS = 10;
    private static final int ROOM_MARKER_SEARCH_MIN_Y = 12;
    private static final int ROOM_MARKER_SEARCH_MAX_Y = 160;
    private static final int UNRESOLVED_RETRY_INITIAL_DELAY_TICKS = 5;
    private static final int UNRESOLVED_RETRY_MAX_DELAY_TICKS = 80;
    private static final String BLUE_TERRACOTTA_BLOCK_ID = "minecraft:blue_terracotta";
    private static final String AIR_BLOCK_ID = "minecraft:air";
    private static final String CAVE_AIR_BLOCK_ID = "minecraft:cave_air";
    private static final String VOID_AIR_BLOCK_ID = "minecraft:void_air";

    private final ActiveGroupManager manager;
    private final DungeonConfig config;
    private final List<Consumer<DungeonRoom>> listeners = new ArrayList<>();
    private final Map<Long, DungeonRoom> knownRoomsBySegment = new HashMap<>();
    private final Map<Long, DungeonCoreSignature> coreSignaturesBySegment = new HashMap<>();
    private final Map<Long, LevelChunk> pendingChunks = new HashMap<>();
    private final Map<String, List<RoomAssembly>> assembliesByDefinition = new HashMap<>();
    private final List<RoomAssembly> unresolvedAssemblies = new ArrayList<>();
    private final Set<Long> loggedUnknownCoreSegments = new HashSet<>();

    private volatile boolean inDungeon;
    private volatile DungeonRoom currentRoom;
    private volatile Direction directionOverride;
    private ClientLevel observedLevel;

    private int tickCounter;
    private long dungeonTick;
    private long nextUnresolvedRetryTick = Long.MAX_VALUE;
    private int unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
    private boolean unresolvedRetryRequested;
    private volatile long lastScanAtMillis;
    private volatile long lastScanDurationNanos;
    private volatile String lastScanStage = "not started";
    private volatile String lastScanResult = "not started";
    private volatile long lastPlayerSegment = Long.MIN_VALUE;
    private volatile DungeonCoreSignature lastPlayerSegmentSignature;
    private volatile String lastMatchedRoomId = "";
    private volatile String lastMatchedRoomName = "";
    private volatile int lastMatchedComponentCount;

    public DungeonStateTracker(ActiveGroupManager manager, DungeonConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
        ClientChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onZoneChanged(currentZone);
    }

    public void addRoomListener(Consumer<DungeonRoom> l) {
        listeners.add(l);
    }

    public void removeRoomListener(Consumer<DungeonRoom> l) {
        listeners.remove(l);
    }

    public boolean inDungeon() {
        return inDungeon;
    }

    public DungeonRoom currentRoom() {
        return config.enabled() ? currentRoom : null;
    }

    /**
     * Every distinct resolved room so far this run. Client tick thread only --
     * same thread that mutates the segment cache.
     */
    public List<DungeonRoom> knownRooms() {
        List<DungeonRoom> out = new ArrayList<>();
        for (DungeonRoom room : knownRoomsBySegment.values()) {
            if (!out.contains(room)) out.add(room);
        }
        return out;
    }

    public Direction directionOverride() {
        return directionOverride;
    }

    public DebugSnapshot debugSnapshot() {
        DungeonRoom room = currentRoom;
        Direction effectiveDirection = directionOverride != null
                ? directionOverride
                : room == null ? defaultDirection() : room.direction();
        return new DebugSnapshot(
                inDungeon,
                room != null,
                room == null ? "(none)" : room.displayName(),
                room != null && room.hasRoomId() ? room.roomId() : "(unmatched)",
                room == null ? "(none)" : String.valueOf(room.type()),
                room == null ? "(none)" : String.valueOf(room.shape()),
                room == null ? "(none)" : String.valueOf(room.direction()),
                room == null ? 0 : room.physicalCornerX(),
                room == null ? 0 : room.physicalCornerZ(),
                room == null ? List.of() : List.copyOf(room.segments()),
                room == null ? DungeonDetectionConfidence.UNKNOWN : room.confidence(),
                directionOverride == null ? "(none)" : String.valueOf(directionOverride),
                String.valueOf(effectiveDirection),
                knownRoomsBySegment.size(),
                coreSignaturesBySegment.size(),
                lastScanAtMillis,
                lastScanDurationNanos,
                lastScanStage,
                lastScanResult,
                lastPlayerSegment,
                lastPlayerSegmentSignature,
                lastMatchedRoomId,
                lastMatchedRoomName,
                lastMatchedComponentCount);
    }

    public void applyCurrentRoomDefinition(String id, String name) {
        DungeonRoom prev = currentRoom;
        if (prev == null) return;
        DungeonRoom updated = prev.withDefinition(id, name);
        currentRoom = updated;
        cacheRoom(updated);
        fireRoomChanged(updated);
    }

    public void setDirectionOverride(Direction dir) {
        this.directionOverride = dir;
        clearResolvedRoomState(false);
        // Drop the cached room so the next tick picks the new direction up.
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            Direction effectiveDirection = dir == null ? defaultDirection() : dir;
            int[] corner = physicalCornerForSegments(
                    effectiveDirection,
                    prev.segments(),
                    prev.physicalCornerX(),
                    prev.physicalCornerZ());
            DungeonRoom rotated = new DungeonRoom(
                    prev.type(),
                    prev.shape(),
                    effectiveDirection,
                    corner[0],
                    corner[1],
                    prev.segments(),
                    prev.roomId(),
                    prev.roomName(),
                    prev.confidence());
            currentRoom = rotated;
            cacheRoom(rotated);
            fireRoomChanged(rotated);
        }
    }

    // ---- zone -> dungeon state -----------------------------------------

    void onZoneChanged(Zone zone) {
        boolean nowDungeon = DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                || DungeonRoomZoneBridge.isRoomZone(zone);
        if (nowDungeon == inDungeon) {
            rememberPassiveScanState("zone unchanged", describeZoneForDebug(zone));
            return;
        }
        inDungeon = nowDungeon;
        clearResolvedRoomState(nowDungeon);
        rememberPassiveScanState(nowDungeon ? "entered dungeon context" : "left dungeon context",
                describeZoneForDebug(zone));
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            currentRoom = null;
            fireRoomChanged(null);
        }
        if (nowDungeon) {
            Waypointer.LOGGER.info("Dungeon detected: {} -- scanning loaded room-center chunks", zone.id());
            flushPendingChunks(observedLevel);
        }
    }

    // ---- per-tick scan -------------------------------------------------

    private void onClientTick(Minecraft client) {
        long startedNanos = System.nanoTime();
        dungeonTick++;
        if (!config.enabled()) {
            rememberScanResult("skipped", "config disabled", startedNanos,
                    Long.MIN_VALUE, null, null);
            return;
        }
        if (!inDungeon) {
            rememberScanResult("skipped", "outside dungeon", startedNanos,
                    Long.MIN_VALUE, null, null);
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            rememberScanResult("skipped", "missing player or level", startedNanos,
                    Long.MIN_VALUE, null, null);
            return;
        }
        rememberLevel(level);
        flushPendingChunks(level);
        retryUnresolvedAssemblies(level);

        int[] physRoomCorner = odinPhysicalSegmentCorner(player.getX(), player.getZ());
        long playerSegment = DungeonRoom.packSegment(physRoomCorner[0], physRoomCorner[1]);
        DungeonRoom built = knownRoomsBySegment.get(playerSegment);
        boolean suppressedByRoof = false;
        if (built != null && playerIsAboveRoomRoof(player, playerSegment)) {
            built = currentRoom;
            suppressedByRoof = true;
        }
        DungeonCoreSignature signature = coreSignaturesBySegment.get(playerSegment);
        String stage = suppressedByRoof
                ? "over-roof suppressed"
                : built != null
                ? "tile cache hit"
                : signature == null ? "tile not scanned" : "tile unresolved";
        String result = built == null
                ? "unmatched"
                : "matched " + built.displayName() + " (" + (built.hasRoomId() ? built.roomId() : "<unmatched>") + ")";
        rememberScanResult(stage, result, startedNanos, playerSegment, signature, built);
        setCurrentRoom(built);
    }

    static int[] odinPhysicalSegmentCorner(double x, double z) {
        int centerX = odinRoomCenter((int) x);
        int centerZ = odinRoomCenter((int) z);
        return new int[] {
                centerX - ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET,
                centerZ - ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET
        };
    }

    private static int odinRoomCenter(int coordinate) {
        int roomIndex = (coordinate - ODIN_ROOM_CENTER_START + (1 << (ODIN_ROOM_SIZE_SHIFT - 1)))
                >> ODIN_ROOM_SIZE_SHIFT;
        return (roomIndex << ODIN_ROOM_SIZE_SHIFT) + ODIN_ROOM_CENTER_START;
    }

    // ---- chunk-load room registry -------------------------------------

    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        rememberLevel(level);
        if (chunk == null) return;
        if (inDungeon && !unresolvedAssemblies.isEmpty()) requestUnresolvedRetry();
        if (!isRoomCenterChunk(chunk.getPos())) return;
        if (!inDungeon) {
            pendingChunks.put(chunkKey(chunk.getPos()), chunk);
            return;
        }
        scanLoadedRoomChunk(level, chunk);
    }

    private void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        if (chunk != null) {
            pendingChunks.remove(chunkKey(chunk.getPos()));
        }
    }

    private void rememberLevel(ClientLevel level) {
        if (level == null) return;
        if (observedLevel == null) {
            observedLevel = level;
            return;
        }
        if (observedLevel == level) return;
        observedLevel = level;
        clearResolvedRoomState(false);
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            currentRoom = null;
            fireRoomChanged(null);
        }
        rememberPassiveScanState("world changed", "cleared dungeon room state");
    }

    private void clearResolvedRoomState(boolean keepPendingChunks) {
        knownRoomsBySegment.clear();
        coreSignaturesBySegment.clear();
        assembliesByDefinition.clear();
        unresolvedAssemblies.clear();
        nextUnresolvedRetryTick = Long.MAX_VALUE;
        unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
        unresolvedRetryRequested = false;
        loggedUnknownCoreSegments.clear();
        if (!keepPendingChunks) pendingChunks.clear();
    }

    private void flushPendingChunks(ClientLevel level) {
        if (!inDungeon || level == null || pendingChunks.isEmpty()) return;
        List<LevelChunk> chunks = new ArrayList<>(pendingChunks.values());
        pendingChunks.clear();
        for (LevelChunk chunk : chunks) {
            scanLoadedRoomChunk(level, chunk);
        }
    }

    private void scanLoadedRoomChunk(ClientLevel level, LevelChunk chunk) {
        if (level == null || chunk == null || !isRoomCenterChunk(chunk.getPos())) return;

        long segment = segmentForRoomCenterChunk(chunk.getPos());
        if (coreSignaturesBySegment.containsKey(segment)) return;

        DungeonCoreSignature signature = DungeonRoomCoreScanner.coreSignatureForChunk(chunk);
        coreSignaturesBySegment.put(segment, signature);
        DungeonRoomDefinition definition = DungeonRoomData.definitionForCoreHash(signature.hash());
        if (definition == null) {
            logUnknownCoreHashOnce(segment, signature);
            return;
        }

        RoomAssembly assembly = attachScannedSegment(definition, segment, signature.topY());
        if (assembly != null && assembly.isComplete()) {
            queueAssemblyForResolution(assembly);
        }
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

    private RoomAssembly attachScannedSegment(
            DungeonRoomDefinition definition,
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

    private void queueAssemblyForResolution(RoomAssembly assembly) {
        if (assembly == null || assembly.resolved) return;
        if (!unresolvedAssemblies.contains(assembly)) {
            unresolvedAssemblies.add(assembly);
            unresolvedRetryDelayTicks = UNRESOLVED_RETRY_INITIAL_DELAY_TICKS;
        }
        requestUnresolvedRetry();
    }

    private void requestUnresolvedRetry() {
        unresolvedRetryRequested = true;
    }

    private void retryUnresolvedAssemblies(ClientLevel level) {
        if (level == null || !unresolvedRetryDue()) return;
        retryUnresolvedAssemblies(new DungeonRoomBlockLookup(level));
    }

    void retryUnresolvedAssemblies(DungeonRoomData.BlockLookup lookup) {
        if (lookup == null || !unresolvedRetryDue()) return;
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
            cacheRoom(room);
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

    private boolean unresolvedRetryDue() {
        return !unresolvedAssemblies.isEmpty()
                && (unresolvedRetryRequested || dungeonTick >= nextUnresolvedRetryTick);
    }

    private DungeonRoom resolveCoreRoom(
            RoomAssembly assembly,
            DungeonRoomData.BlockLookup lookup) {
        if (assembly == null || lookup == null || !assembly.isComplete()) return null;

        List<Long> packed = assembly.sortedSegments();
        RoomMarker marker = markerForResolvedRoom(assembly.definition, packed, assembly.roofY, lookup);
        if (marker == null) return null;

        DungeonRoomShape detectedShape = DungeonRoomShape.classifySegments(packed);
        DungeonDetectionConfidence confidence = confidenceForCoreRoom(assembly.definition, detectedShape);
        return new DungeonRoom(
                assembly.definition.type(),
                assembly.definition.shape(),
                marker.direction,
                marker.anchorX,
                marker.anchorZ,
                packed,
                assembly.definition.id(),
                assembly.definition.displayName(),
                confidence);
    }

    private RoomMarker markerForResolvedRoom(
            DungeonRoomDefinition definition,
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        if (directionOverride != null) {
            int[] corner = physicalCornerForSegments(directionOverride, segments, 0, 0);
            return new RoomMarker(directionOverride, corner[0], corner[1]);
        }
        if (definition.type() == DungeonRoomType.FAIRY && !segments.isEmpty()) {
            long segment = segments.get(0);
            return new RoomMarker(Direction.NW, DungeonRoom.segmentX(segment), DungeonRoom.segmentZ(segment));
        }
        return detectRoomMarker(segments, markerY, lookup);
    }

    private boolean playerIsAboveRoomRoof(LocalPlayer player, long playerSegment) {
        DungeonCoreSignature signature = coreSignaturesBySegment.get(playerSegment);
        return signature != null
                && signature.topY() > 0
                && player.getY() > signature.topY() - OVER_ROOM_SUPPRESSION_BLOCKS;
    }

    private static boolean isRoomCenterChunk(ChunkPos pos) {
        return pos != null
                && (pos.x() & 1) == 0
                && (pos.z() & 1) == 0
                && pos.x() >= ODIN_ROOM_CENTER_CHUNK_MIN
                && pos.x() <= ODIN_ROOM_CENTER_CHUNK_MAX
                && pos.z() >= ODIN_ROOM_CENTER_CHUNK_MIN
                && pos.z() <= ODIN_ROOM_CENTER_CHUNK_MAX;
    }

    private static long segmentForRoomCenterChunk(ChunkPos pos) {
        int centerX = pos.x() * 16 + ROOM_CENTER_CHUNK_OFFSET;
        int centerZ = pos.z() * 16 + ROOM_CENTER_CHUNK_OFFSET;
        return DungeonRoom.packSegment(
                centerX - ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET,
                centerZ - ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET);
    }

    private static long chunkKey(ChunkPos pos) {
        return (((long) pos.x()) << 32) | (pos.z() & 0xFFFFFFFFL);
    }

    private static int[] physicalCornerForSegments(
            Direction dir,
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
        return DungeonMapMath.physicalCorner(dir, minSegX, minSegZ, maxSegX, maxSegZ);
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

    private static RoomMarker detectRoomMarker(
            List<Long> segments,
            int markerY,
            DungeonRoomData.BlockLookup lookup) {
        if (segments == null || segments.isEmpty() || lookup == null) return null;
        List<Long> sorted = sortedSegments(segments);
        if (sorted.isEmpty()) return null;

        // Rectangular rooms (1x1, 1xN, 2x2) can only carry their marker at a
        // bounding-box corner, and every direction maps to a distinct corner,
        // so four probes suffice and inner segment corners -- where decorative
        // blue terracotta can sit mid-wall -- never produce a false candidate.
        // L-shapes may keep the marker at the elbow segment's corner, which is
        // not a bounding-box corner, so they fall back to per-segment probing.
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
            DungeonRoomDefinition definition,
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

    private static DungeonDetectionConfidence confidenceForCoreRoom(
            DungeonRoomDefinition definition,
            DungeonRoomShape detectedShape) {
        if (definition.type() != DungeonRoomType.ROOM) return DungeonDetectionConfidence.CORE_CONFIRMED;
        return detectedShape == definition.shape()
                ? DungeonDetectionConfidence.CORE_CONFIRMED
                : DungeonDetectionConfidence.CORE_MATCHED;
    }

    private static List<Long> sortedSegments(Iterable<Long> segments) {
        List<Long> out = new ArrayList<>();
        if (segments != null) {
            for (Long segment : segments) {
                if (segment != null) out.add(segment);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static int minTileCount(DungeonRoomDefinition definition) {
        if (definition == null) return 1;
        return switch (definition.shape()) {
            case ONE_BY_TWO -> 2;
            case ONE_BY_THREE, L_SHAPE -> 3;
            case ONE_BY_FOUR, TWO_BY_TWO -> 4;
            case ONE_BY_ONE, UNKNOWN -> 1;
        };
    }

    private static int maxTileCount(DungeonRoomDefinition definition) {
        if (definition == null) return 1;
        return definition.shape() == DungeonRoomShape.L_SHAPE ? 4 : minTileCount(definition);
    }

    private record RoomMarker(Direction direction, int anchorX, int anchorZ) {}

    private static final class RoomAssembly {
        private final DungeonRoomDefinition definition;
        private final Set<Long> segments = new HashSet<>();
        private int roofY;
        private boolean resolved;

        private RoomAssembly(DungeonRoomDefinition definition) {
            this.definition = definition;
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
            return DungeonStateTracker.sortedSegments(segments);
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

    private void cacheRoom(DungeonRoom room) {
        if (room == null) return;
        for (Long segment : room.segments()) {
            knownRoomsBySegment.put(segment, room);
        }
    }

    void setCurrentRoom(DungeonRoom next) {
        DungeonRoom prev = currentRoom;
        if (sameRoomState(prev, next)) return;
        currentRoom = next;
        fireRoomChanged(next);
    }

    private static boolean sameRoomState(DungeonRoom left, DungeonRoom right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.identityKey().equals(right.identityKey())
                && left.roomId().equals(right.roomId())
                && left.roomName().equals(right.roomName())
                && left.confidence() == right.confidence();
    }

    // ---- helpers -------------------------------------------------------

    private Direction defaultDirection() {
        try {
            return Direction.valueOf(config.defaultDirection().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Direction.NW;
        }
    }

    private void rememberPassiveScanState(String stage, String result) {
        lastScanAtMillis = System.currentTimeMillis();
        lastScanDurationNanos = 0L;
        lastScanStage = debugText(stage, "idle");
        lastScanResult = debugText(result, "(none)");
        lastPlayerSegment = Long.MIN_VALUE;
        lastPlayerSegmentSignature = null;
        lastMatchedRoomId = "";
        lastMatchedRoomName = "";
        lastMatchedComponentCount = 0;
    }

    private void rememberScanResult(String stage,
                                    String result,
                                    long startedNanos,
                                    long playerSegment,
                                    DungeonCoreSignature signature,
                                    DungeonRoom room) {
        lastScanAtMillis = System.currentTimeMillis();
        lastScanDurationNanos = Math.max(0L, System.nanoTime() - startedNanos);
        lastScanStage = debugText(stage, "scan");
        lastScanResult = debugText(result, "(none)");
        lastPlayerSegment = playerSegment;
        lastPlayerSegmentSignature = signature;
        if (room == null) {
            lastMatchedRoomId = "";
            lastMatchedRoomName = "";
            lastMatchedComponentCount = 0;
            return;
        }
        lastMatchedRoomId = room.hasRoomId() ? room.roomId() : "<unmatched>";
        lastMatchedRoomName = room.displayName();
        lastMatchedComponentCount = room.segments().size();
    }

    private static String describeZoneForDebug(Zone zone) {
        if (zone == null) return "(none)";
        return zone.displayName() + " (" + zone.id() + ")";
    }

    private static String debugText(String text, String fallback) {
        if (text == null) return fallback;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static final class DebugSnapshot {
        public final boolean inDungeon;
        public final boolean roomPresent;
        public final String roomName;
        public final String roomId;
        public final String roomType;
        public final String roomShape;
        public final String roomDirection;
        public final int physicalCornerX;
        public final int physicalCornerZ;
        public final List<Long> roomSegments;
        public final DungeonDetectionConfidence confidence;
        public final String directionOverride;
        public final String effectiveDirection;
        public final int knownRoomCacheSize;
        public final int coreSignatureCacheSize;
        public final long lastScanAtMillis;
        public final long lastScanDurationNanos;
        public final String lastScanStage;
        public final String lastScanResult;
        public final long lastPlayerSegment;
        public final DungeonCoreSignature lastPlayerSegmentSignature;
        public final String lastMatchedRoomId;
        public final String lastMatchedRoomName;
        public final int lastMatchedComponentCount;

        private DebugSnapshot(boolean inDungeon,
                              boolean roomPresent,
                              String roomName,
                              String roomId,
                              String roomType,
                              String roomShape,
                              String roomDirection,
                              int physicalCornerX,
                              int physicalCornerZ,
                              List<Long> roomSegments,
                              DungeonDetectionConfidence confidence,
                              String directionOverride,
                              String effectiveDirection,
                              int knownRoomCacheSize,
                              int coreSignatureCacheSize,
                              long lastScanAtMillis,
                              long lastScanDurationNanos,
                              String lastScanStage,
                              String lastScanResult,
                              long lastPlayerSegment,
                              DungeonCoreSignature lastPlayerSegmentSignature,
                              String lastMatchedRoomId,
                              String lastMatchedRoomName,
                              int lastMatchedComponentCount) {
            this.inDungeon = inDungeon;
            this.roomPresent = roomPresent;
            this.roomName = debugText(roomName, "(none)");
            this.roomId = debugText(roomId, "(none)");
            this.roomType = debugText(roomType, "(none)");
            this.roomShape = debugText(roomShape, "(none)");
            this.roomDirection = debugText(roomDirection, "(none)");
            this.physicalCornerX = physicalCornerX;
            this.physicalCornerZ = physicalCornerZ;
            this.roomSegments = roomSegments == null ? List.of() : List.copyOf(roomSegments);
            this.confidence = confidence == null ? DungeonDetectionConfidence.UNKNOWN : confidence;
            this.directionOverride = debugText(directionOverride, "(none)");
            this.effectiveDirection = debugText(effectiveDirection, "(none)");
            this.knownRoomCacheSize = knownRoomCacheSize;
            this.coreSignatureCacheSize = coreSignatureCacheSize;
            this.lastScanAtMillis = lastScanAtMillis;
            this.lastScanDurationNanos = lastScanDurationNanos;
            this.lastScanStage = debugText(lastScanStage, "(none)");
            this.lastScanResult = debugText(lastScanResult, "(none)");
            this.lastPlayerSegment = lastPlayerSegment;
            this.lastPlayerSegmentSignature = lastPlayerSegmentSignature;
            this.lastMatchedRoomId = debugText(lastMatchedRoomId, "(none)");
            this.lastMatchedRoomName = debugText(lastMatchedRoomName, "(none)");
            this.lastMatchedComponentCount = lastMatchedComponentCount;
        }
    }

    private void fireRoomChanged(DungeonRoom room) {
        if (config.debugLogRoomChanges()) {
            Waypointer.LOGGER.info("Dungeon room changed -> {}", room == null ? "<none>" : room.identityKey());
        }
        for (Consumer<DungeonRoom> l : listeners) l.accept(room);
    }
}
