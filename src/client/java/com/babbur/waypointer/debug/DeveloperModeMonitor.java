package com.babbur.waypointer.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonCoreSignature;
import com.babbur.waypointer.dungeon.DungeonDetectionConfidence;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRouteSession;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session-only dungeon diagnostics enabled through {@code /wp devmode}.
 * It observes the tracker's existing snapshots and never scans the world itself.
 */
public final class DeveloperModeMonitor {

    static final int SAMPLE_INTERVAL_TICKS = 10;
    static final int UNRESOLVED_GRACE_TICKS = 100;
    private static final int MAX_QUEUED_WRITES = 256;
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final int RETAINED_LOG_FILES = 5;
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final DungeonStateTracker tracker;
    private final DungeonConfig dungeonConfig;
    private final ActiveGroupManager manager;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "waypointer-devmode-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queuedWrites = new AtomicInteger();
    private final Set<String> alertedProblems = new HashSet<>();
    private final Set<String> uniqueRooms = new HashSet<>();
    private final Map<Long, String> roomIdsBySegment = new HashMap<>();

    private volatile boolean enabled;
    private volatile Path logFile;
    private volatile String writeFailure = "";
    private boolean lastInDungeon;
    private List<Long> activeVisitSegments = List.of();
    private String activeRoomMetadata = "";
    private long unresolvedSegment = Long.MIN_VALUE;
    private int unresolvedTicks;
    private int sampleTicks;
    private int dungeonRun;
    private int roomVisits;
    private int anomalies;
    private long sequence;
    private volatile Path fullLogFile;
    private boolean queueWarningSent;
    private boolean writeWarningSent;
    private boolean logsPruned;

    public DeveloperModeMonitor(DungeonStateTracker tracker,
                                DungeonConfig dungeonConfig,
                                ActiveGroupManager manager) {
        this.tracker = tracker;
        this.dungeonConfig = dungeonConfig;
        this.manager = manager;
    }

