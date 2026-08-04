package com.babbur.waypointer.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonCoreSignature;
import com.babbur.waypointer.dungeon.DungeonDetectionConfidence;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRouteSession;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import com.babbur.waypointer.render.RenderDiagnostics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session-only live diagnostics monitor.
 * It observes existing route, renderer, and dungeon snapshots and never scans the world itself.
 */
public final class DeveloperModeMonitor implements HudElement {

    static final int SAMPLE_INTERVAL_TICKS = 10;
    static final int UNRESOLVED_GRACE_TICKS = 100;
    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "developer_mode");
    private static final int MAX_QUEUED_WRITES = 256;
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final int RETAINED_LOG_FILES = 5;
    private static final int MAX_HUD_ROUTES = 2;
    private static final int HUD_X = 5;
    private static final int HUD_Y = 5;
    private static final int HUD_PADDING = 4;
    private static final int HUD_ROW_GAP = 2;
    private static final int HUD_BACKGROUND = 0xB0000000;
    private static final int HUD_ACCENT = 0xFF55FFFF;
    private static final int HUD_TEXT = 0xFFE6E6E6;
    private static final int HUD_DIM = 0xFFAAAAAA;
    private static final int HUD_WARN = 0xFFFFAA00;
    private static final int HUD_ERROR = 0xFFFF5555;
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
    private volatile List<HudRow> hudRows = List.of();
    private List<HudRow> renderedHudSource = List.of();
    private List<String> renderedHudText = List.of();
    private int renderedHudGuiWidth = -1;
    private int renderedHudContentWidth;
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
        // Match waypoint labels: the developer overlay disappears with F1.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, this);
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
        sampleTicks = SAMPLE_INTERVAL_TICKS - 1;
        RenderDiagnostics.setDeveloperModeCaptureEnabled(true);
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
        hudRows = List.of();
        RenderDiagnostics.setDeveloperModeCaptureEnabled(false);
        return file;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker tick) {
        if (!enabled) return;

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        List<HudRow> rows = hudRows;
        if (rows.isEmpty()) return;

        refreshRenderedHudCache(font, graphics.guiWidth(), rows);

        int rowAdvance = font.lineHeight + HUD_ROW_GAP;
        int panelWidth = renderedHudContentWidth + HUD_PADDING * 2;
        int panelHeight = rows.size() * rowAdvance - HUD_ROW_GAP + HUD_PADDING * 2;
        graphics.fill(HUD_X, HUD_Y, HUD_X + panelWidth, HUD_Y + panelHeight, HUD_BACKGROUND);
        graphics.fill(HUD_X, HUD_Y, HUD_X + 2, HUD_Y + panelHeight, HUD_ACCENT);

        int textX = HUD_X + HUD_PADDING;
        int textY = HUD_Y + HUD_PADDING;
        for (int i = 0; i < rows.size(); i++) {
            graphics.text(font, renderedHudText.get(i), textX, textY, rows.get(i).color, true);
            textY += rowAdvance;
        }
    }

    private void refreshRenderedHudCache(Font font, int guiWidth, List<HudRow> rows) {
        if (rows == renderedHudSource && guiWidth == renderedHudGuiWidth) return;

        int maxTextWidth = Math.max(1, guiWidth - HUD_X - HUD_PADDING * 2 - 2);
        List<String> text = new ArrayList<>(rows.size());
        int contentWidth = 0;
        for (HudRow row : rows) {
            String clipped = clipToWidth(font, row.text, maxTextWidth);
            text.add(clipped);
            contentWidth = Math.max(contentWidth, font.width(clipped));
        }
        renderedHudSource = rows;
        renderedHudGuiWidth = guiWidth;
        renderedHudText = List.copyOf(text);
        renderedHudContentWidth = contentWidth;
    }

    private List<HudRow> buildHudRows(Minecraft minecraft,
                                      DungeonStateTracker.DebugSnapshot snapshot) {
        List<HudRow> rows = new ArrayList<>();
        rows.add(new HudRow("WAYPOINTER DEV MODE", HUD_ACCENT));

        LocalPlayer player = minecraft.player;
        String position = player == null
                ? "(no player)"
                : String.format(Locale.ROOT, "%.1f, %.1f, %.1f | chunk %d,%d",
                        player.getX(), player.getY(), player.getZ(),
                        player.chunkPosition().x(), player.chunkPosition().z());
        rows.add(new HudRow("Zone: " + describeZone(manager.currentZone()) + " | XYZ: " + position, HUD_TEXT));

        if (!snapshot.inDungeon) {
            rows.add(new HudRow("Dungeon: outside detected dungeon", HUD_DIM));
        } else if (!dungeonConfig.enabled()) {
            rows.add(new HudRow("Dungeon: detection disabled", HUD_ERROR));
        } else if (!snapshot.roomPresent) {
            rows.add(new HudRow("Room: unresolved | segment " + segmentLabel(snapshot.lastPlayerSegment), HUD_WARN));
        } else {
            rows.add(new HudRow("Room: " + snapshot.roomName + " [" + snapshot.roomId + "] | "
                    + snapshot.roomType + "/" + snapshot.roomShape + "/" + snapshot.effectiveDirection
                    + " | " + DebugSignals.detectionConfidenceLabel(snapshot.confidence), HUD_TEXT));
        }

        if (snapshot.inDungeon) {
            rows.add(new HudRow("Scan: " + snapshot.lastScanStage + " -> " + snapshot.lastScanResult
                    + " | " + formatNanos(snapshot.lastScanDurationNanos)
                    + " | caches room/core " + snapshot.knownRoomCacheSize
                    + "/" + snapshot.coreSignatureCacheSize, HUD_DIM));
            addDungeonRouteRow(rows);
            addRenderDiagnosticsRow(rows);
        }

        List<WaypointGroup> active = manager.activeGroups();
        rows.add(new HudRow("Routes: " + active.size() + " active / " + manager.allGroups().size() + " total", HUD_DIM));
        for (int i = 0; i < Math.min(active.size(), MAX_HUD_ROUTES); i++) {
            for (String line : activeRouteHudLines(active.get(i))) {
                rows.add(new HudRow(line, line.startsWith("  Target") ? HUD_TEXT : HUD_DIM));
            }
        }
        if (active.size() > MAX_HUD_ROUTES) {
            rows.add(new HudRow("  ...and " + (active.size() - MAX_HUD_ROUTES) + " more active route(s)", HUD_DIM));
        }

        String logName = logFile == null ? "(none)" : logFile.getFileName().toString();
        int sessionColor = !writeFailure.isBlank() ? HUD_ERROR : anomalies > 0 ? HUD_WARN : HUD_DIM;
        String session = "Session: visits=" + roomVisits + " rooms=" + uniqueRooms.size()
                + " anomalies=" + anomalies + " queued=" + queuedWrites.get() + " | " + logName;
        if (!writeFailure.isBlank()) session += " | write error: " + writeFailure;
        rows.add(new HudRow(session, sessionColor));
        return rows;
    }

    private static void addDungeonRouteRow(List<HudRow> rows) {
        DungeonRouteSession.DebugSnapshot route = DebugSignals.dungeonDebugSnapshot().routeSession;
        if (route == null) return;
        rows.add(new HudRow("Dungeon route: " + route.roomKey + " | secret=" + route.currentSecretIndex
                + " found/upcoming=" + route.foundCount + "/" + route.upcomingCount
                + " progress=" + route.totalProgressWaypoints
                + (route.complete ? " | complete" : ""), HUD_DIM));
    }

    private static void addRenderDiagnosticsRow(List<HudRow> rows) {
        RenderDiagnostics.Snapshot render = RenderDiagnostics.snapshot();
        if (render.updatedAtEpochMillis() <= 0L) {
            rows.add(new HudRow("Render: waiting for the next captured frame", HUD_DIM));
            return;
        }
        if (render.groups().isEmpty()) {
            rows.add(new HudRow("Render: no dungeon path candidates", HUD_DIM));
            return;
        }

        RenderDiagnostics.GroupSnapshot group = render.groups().getFirst();
        String line = "Render " + compactText(group.groupName(), 24) + " [" + shortId(group.groupId())
                + "]: " + group.finalOutcome();
        RenderDiagnostics.PathSnapshot path = group.path();
        if (path != null && !"not attempted".equals(path.result())) {
            line += " | path=" + path.result() + "/" + path.cacheStatus()
                    + " " + path.pointCount() + "pt "
                    + String.format(Locale.ROOT, "%.2fms", path.computeTimeMillis());
        }
        if (render.groups().size() > 1) line += " | +" + (render.groups().size() - 1) + " group(s)";
        rows.add(new HudRow(line, group.finalOutcome().startsWith("nothing submitted") ? HUD_WARN : HUD_DIM));
    }

    static List<String> activeRouteHudLines(WaypointGroup group) {
        if (group == null) return List.of();
        String tags = group.temp() ? " temp" : group.runtimeOnly() ? " runtime" : "";
        String progress = group.isEmpty()
                ? "empty"
                : group.isComplete() ? "complete" : "index=" + group.currentIndex();
        String header = "Route: " + compactText(group.name(), 32) + " [" + shortId(group.id()) + "]"
                + " | " + group.loadMode() + tags + " | " + progress + "/" + group.size();

        Waypoint target = group.current();
        if (target == null) return List.of(header);
        double radius = target.customRadius() > 0.0 ? target.customRadius() : group.defaultRadius();
        String name = target.hasName() ? " \"" + compactText(target.name(), 24) + "\"" : "";
        String detail = String.format(Locale.ROOT,
                "  Target %s%s | xyz=%d,%d,%d p16=%d,%d,%d | r=%.2f color=#%06X flags=0x%08X",
                group.displayIndexLabel(group.currentIndex()), name,
                target.x(), target.y(), target.z(),
                target.preciseX(), target.preciseY(), target.preciseZ(),
                radius, target.color() & 0xFFFFFF, target.flags());
        return List.of(header, detail);
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String compactText(String value, int maxChars) {
        if (value == null || value.isBlank()) return "(unnamed)";
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= maxChars
                ? oneLine
                : oneLine.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String clipToWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        if (font.width(suffix) > maxWidth) return "";
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.width(value.substring(0, mid)) + font.width(suffix) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, low) + suffix;
    }

    private static String formatNanos(long nanos) {
        if (nanos < 1_000L) return nanos + "ns";
        if (nanos < 1_000_000L) return String.format(Locale.ROOT, "%.1fus", nanos / 1_000.0);
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    private record HudRow(String text, int color) {}

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
        try {
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
        } finally {
            hudRows = List.copyOf(buildHudRows(minecraft, snapshot));
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
        event.add("renderer", GSON.toJsonTree(RenderDiagnostics.snapshot()));

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
        if (segment == Long.MIN_VALUE) return "(none)";
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
