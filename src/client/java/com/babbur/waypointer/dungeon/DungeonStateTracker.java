package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Finds the dungeon room the player is currently in.
 *
 * <p>Room-center chunks are scanned once when they load. The core hash identifies
 * the room layout, adjacent segments are joined, and a blue terracotta marker
 * gives the room's rotation and origin.
 *
 * <p>Each tick only looks up the player's segment in the cache. It does not rescan
 * chunks or read the dungeon map.
 */
public final class DungeonStateTracker {

    private static final int ODIN_ROOM_SIZE_SHIFT = 5;
    private static final int ODIN_ROOM_CENTER_START = -185;
    private static final int ODIN_ROOM_CENTER_TO_SEGMENT_CORNER_OFFSET = 15;
    private static final int ODIN_ROOM_CENTER_CHUNK_MIN = -12;
    private static final int ODIN_ROOM_CENTER_CHUNK_MAX = -2;
    private static final int ROOM_CENTER_CHUNK_OFFSET = 7;

    private final ActiveGroupManager manager;
    private final DungeonConfig config;
    private final DungeonRoomResolver resolver = new DungeonRoomResolver();
    private final List<Consumer<DungeonRoom>> listeners = new ArrayList<>();
    private final Map<Long, LevelChunk> pendingChunks = new HashMap<>();

    private volatile boolean inDungeon;
    private volatile DungeonRoom currentRoom;
    private ClientLevel observedLevel;

    private long dungeonTick;
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
        listeners.add(Objects.requireNonNull(l, "listener"));
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

    public List<DungeonRoom> knownRooms() {
        return resolver.knownRooms();
    }

    public Direction directionOverride() {
        return resolver.directionOverride(currentRoom);
    }

    public DebugSnapshot debugSnapshot() {
        DungeonRoom room = currentRoom;
        Direction directionOverride = directionOverride();
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
                resolver.knownSegmentCount(),
                resolver.signatureCount(),
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
        resolver.cache(updated);
        fireRoomChanged(updated);
    }

    public boolean setDirectionOverride(Direction dir) {
        DungeonRoom prev = currentRoom;
        if (prev == null) return false;

        DungeonRoom rotated = resolver.withDirectionOverride(prev, dir);
        currentRoom = rotated;
        resolver.cache(rotated);
        fireRoomChanged(rotated);
        return true;
    }

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
        DungeonRoom built = resolver.roomAt(playerSegment);
        boolean suppressedByRoof = false;
        if (built != null && resolver.isAboveRoof(playerSegment, player.getY())) {
            built = currentRoom;
            suppressedByRoof = true;
        }
        DungeonCoreSignature signature = resolver.signatureAt(playerSegment);
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

    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        rememberLevel(level);
        if (chunk == null) return;
        if (inDungeon && resolver.hasUnresolvedAssemblies()) resolver.requestRetry();
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
        resolver.clear();
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
        resolver.scan(chunk, segment);
    }

    private void retryUnresolvedAssemblies(ClientLevel level) {
        if (level == null) return;
        retryUnresolvedAssemblies(new DungeonRoomBlockLookup(level));
    }

    void retryUnresolvedAssemblies(DungeonRoomData.BlockLookup lookup) {
        resolver.retry(lookup, dungeonTick);
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
        for (Consumer<DungeonRoom> listener : List.copyOf(listeners)) {
            try {
                listener.accept(room);
            } catch (RuntimeException failure) {
                Waypointer.LOGGER.error("Dungeon room listener failed", failure);
            }
        }
    }
}