    public void install() {
        tracker.addRoomListener(this::onRoomChanged);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public boolean enabled() {
        return enabled;
    }

    public Path logFile() {
        return logFile;
    }

    public synchronized int roomVisits() {
        return roomVisits;
    }

    public synchronized int uniqueRoomCount() {
        return uniqueRooms.size();
    }

    public synchronized int anomalyCount() {
        return anomalies;
    }

    public String writeFailure() {
        return writeFailure;
    }

    public synchronized Path enable() {
        if (enabled) return logFile;

        resetSessionState();
        Path directory = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("waypointer");
        Path file = directory.resolve("devmode-" + FILE_TIME.format(Instant.now()) + ".jsonl");
        try {
            Files.createDirectories(directory);
            if (!logsPruned) {
                pruneOldLogs(directory);
                logsPruned = true;
            }
            JsonObject start = baseEvent("devmode_start");
            start.addProperty("waypointerVersion", modVersion(Waypointer.MOD_ID));
            start.addProperty("minecraftVersion", modVersion("minecraft"));
            start.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
            Files.writeString(file, GSON.toJson(start) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create dev mode log: " + e.getMessage(), e);
        }

        logFile = file;
        enabled = true;
        writeReport("enabled");
        return file;
    }

    public synchronized Path disable() {
        Path file = logFile;
        if (!enabled) return file;

        JsonObject stop = baseEvent("devmode_stop");
        stop.addProperty("roomVisits", roomVisits);
        stop.addProperty("uniqueRooms", uniqueRooms.size());
        stop.addProperty("anomalies", anomalies);
        queueEvent(stop);
        enabled = false;
        return file;
    }

    public synchronized boolean writeReport(String reason) {
        if (!enabled) return false;
        queueEvent(richEvent("manual_report", reason, tracker.debugSnapshot(), Minecraft.getInstance()));
        return true;
    }

    public synchronized String statusLine() {
        if (!enabled) {
            return "Developer mode is off" + (logFile == null ? "." : "; last log: " + logFile);
        }
        String status = "Developer mode is on: " + roomVisits + " visit(s), "
                + uniqueRooms.size() + " unique room(s), " + anomalies + " anomaly(s); log: " + logFile;
        if (!writeFailure.isBlank()) status += "; write error: " + writeFailure;
        return status;
    }

    public synchronized Component statusComponent() {
        if (!enabled) {
            return logFile == null
                    ? Component.translatable("waypointer.debug.status.off")
                    : Component.translatable(
                            "waypointer.debug.status.off_last_log", logFile);
        }
        var status = Component.translatable(
                "waypointer.debug.status.on",
                roomVisits, uniqueRooms.size(), anomalies, logFile);
        if (!writeFailure.isBlank()) {
            status.append(Component.translatable(
                    "waypointer.debug.status.write_error", writeFailure));
        }
        return status;
    }

    public synchronized void flushAndShutdown() {
        if (enabled) disable();
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void onClientTick(Minecraft minecraft) {
        if (!enabled) return;
        if (++sampleTicks < SAMPLE_INTERVAL_TICKS) return;
        sampleTicks = 0;

        DungeonStateTracker.DebugSnapshot snapshot = tracker.debugSnapshot();
        observeDungeonContext(snapshot, minecraft);
        if (!snapshot.inDungeon) {
            clearActiveVisit();
            return;
        }
        if (!dungeonConfig.enabled()) {
            reportAnomaly("detection-disabled", "Dungeon room detection is disabled", snapshot, minecraft);
            clearActiveVisit();
            return;
        }

        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            observeUnresolved(snapshot, minecraft);
        } else {
            observeResolved(room, snapshot, minecraft);
        }
    }

    private synchronized void onRoomChanged(DungeonRoom room) {
        if (!enabled) return;
        Minecraft minecraft = Minecraft.getInstance();
        DungeonStateTracker.DebugSnapshot snapshot = tracker.debugSnapshot();
        observeDungeonContext(snapshot, minecraft);
        if (snapshot.inDungeon && room != null && dungeonConfig.enabled()) {
            observeResolved(room, snapshot, minecraft);
        }
    }

    private void observeDungeonContext(DungeonStateTracker.DebugSnapshot snapshot, Minecraft minecraft) {
        if (snapshot.inDungeon == lastInDungeon) return;
        lastInDungeon = snapshot.inDungeon;
        if (snapshot.inDungeon) {
            dungeonRun++;
            alertedProblems.clear();
            roomIdsBySegment.clear();
            clearActiveVisit();
        }
        queueEvent(richEvent(snapshot.inDungeon ? "dungeon_enter" : "dungeon_exit",
                snapshot.lastScanResult, snapshot, minecraft));
    }

    private void observeUnresolved(DungeonStateTracker.DebugSnapshot snapshot, Minecraft minecraft) {
        long segment = snapshot.lastPlayerSegment;
        if (segment == Long.MIN_VALUE || "over-roof suppressed".equals(snapshot.lastScanStage)) {
            unresolvedSegment = Long.MIN_VALUE;
            unresolvedTicks = 0;
            return;
        }
        if (segment != unresolvedSegment) {
            unresolvedSegment = segment;
            unresolvedTicks = SAMPLE_INTERVAL_TICKS;
            if (!activeVisitSegments.contains(segment)) {
                startVisit(List.of(segment), "", snapshot, "room_pending");
            }
            return;
        }
        unresolvedTicks += SAMPLE_INTERVAL_TICKS;
        if (unresolvedTicks < UNRESOLVED_GRACE_TICKS) return;

        DungeonCoreSignature signature = snapshot.lastPlayerSegmentSignature;
        String reason = unresolvedReason(signature);
        reportAnomaly("run:" + dungeonRun + ":unresolved:" + segment, reason, snapshot, minecraft);
    }

    private static String unresolvedReason(DungeonCoreSignature signature) {
        if (signature == null) {
            return "Room tile was not scanned after the detection grace period";
        }
        List<String> candidates = new ArrayList<>();
        for (DungeonRoomDefinition definition : DungeonRoomData.allDefinitions()) {
            if (definition.coreHashes().contains(signature.hash())) candidates.add(definition.id());
        }
        if (candidates.isEmpty()) {
            return "Unknown dungeon room core hash " + signature.hash();
        }
        if (candidates.size() > 1) {
            return "Ambiguous dungeon room core hash " + signature.hash()
                    + " matched " + String.join(", ", candidates);
        }
        return "Known room '" + candidates.getFirst()
                + "' could not resolve its shape or direction after the detection grace period";
    }

    private void observeResolved(DungeonRoom room,
                                 DungeonStateTracker.DebugSnapshot snapshot,
                                 Minecraft minecraft) {
        unresolvedSegment = Long.MIN_VALUE;
        unresolvedTicks = 0;
        List<Long> roomSegments = sortedSegments(room.segments());
        if (roomSegments.isEmpty() && snapshot.lastPlayerSegment != Long.MIN_VALUE) {
            roomSegments = List.of(snapshot.lastPlayerSegment);
        }
        String metadata = room.identityKey() + ":" + room.roomId() + ":" + room.confidence();
        boolean sameVisit = intersects(activeVisitSegments, roomSegments);
        if (!sameVisit) {
            startVisit(roomSegments, metadata, snapshot, "room_visit");
        } else if (!metadata.equals(activeRoomMetadata)) {
            JsonObject update = eventWithSnapshot(activeRoomMetadata.isBlank()
                    ? "room_resolved" : "room_updated", snapshot);
            addRoom(update, room);
            queueEvent(update);
            activeVisitSegments = roomSegments;
            activeRoomMetadata = metadata;
        }
        uniqueRooms.add(room.hasRoomId() ? room.roomId() : room.identityKey());

        List<String> problems = detectionProblems(room);
        if (!problems.isEmpty()) {
            String problem = String.join("; ", problems);
            reportAnomaly("run:" + dungeonRun + ":room:" + physicalVisitKey(roomSegments)
                    + ":" + problem, problem, snapshot, minecraft);
        }
        if (room.hasRoomId()) {
            for (long segment : room.segments()) {
                String previous = roomIdsBySegment.putIfAbsent(segment, room.roomId());
                if (previous != null && !previous.equals(room.roomId())) {
                    reportAnomaly("run:" + dungeonRun + ":identity-flip:" + segment,
                            "The same physical segment changed from room '" + previous
                                    + "' to '" + room.roomId() + "'",
                            snapshot, minecraft);
                }
            }
        }
        Zone zone = manager.currentZone();
        if (room.hasRoomId() && zone != null && !room.roomId().equals(zone.id())) {
            reportAnomaly("run:" + dungeonRun + ":bridge:"
                            + physicalVisitKey(roomSegments) + ":" + zone.id(),
                    "Detected room and active waypoint zone disagree", snapshot, minecraft);
        }
    }

    private void startVisit(List<Long> segments,
                            String metadata,
                            DungeonStateTracker.DebugSnapshot snapshot,
                            String eventType) {
        activeVisitSegments = segments == null ? List.of() : List.copyOf(segments);
        activeRoomMetadata = metadata == null ? "" : metadata;
        roomVisits++;
        JsonObject visit = eventWithSnapshot(eventType, snapshot);
        visit.addProperty("visit", roomVisits);
        visit.addProperty("visitSegments", physicalVisitKey(activeVisitSegments));
        queueEvent(visit);
    }

    static List<String> detectionProblems(DungeonRoom room) {
        List<String> problems = new ArrayList<>();
        if (room == null) {
            problems.add("No dungeon room was detected");
            return problems;
        }
        if (!room.hasRoomId()) problems.add("Detected room has no catalog identity");
        if (room.segments().isEmpty()) problems.add("Detected room has no physical segments");
        if (room.confidence() == DungeonDetectionConfidence.CORE_MATCHED) {
            problems.add("Core identity matched but the detected room shape did not");
        } else if (room.confidence() == DungeonDetectionConfidence.UNKNOWN
                || room.confidence() == DungeonDetectionConfidence.MAP_FALLBACK) {
            problems.add("Detected room has low confidence: " + room.confidence());
        }
        return problems;
    }

    private static List<Long> sortedSegments(List<Long> segments) {
        if (segments == null || segments.isEmpty()) return List.of();
        List<Long> sorted = new ArrayList<>(segments);
        sorted.sort(Long::compareTo);
        return List.copyOf(sorted);
    }

    static boolean intersects(List<Long> left, List<Long> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        for (long segment : left) {
            if (right.contains(segment)) return true;
        }
        return false;
    }

    private static String physicalVisitKey(List<Long> segments) {
        if (segments == null || segments.isEmpty()) return "(none)";
        StringBuilder key = new StringBuilder();
        for (long segment : segments) {
            if (!key.isEmpty()) key.append(';');
            key.append(segmentLabel(segment));
        }
        return key.toString();
    }

    private void reportAnomaly(String key,
                               String reason,
                               DungeonStateTracker.DebugSnapshot snapshot,
                               Minecraft minecraft) {
        if (!alertedProblems.add(key)) return;
        anomalies++;
        queueEvent(richEvent("detection_anomaly", reason, snapshot, minecraft));
        sendChat(Component.translatable(
                        "waypointer.debug.anomaly", reason, logFile)
                .withStyle(ChatFormatting.YELLOW));
    }

    private JsonObject richEvent(String type,
                                 String reason,
                                 DungeonStateTracker.DebugSnapshot snapshot,
                                 Minecraft minecraft) {
        JsonObject event = eventWithSnapshot(type, snapshot);
        event.addProperty("reason", reason == null ? "" : reason);
        event.addProperty("zone", describeZone(manager.currentZone()));
        event.addProperty("zoneBridge", DebugSignals.dungeonBridgeLine());
        event.addProperty("hypixelApi", DebugSignals.hypixelApiLine());
        event.addProperty("dungeonConfig", DebugSignals.dungeonConfigLine());
        event.addProperty("scoreboard", DebugSignals.scoreboardLine());

        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player != null) {
            JsonObject position = new JsonObject();
            position.addProperty("x", player.getX());
            position.addProperty("y", player.getY());
            position.addProperty("z", player.getZ());
            position.addProperty("chunkX", player.chunkPosition().x());
            position.addProperty("chunkZ", player.chunkPosition().z());
            event.add("playerPosition", position);
        }

        DebugSignals.DungeonDebugSnapshot debug = DebugSignals.dungeonDebugSnapshot();
        DungeonRouteSession.DebugSnapshot route = debug.routeSession;
        if (route != null) {
            JsonObject routeJson = new JsonObject();
            routeJson.addProperty("roomKey", route.roomKey);
            routeJson.addProperty("currentSecretIndex", route.currentSecretIndex);
            routeJson.addProperty("totalProgressWaypoints", route.totalProgressWaypoints);
            routeJson.addProperty("foundCount", route.foundCount);
            routeJson.addProperty("upcomingCount", route.upcomingCount);
            routeJson.addProperty("complete", route.complete);
            event.add("route", routeJson);
        }
        return event;
    }

    private JsonObject eventWithSnapshot(String type, DungeonStateTracker.DebugSnapshot snapshot) {
        JsonObject event = baseEvent(type);
        event.addProperty("inDungeon", snapshot.inDungeon);
        event.addProperty("scanStage", snapshot.lastScanStage);
        event.addProperty("scanResult", snapshot.lastScanResult);
        event.addProperty("scanMicros", snapshot.lastScanDurationNanos / 1_000L);
        event.addProperty("playerSegment", snapshot.lastPlayerSegment == Long.MIN_VALUE
                ? "(none)" : segmentLabel(snapshot.lastPlayerSegment));
        event.addProperty("knownRoomCacheSize", snapshot.knownRoomCacheSize);
        event.addProperty("coreSignatureCacheSize", snapshot.coreSignatureCacheSize);
        DungeonCoreSignature signature = snapshot.lastPlayerSegmentSignature;
        if (signature != null) {
            JsonObject core = new JsonObject();
            core.addProperty("hash", signature.hash());
            core.addProperty("topY", signature.topY());
            core.addProperty("sampleCount", signature.sampleCount());
            event.add("core", core);
        }
        DungeonRoom room = tracker.currentRoom();
        if (room != null) addRoom(event, room);
        return event;
    }

    private JsonObject baseEvent(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("schema", 1);
        event.addProperty("sequence", sequence++);
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("event", type);
        return event;
    }

    private static void addRoom(JsonObject event, DungeonRoom room) {
        JsonObject roomJson = new JsonObject();
        roomJson.addProperty("id", room.roomId());
        roomJson.addProperty("name", room.displayName());
        roomJson.addProperty("type", room.type().name());
        roomJson.addProperty("shape", room.shape().name());
        roomJson.addProperty("direction", room.direction().name());
        roomJson.addProperty("confidence", room.confidence().name());
        roomJson.addProperty("cornerX", room.physicalCornerX());
        roomJson.addProperty("cornerZ", room.physicalCornerZ());
        JsonArray segments = new JsonArray();
        for (long segment : room.segments()) segments.add(segmentLabel(segment));
        roomJson.add("segments", segments);
        event.add("room", roomJson);
    }

    private void queueEvent(JsonObject event) {
        Path file = logFile;
        if (file == null || file.equals(fullLogFile)) return;
        if (queuedWrites.incrementAndGet() > MAX_QUEUED_WRITES) {
            queuedWrites.decrementAndGet();
            if (!queueWarningSent) {
                queueWarningSent = true;
                sendChat(Component.translatable("waypointer.debug.writer_queue_full")
                        .withStyle(ChatFormatting.RED));
            }
            return;
        }
        String line = GSON.toJson(event) + System.lineSeparator();
        writer.execute(() -> {
            try {
                appendLine(file, line);
            } finally {
                queuedWrites.decrementAndGet();
            }
        });
    }

    private void appendLine(Path file, String line) {
        if (file.equals(fullLogFile)) return;
        try {
            long currentSize = Files.exists(file) ? Files.size(file) : 0L;
            if (currentSize + line.getBytes(StandardCharsets.UTF_8).length > MAX_LOG_BYTES) {
                fullLogFile = file;
                sendChat(Component.translatable("waypointer.debug.log_size_limit")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            writeFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Waypointer.LOGGER.error("Developer mode log write failed", e);
            if (!writeWarningSent) {
                writeWarningSent = true;
                sendChat(Component.translatable("waypointer.debug.write_failed")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private void resetSessionState() {
        lastInDungeon = false;
        activeVisitSegments = List.of();
        activeRoomMetadata = "";
        unresolvedSegment = Long.MIN_VALUE;
        unresolvedTicks = 0;
        sampleTicks = 0;
        dungeonRun = 0;
        roomVisits = 0;
        anomalies = 0;
        sequence = 0L;
        fullLogFile = null;
        queueWarningSent = false;
        writeWarningSent = false;
        writeFailure = "";
        alertedProblems.clear();
        uniqueRooms.clear();
        roomIdsBySegment.clear();
    }

    private void clearActiveVisit() {
        activeVisitSegments = List.of();
        activeRoomMetadata = "";
        unresolvedSegment = Long.MIN_VALUE;
        unresolvedTicks = 0;
    }

    private static void pruneOldLogs(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            List<Path> logs = files
                    .filter(path -> path.getFileName().toString().startsWith("devmode-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong(DeveloperModeMonitor::lastModified).reversed())
                    .toList();
            for (int i = RETAINED_LOG_FILES - 1; i < logs.size(); i++) {
                Files.deleteIfExists(logs.get(i));
            }
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("not installed");
    }

    private static String segmentLabel(long segment) {
        return DungeonRoom.segmentX(segment) + "," + DungeonRoom.segmentZ(segment);
    }

    private static String describeZone(Zone zone) {
        return zone == null ? "(none)" : zone.displayName() + " (" + zone.id() + ")";
    }

    private static void sendChat(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
            }
        });
    }
}
