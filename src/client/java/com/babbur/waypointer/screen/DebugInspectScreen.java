package com.babbur.waypointer.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.codec.DecodeDebug;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.debug.ConfigChangeHistory;
import com.babbur.waypointer.debug.DebugEventLog;
import com.babbur.waypointer.debug.DebugLogTail;
import com.babbur.waypointer.debug.DebugReportExport;
import com.babbur.waypointer.debug.DebugSignals;
import com.babbur.waypointer.debug.PerformanceStats;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.render.RenderDiagnostics;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.dungeon.DungeonCoreSignature;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomZoneBridge;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.FOOTER_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.ROW_H;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

public final class DebugInspectScreen extends Screen {


    private sealed interface Row {
        record Section(String title, DebugReportExport.Category category) implements Row {}
        record KV(String key, String value) implements Row {}
        record KVDim(String key, String value) implements Row {}
        record KVWarn(String key, String value) implements Row {}
        record Bit(int bit, String label, boolean set) implements Row {}
        record BitNote(String text) implements Row {}
        record PoolEntry(int index, String text) implements Row {}
        record WP(DecodeDebug.WaypointDebug wp) implements Row {}
        record Blank() implements Row {}
    }

    private record SectionAnchor(String label, int rowIndex) {}

    private static final int SCROLL_ROWS_PER_NOTCH = 3;
    private static final int DEBUG_SIDEBAR_W = 208;
    private static final int DEBUG_MAIN_MIN_W = 100;

    private static final long FEEDBACK_MS = 1500L;

    private static final int KEY_COL_W = 140;

    private static final int BIT_LABEL_OFFSET = 30;

    private static final int POOL_CONTENT_OFFSET = 30;

    private static final int ERROR_TONE = 0xFFCA7A7A;
    private static final int SUCCESS_TONE = 0xFF8BD49C;
    private static final int WARN_TONE = 0xFFE6C07B;
    private static final int NUMBER_TONE = 0xFF82AAFF;
    private static final int STRING_TONE = 0xFFC3E88D;
    private static final int HEX_TONE = 0xFFFFCB6B;
    private static final int KEYWORD_TONE = 0xFFC792EA;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;

    private DecodeDebug debug;
    private PerformanceStats performanceStats;
    private String lastError;
    private String codecError;
    private final List<Row> rows = new ArrayList<>();
    private final List<SectionAnchor> sections = new ArrayList<>();
    private int scrollRows;
    private int selectedSection;
    private int sidebarScrollRows;
    private int sidebarVisibleRows;

    private Button copyButton;
    private long copyFeedbackUntil;
    private long rendererCaptureRequestedAt;
    private boolean awaitingFreshRendererCapture;

    private int sidebarX1, sidebarX2, sidebarContentTop;
    private int mainX1, mainX2, mainTop, mainBottom;
    private int visibleRowCount;

    public DebugInspectScreen(Screen parent, ActiveGroupManager manager,
                              WaypointerConfig config) {
        super(Component.translatable("waypointer.screen.debug.title"));
        this.parent = parent;
        this.manager = manager;
        this.config = config;
    }

    public static void open(Screen parent) {
        MinecraftCompat.setScreen(Minecraft.getInstance(), new DebugInspectScreen(parent));
    }

    public DebugInspectScreen(Screen parent) {
        this(parent, null, null);
    }

    public static void open(Screen parent, ActiveGroupManager manager,
                            WaypointerConfig config) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new DebugInspectScreen(parent, manager, config));
    }


    @Override
    protected void init() {
        if (manager != null && config != null) {
            RenderDiagnostics.setDetailedCaptureEnabled(true);
            rendererCaptureRequestedAt = System.currentTimeMillis();
            awaitingFreshRendererCapture = true;
        }
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        left.add(new GuiTokens.ButtonSpec(
                Component.translatable("waypointer.screen.debug.refresh").getString(),
                this::loadCombinedReport));
        if (config != null) {
            left.add(new GuiTokens.ButtonSpec(
                    Component.translatable("waypointer.screen.debug.perf_test").getString(),
                    88, this::openPerfStressTest));
        }
        String copyLabel = Component.translatable(
                copyFeedbackUntil > System.currentTimeMillis()
                        ? "waypointer.screen.debug.copied"
                        : "waypointer.screen.debug.copy_report").getString();
        left.add(new GuiTokens.ButtonSpec(copyLabel, this::openCopyConfirmation));
        GuiTokens.ButtonSpec back = new GuiTokens.ButtonSpec(
                Component.translatable("gui.back").getString(), this::onClose);

        int footerY = height - FOOTER_H;
        GuiTokens.layoutFooter(width, footerY, left, back,
                b -> {
            if (Component.translatable("waypointer.screen.debug.copy_report").getString()
                            .contentEquals(b.getMessage().getString())
                    || Component.translatable("waypointer.screen.debug.copied").getString()
                            .contentEquals(b.getMessage().getString())) {
                copyButton = b;
            }
            addRenderableWidget(b);
        }, font);

        if (rows.isEmpty() && lastError == null) {
            loadCombinedReport();
        }
    }


    private void openPerfStressTest() {
        MinecraftCompat.setScreen(minecraft, SettingsScreen.atSetting(this, config,
                WaypointerClient.dungeonConfig(), SettingsCatalog.ACTION_PERF_TEST));
    }

    private void resetReportState() {
        this.debug = null;
        this.performanceStats = null;
        this.codecError = null;
        this.lastError = null;
        this.rows.clear();
        this.sections.clear();
        this.scrollRows = 0;
        this.selectedSection = 0;
        this.sidebarScrollRows = 0;
    }

    private void loadCombinedReport() {
        resetReportState();

        buildSafely("Report Summary", this::buildReportSummary);
        buildSafely("PC Specs", DebugReportExport.Category.PC_SPECS, this::buildPcSpecsReport);
        buildSafely("Active Mods and Versions", DebugReportExport.Category.ACTIVE_MODS,
                this::buildActiveModsReport);

        if (manager == null || config == null) {
            addSection(rows, sections, "Performance Snapshot", "unavailable");
            rows.add(new Row.KVWarn("Unavailable",
                    "Open this screen through /wp debug to capture live Waypointer state."));
        } else {
            buildSafely("Server, Player, and Location", DebugReportExport.Category.SERVER_CONTEXT,
                    this::buildServerContextReport);
            buildSafely("Storage Health", this::buildStorageHealthReport);
            buildSafely("Performance Snapshot", this::buildLivePerformanceReport);
            buildSafely("Settings and Recent Changes", DebugReportExport.Category.SETTINGS_AND_CHANGES,
                    this::buildSettingsReport);
            buildSafely("Tracer and Dungeon Path Settings", DebugReportExport.Category.SETTINGS_AND_CHANGES,
                    this::buildTracerAndPathSettingsReport);
            buildSafely("Dungeon Entry Path Outcomes", DebugReportExport.Category.ROUTES_AND_WAYPOINTS,
                    this::buildRenderDiagnosticsReport);
            buildSafely("Keybinds", DebugReportExport.Category.SETTINGS_AND_CHANGES,
                    this::buildKeybindReport);
            buildSafely("Active Routes and Waypoints", DebugReportExport.Category.ROUTES_AND_WAYPOINTS,
                    this::buildActiveRoutesReport);
        }

        buildSafely("Dungeon Diagnostics", DebugReportExport.Category.SERVER_CONTEXT,
                () -> buildDungeonDiagnosticsReport(DebugSignals.dungeonDebugSnapshot(), rows, sections));
        buildSafely("Recent Settings Changes", DebugReportExport.Category.SETTINGS_AND_CHANGES,
                this::buildRecentSettingsChangesReport);
        buildSafely("Recent Logs and Activity", DebugReportExport.Category.RECENT_LOGS_AND_ACTIVITY,
                this::buildRecentLogsAndActivityReport);
        loadClipboardReport();
    }

    private void loadClipboardReport() {
        String text;
        try {
            text = minecraft.keyboardHandler.getClipboard();
        } catch (Throwable error) {
            this.codecError = "Clipboard could not be read: " + error.getClass().getSimpleName();
            buildCodecClipboardReport(rows, sections, codecError);
            return;
        }
        if (text == null || text.isBlank()) {
            this.codecError = "Clipboard is empty. Copy a " + WaypointCodec.MAGIC
                    + " export to inspect its codec payload here.";
            buildCodecClipboardReport(rows, sections, codecError);
            return;
        }
        String trimmed = text.trim();
        if (!WaypointCodec.isCodecString(trimmed)) {
            this.codecError = "Clipboard does not start with " + WaypointCodec.MAGIC
                    + ". Copy a Waypointer export string to inspect codec details.";
            buildCodecClipboardReport(rows, sections, codecError);
            return;
        }

        try {
            this.debug = WaypointCodec.debugDecode(trimmed);
            buildReport(this.debug, rows, sections);
        } catch (IllegalArgumentException e) {
            this.codecError = "Decode failed: " + e.getMessage();
            buildCodecClipboardReport(rows, sections, codecError);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (awaitingFreshRendererCapture
                && RenderDiagnostics.lastUpdatedAtEpochMillis() >= rendererCaptureRequestedAt) {
            awaitingFreshRendererCapture = false;
            loadCombinedReport();
        }
    }

    private void buildSafely(String label, Runnable builder) {
        buildSafely(label, DebugReportExport.Category.CORE, builder);
    }

    private void buildSafely(String label, DebugReportExport.Category category, Runnable builder) {
        try {
            builder.run();
        } catch (Throwable error) {
            addSection(rows, sections, label + " (Unavailable)", "capture failed", category);
            rows.add(new Row.KVWarn("Reason", error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + oneLine(error.getMessage()))));
        }
    }

    private void buildReportSummary() {
        addSection(rows, sections, "Troubleshooting Report", "schema 1");
        rows.add(new Row.KV("Captured", java.time.Instant.now().toString()));
        rows.add(new Row.KV("Report schema", "1"));
        rows.add(new Row.KV("Waypointer", loadedModVersion(Waypointer.MOD_ID)));
        rows.add(new Row.KV("Minecraft", loadedModVersion("minecraft")));
        rows.add(new Row.KV("Fabric Loader", loadedModVersion("fabricloader")));
        rows.add(new Row.KVDim("Command", "/wp debug"));
        rows.add(new Row.KVDim("Privacy", "Sensitive sections require confirmation before copying."));
    }

    private void buildPcSpecsReport() {
        addSection(rows, sections, "PC Specs", "review before sharing",
                DebugReportExport.Category.PC_SPECS);
        Runtime runtime = Runtime.getRuntime();
        rows.add(new Row.KV("Operating system", oneLine(System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.version", ""))));
        rows.add(new Row.KV("Architecture", System.getProperty("os.arch", "unknown")));
        rows.add(new Row.KV("Java", System.getProperty("java.version", "unknown")
                + " (" + System.getProperty("java.vendor", "unknown") + ")"));
        String cpuModel = System.getenv("PROCESSOR_IDENTIFIER");
        rows.add(new Row.KV("CPU", cpuModel == null || cpuModel.isBlank()
                ? "model unavailable; " + runtime.availableProcessors() + " logical processors"
                : oneLine(cpuModel)));
        rows.add(new Row.KV("Logical CPUs", String.valueOf(runtime.availableProcessors())));
        try {
            var operatingSystem = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
                rows.add(new Row.KV("System memory", formatBytes(extended.getTotalMemorySize())));
            }
        } catch (Throwable ignored) {
            rows.add(new Row.KVWarn("System memory", "Unavailable"));
        }
        rows.add(new Row.KV("JVM memory", formatBytes(runtime.totalMemory() - runtime.freeMemory())
                + " used / " + formatBytes(runtime.maxMemory()) + " max"));

        String gpuVendor = "unavailable";
        String gpuBackend = "unavailable";
        String gpuImplementation = "unavailable";
        try {
            var device = RenderSystem.tryGetDevice();
            if (device != null) {
                MinecraftCompat.GpuInfo gpu = MinecraftCompat.gpuInfo(device);
                gpuVendor = oneLine(gpu.vendor());
                gpuBackend = oneLine(gpu.backend());
                gpuImplementation = oneLine(gpu.implementation());
            }
        } catch (Throwable ignored) {
        }
        rows.add(new Row.KV("GPU vendor", gpuVendor));
        rows.add(new Row.KV("GPU backend", gpuBackend));
        rows.add(new Row.KVDim("GPU", gpuImplementation));

        try {
            rows.add(new Row.KV("Window", minecraft.getWindow().getWidth() + "x"
                    + minecraft.getWindow().getHeight() + " physical, "
                    + minecraft.getWindow().getGuiScaledWidth() + "x"
                    + minecraft.getWindow().getGuiScaledHeight() + " GUI"));
            rows.add(new Row.KV("VSync", minecraft.options.enableVsync().get() ? "on" : "off"));
            rows.add(new Row.KV("FPS limit", String.valueOf(minecraft.options.framerateLimit().get())));
        } catch (Throwable ignored) {
            rows.add(new Row.KVWarn("Video settings", "Unavailable"));
        }
    }

    private void buildActiveModsReport() {
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator
                .comparing((ModContainer mod) -> mod.getMetadata().getId(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(mod -> mod.getMetadata().getName(), String.CASE_INSENSITIVE_ORDER));
        addSection(rows, sections, "Active Mods and Versions", mods.size() + " loaded",
                DebugReportExport.Category.ACTIVE_MODS);
        for (ModContainer mod : mods) {
            String id = oneLine(mod.getMetadata().getId());
            String name = oneLine(mod.getMetadata().getName());
            String version = oneLine(mod.getMetadata().getVersion().getFriendlyString());
            rows.add(new Row.KVDim(id, name + ": " + version));
        }
    }

    private void buildServerContextReport() {
        addSection(rows, sections, "Server, Player, and Location", "live context",
                DebugReportExport.Category.SERVER_CONTEXT);
        var server = minecraft.getCurrentServer();
        rows.add(new Row.KV("Connection", server == null
                ? (minecraft.level == null ? "Not connected" : "Local or address unavailable")
                : oneLine(server.ip)));
        rows.add(new Row.KV("World loaded", String.valueOf(minecraft.level != null)));

        var zone = manager.currentZone();
        rows.add(new Row.KV("Waypointer zone", zone == null
                ? "(none)" : oneLine(zone.displayName()) + " (" + oneLine(zone.id()) + ")"));
        var player = minecraft.player;
        if (player == null) {
            rows.add(new Row.KVWarn("Player", "Unavailable"));
        } else {
            rows.add(new Row.KV("Player position", String.format(Locale.ROOT,
                    "%.3f, %.3f, %.3f", player.getX(), player.getY(), player.getZ())));
            rows.add(new Row.KV("Player block", player.blockPosition().getX() + ", "
                    + player.blockPosition().getY() + ", " + player.blockPosition().getZ()));
            rows.add(new Row.KV("View", String.format(Locale.ROOT, "yaw %.1f, pitch %.1f",
                    player.getYRot(), player.getXRot())));
        }
        rows.add(new Row.KVDim("Hypixel Mod API", DebugSignals.hypixelApiLine()));
    }

    private void buildLivePerformanceReport() {
        var player = minecraft.player;
        this.performanceStats = player == null
                ? PerformanceStats.capture(manager, config)
                : PerformanceStats.capture(manager, config,
                player.getX(), player.getY(), player.getZ());
        buildPerformanceReport(this.performanceStats, config, rows, sections);
    }

    private void buildStorageHealthReport() {
        addSection(rows, sections, "Storage Health", null);
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID);
        addFileHealth("Main config", configDirectory.resolve("config.json"));
        addFileHealth("Dungeon config", configDirectory.resolve("dungeon.json"));
        Storage storage = WaypointerClient.storage();
        if (storage == null) {
            rows.add(new Row.KVWarn("Storage", "Unavailable"));
            return;
        }
        Storage.DebugSnapshot snapshot = storage.debugSnapshot();
        rows.add(new Row.KV("Route file", snapshot.fileName()));
        rows.add(new Row.KV("Exists", String.valueOf(snapshot.exists())));
        rows.add(new Row.KV("Size", snapshot.sizeBytes() < 0L
                ? "unavailable" : formatBytes(snapshot.sizeBytes())));
        rows.add(new Row.KVDim("Modified", snapshot.modifiedAtMillis() < 0L
                ? "unavailable" : java.time.Instant.ofEpochMilli(snapshot.modifiedAtMillis()).toString()));
        rows.add(new Row.KV("Saver attached", String.valueOf(snapshot.attached())));
        rows.add(new Row.KV("Writes blocked", String.valueOf(snapshot.writesBlocked())));
        rows.add(new Row.KV("Snapshot ready", String.valueOf(snapshot.snapshotReady())));
        rows.add(new Row.KVDim("Session I/O", snapshot.snapshotsCaptured()
                + " snapshots, " + snapshot.writesCompleted() + " writes"));
        try (var files = Files.list(configDirectory)) {
            long quarantined = files
                    .filter(path -> path.getFileName().toString().startsWith("waypoints.json.invalid"))
                    .count();
            rows.add(new Row.KV("Invalid quarantines", String.valueOf(quarantined)));
        } catch (Exception ignored) {
            rows.add(new Row.KVWarn("Invalid quarantines", "Unavailable"));
        }
    }

    private void addFileHealth(String label, Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                rows.add(new Row.KV(label, "missing (defaults may be in use)"));
                return;
            }
            rows.add(new Row.KV(label, file.getFileName() + ", " + formatBytes(Files.size(file))
                    + ", modified " + formatAge(Files.getLastModifiedTime(file).toMillis())));
        } catch (Exception ignored) {
            rows.add(new Row.KVWarn(label, "Metadata unavailable"));
        }
    }

    private void buildSettingsReport() {
        DungeonConfig dungeonConfig = WaypointerClient.dungeonConfig();
        addSection(rows, sections, "All Settings", SettingsCatalog.allSettings().size() + " catalog entries",
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            rows.add(new Row.BitNote("[" + category.label() + "]"));
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (setting.store() == Setting.Store.NONE) continue;
                    if (setting.store() == Setting.Store.DUNGEON && dungeonConfig == null) {
                        rows.add(new Row.KVWarn(setting.id(), "Unavailable"));
                        continue;
                    }
                    Object value = setting.get(config, dungeonConfig);
                    rows.add(new Row.KV(setting.id(), oneLine(setting.formatValue(value))));
                }
            }
        }
        rows.add(new Row.BitNote("[Internal troubleshooting state]"));
        rows.add(new Row.KV("configSchemaVersion", String.valueOf(config.configSchemaVersion())));
        rows.add(new Row.KV("painterPalette", formatPalette(config.waypointPainterPalette())));
        rows.add(new Row.KV("painterDefault", config.waypointPainterDefaultPaint() == null
                ? "(none)" : "configured (hash " + config.waypointPainterDefaultPaint().hashCode() + ")"));
        if (dungeonConfig != null) {
            rows.add(new Row.KV("debugLogRoomChanges", String.valueOf(dungeonConfig.debugLogRoomChanges())));
            rows.add(new Row.KV("defaultDirection", dungeonConfig.defaultDirection()));
        }
    }

    private void buildActiveRoutesReport() {
        List<WaypointGroup> activeGroups = manager.activeGroups();
        addSection(rows, sections, "Active Routes and Waypoints", activeGroups.size() + " active",
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        if (activeGroups.isEmpty()) {
            rows.add(new Row.KVDim("Routes", "No routes are active in the current zone."));
            return;
        }

        var player = minecraft.player;
        for (int groupIndex = 0; groupIndex < activeGroups.size(); groupIndex++) {
            WaypointGroup group = activeGroups.get(groupIndex);
            rows.add(new Row.BitNote("Route " + (groupIndex + 1) + " of " + activeGroups.size()));
            rows.add(new Row.KV("Name", oneLine(group.name().isBlank() ? "(unnamed)" : group.name())));
            rows.add(new Row.KVDim("ID", oneLine(group.id())));
            rows.add(new Row.KV("Zone", oneLine(group.zoneId())));
            rows.add(new Row.KV("Source", routeSource(group)));
            rows.add(new Row.KVDim("Coordinates", routeCoordinateSpace(group)));
            rows.add(new Row.KV("State", "enabled=" + group.enabled()
                    + ", mode=" + group.loadMode()
                    + ", temp=" + group.temp()
                    + ", runtimeOnly=" + group.runtimeOnly()));
            rows.add(new Row.KV("Progress", "currentIndex=" + group.currentIndex()
                    + ", currentMain=" + group.currentMainIndex()
                    + ", mainOrdinal=" + group.currentMainOrdinal() + "/" + group.mainWaypointCount()
                    + ", complete=" + group.isComplete()));
            rows.add(new Row.KV("Route behavior", "skipAhead=" + group.skipAheadEnabled()
                    + ", defaultRadius=" + formatRadius(group.defaultRadius())
                    + ", activeSubParent=" + group.activeSubwaypointParentIndex()));
            List<Waypoint> waypoints = group.waypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                Waypoint waypoint = waypoints.get(i);
                StringBuilder value = new StringBuilder();
                if (i == group.currentIndex()) value.append("CURRENT  ");
                value.append("block=(").append(waypoint.x()).append(',').append(waypoint.y())
                        .append(',').append(waypoint.z()).append(')');
                value.append(String.format(Locale.ROOT, " world=(%.3f,%.3f,%.3f)",
                        waypoint.centerX(), waypoint.centerY(), waypoint.centerZ()));
                if (waypoint.hasName()) value.append(" name=\"").append(oneLine(waypoint.name())).append('"');
                value.append(String.format(Locale.ROOT, " color=#%06X flags=0x%X",
                        waypoint.color() & 0xFFFFFF, waypoint.flags()));
                double radius = waypoint.customRadius() > 0.0
                        ? waypoint.customRadius() : group.defaultRadius();
                value.append(" radius=").append(formatRadius(radius));
                if (waypoint.isTemp()) {
                    value.append(" temp=").append(Waypoint.tempModeName(waypoint.tempMode()))
                            .append(" expiresAt=").append(waypoint.expiresAtMillis());
                }
                if (player != null) {
                    double dx = waypoint.centerX() - player.getX();
                    double dy = waypoint.centerY() - player.getY();
                    double dz = waypoint.centerZ() - player.getZ();
                    value.append(String.format(Locale.ROOT, " distance=%.2f", Math.sqrt(dx * dx + dy * dy + dz * dz)));
                }
                rows.add(new Row.KVDim(group.displayIndexLabel(i), value.toString()));
            }
        }
    }

    private void buildTracerAndPathSettingsReport() {
        addSection(rows, sections, "Tracer and Dungeon Path Settings", null,
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KV("Tracer", config.showTracer() ? "on" : "off"));
        rows.add(new Row.KV("Tracer opacity", formatRadius(config.tracerOpacity())));
        rows.add(new Row.KV("Tracer thickness", formatRadius(config.tracerThickness()) + " px"));
        rows.add(new Row.KV("Inherits color", String.valueOf(config.matchTracerToWaypointColor())));
        rows.add(new Row.KV("Hide static tracer", String.valueOf(config.hideTracerOnStaticRoutes())));
        rows.add(new Row.KV("Hide near player", String.valueOf(config.hideWaypointsNearPlayer())));
        rows.add(new Row.KV("Near-player radius", formatRadius(config.hideWaypointsNearRadius()) + " blocks"));
        rows.add(new Row.KV("Iris HUD fallback", String.valueOf(config.irisShaderHudFallback())));
        rows.add(new Row.KV("Dungeon entry path to first waypoint", String.valueOf(
                config.showDungeonEntryPathToFirstWaypoint())));
        rows.add(new Row.KV("Continue dungeon path after first", String.valueOf(
                config.showDungeonEntryPathToFollowingWaypoints())));
    }

    private void buildKeybindReport() {
        WaypointerKeybinds keybinds = WaypointerClient.keybinds();
        addSection(rows, sections, "Keybinds", keybinds == null ? "unavailable" : "live bindings",
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        if (keybinds == null) {
            rows.add(new Row.KVWarn("Bindings", "Unavailable"));
            return;
        }
        for (WaypointerKeybinds.DebugBinding binding : keybinds.debugSnapshot()) {
            String value = binding.boundKey();
            if (!binding.conflicts().isEmpty()) {
                value += ", conflicts with " + String.join(", ", binding.conflicts());
            }
            rows.add(binding.conflicts().isEmpty()
                    ? new Row.KVDim(binding.translationKey(), oneLine(value))
                    : new Row.KVWarn(binding.translationKey(), oneLine(value)));
        }
    }

    private void buildRenderDiagnosticsReport() {
        RenderDiagnostics.Snapshot snapshot = RenderDiagnostics.snapshot();
        RenderDiagnostics.TracerSettings tracer = snapshot.tracer();
        RenderDiagnostics.DungeonPathSettings pathSettings = snapshot.dungeonPath();

        addSection(rows, sections, "Live Tracer Renderer State",
                snapshot.updatedAtEpochMillis() <= 0L
                        ? "not captured" : formatAge(snapshot.updatedAtEpochMillis()),
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KV("Tracer enabled", String.valueOf(tracer.enabled())));
        rows.add(new Row.KV("Opacity / thickness", formatRadius(tracer.opacity())
                + " / " + formatRadius(tracer.thickness()) + " px"));
        rows.add(new Row.KV("Inherits color", String.valueOf(tracer.inheritsWaypointColor())));
        rows.add(new Row.KV("Hide near player", tracer.hideNearPlayer()
                + " (radius " + formatRadius(tracer.hideNearPlayerRadius()) + ")"));
        rows.add(new Row.KV("Hide static tracer", String.valueOf(tracer.hideOnStaticRoutes())));
        rows.add(new Row.KV("Iris HUD configured", String.valueOf(tracer.irisHudFallbackConfigured())));
        rows.add(new Row.KV("Iris HUD active", String.valueOf(tracer.irisHudFallbackActive())));
        rows.add(new Row.KV("Entry path to first", String.valueOf(pathSettings.entryPathToFirstWaypoint())));
        rows.add(new Row.KV("Continue after first", String.valueOf(pathSettings.continueAfterFirstWaypoint())));

        addSection(rows, sections, "Dungeon Entry Path Outcomes", snapshot.groups().size() + " groups",
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        if (snapshot.groups().isEmpty()) {
            rows.add(new Row.KVDim("Groups", "No active DungeonRoomData route groups were rendered."));
            return;
        }
        for (int i = 0; i < snapshot.groups().size(); i++) {
            RenderDiagnostics.GroupSnapshot group = snapshot.groups().get(i);
            rows.add(new Row.BitNote("Dungeon group " + (i + 1) + " of " + snapshot.groups().size()));
            rows.add(new Row.KV("Group", oneLine(group.groupName().isBlank()
                    ? "(unnamed)" : group.groupName())));
            rows.add(new Row.KVDim("Group ID / zone", oneLine(group.groupId())
                    + " / " + oneLine(group.zoneId())));
            rows.add(new Row.KV("Load / index", group.loadMode() + " / " + group.currentIndex()));
            RenderDiagnostics.TargetSnapshot target = group.currentTarget();
            if (target == null) {
                rows.add(new Row.KVWarn("Current target", "(none)"));
            } else {
                rows.add(new Row.KV("Target block", target.blockX() + ", "
                        + target.blockY() + ", " + target.blockZ()));
                rows.add(new Row.KV("Target world", String.format(Locale.ROOT, "%.3f, %.3f, %.3f",
                        target.worldX(), target.worldY(), target.worldZ())));
                rows.add(new Row.KVDim("Target name", oneLine(target.name().isBlank()
                        ? "(unnamed)" : target.name())));
            }
            rows.add(new Row.KV("Entry-path eligible", String.valueOf(group.entryPathEligible())));
            rows.add(new Row.KV("Straight suppressed", String.valueOf(group.straightTracerSuppressed())));
            rows.add(new Row.KV("Straight submitted", String.valueOf(group.straightTracerSubmitted())));
            rows.add(new Row.KV("Path submitted", String.valueOf(group.dungeonPathSubmitted())));

            RenderDiagnostics.PathSnapshot path = group.path();
            rows.add(new Row.KV("Path cache", path.cacheStatus() + ", age "
                    + String.format(Locale.ROOT, "%.2f ms", path.cacheAgeMillis())));
            rows.add(new Row.KV("Path result", path.result() + ", " + path.pointCount() + " points"));
            rows.add(new Row.KV("Compute time", String.format(Locale.ROOT, "%.3f ms",
                    path.computeTimeMillis())));
            rows.add(new Row.KVDim("Raw start / goal", formatBlockPosition(path.rawStart())
                    + " -> " + formatBlockPosition(path.rawGoal())));
            rows.add(new Row.KVDim("Resolved start / goal", formatBlockPosition(path.resolvedStart())
                    + " -> " + formatBlockPosition(path.resolvedGoal())));
            rows.add(new Row.KV("Expansions", path.expansions() + " / " + path.expansionLimit()));
            rows.add(new Row.KVDim("Path reason", oneLine(path.reason())));
            rows.add(new Row.KVWarn("Final outcome", oneLine(group.finalOutcome())));
        }
    }

    private static String formatBlockPosition(RenderDiagnostics.BlockPosition position) {
        return position == null ? "(none)" : position.x() + "," + position.y() + "," + position.z();
    }

    private void buildRecentSettingsChangesReport() {
        List<ConfigChangeHistory.Entry> changes = ConfigChangeHistory.snapshot();
        addSection(rows, sections, "Recent Settings Changes", changes.size() + " this session",
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KVDim("Scope", "Settings UI changes in this game session only."));
        if (changes.isEmpty()) {
            rows.add(new Row.KVDim("History", "No settings changes recorded this session."));
            return;
        }
        for (ConfigChangeHistory.Entry change : changes) {
            String value = change.kind().equals("bulk")
                    ? change.subject()
                    : change.subject() + ": " + change.before() + " -> " + change.after();
            rows.add(new Row.KVDim(formatAge(change.capturedAtMillis()), oneLine(value)));
        }
    }

    private void buildRecentLogsAndActivityReport() {
        List<DebugEventLog.Entry> events = DebugEventLog.snapshot();
        addSection(rows, sections, "Recent UI and Input Activity", events.size() + " events",
                DebugReportExport.Category.RECENT_LOGS_AND_ACTIVITY);
        if (events.isEmpty()) {
            rows.add(new Row.KVDim("Activity", "No recent Waypointer UI/input events recorded."));
        } else {
            for (DebugEventLog.Entry event : events) {
                rows.add(new Row.KVDim("Event", oneLine(event.plainText())));
            }
        }

        List<String> logs = DebugLogTail.capture(60);
        addSection(rows, sections, "Recent Relevant Log Lines", logs.size() + " lines",
                DebugReportExport.Category.RECENT_LOGS_AND_ACTIVITY);
        if (logs.isEmpty()) {
            rows.add(new Row.KVDim("latest.log", "No recent relevant lines found or log unavailable."));
        } else {
            for (String line : logs) rows.add(new Row.KVDim("Log", oneLine(line)));
        }
    }

    private static String loadedModVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String routeSource(WaypointGroup group) {
        if (group.temp()) return "temporary session route";
        if (group.runtimeOnly() && group.id().startsWith("dungeon:auto:")) {
            return "generated dungeon runtime mirror";
        }
        if (group.runtimeOnly()) return "runtime/API overlay";
        return "persisted user route";
    }

    private static String routeCoordinateSpace(WaypointGroup group) {
        if (group.runtimeOnly() && group.id().startsWith("dungeon:auto:")) {
            return "transformed world coordinates";
        }
        if (group.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            return "stored dungeon room-local coordinates";
        }
        return "world coordinates";
    }

    private static String formatPalette(int[] colors) {
        if (colors == null || colors.length == 0) return "(empty)";
        StringBuilder value = new StringBuilder();
        for (int color : colors) {
            if (!value.isEmpty()) value.append(' ');
            value.append(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        }
        return value.toString();
    }

    private static String oneLine(String value) {
        if (value == null) return "(none)";
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), 1_000));
        for (int i = 0; i < value.length() && sanitized.length() < 1_000; i++) {
            char c = value.charAt(i);
            sanitized.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (value.length() > 1_000) sanitized.append("...");
        return sanitized.toString().trim();
    }

    private void openCopyConfirmation() {
        if (rows.isEmpty()) return;
        MinecraftCompat.setScreen(minecraft,
                new DebugReportConsentScreen(this, this::copyReportToClipboard));
    }

    private void copyReportToClipboard(DebugReportExport.Options options) {
        if (rows.isEmpty()) return;
        String report = DebugReportExport.format(exportSections(), options);
        minecraft.keyboardHandler.setClipboard(report);
        copyFeedbackUntil = System.currentTimeMillis() + FEEDBACK_MS;
        if (copyButton != null) {
            copyButton.setMessage(Component.translatable("waypointer.screen.debug.copied"));
        }
    }

    private List<DebugReportExport.Section> exportSections() {
        List<DebugReportExport.Section> exported = new ArrayList<>();
        String heading = null;
        DebugReportExport.Category category = DebugReportExport.Category.CORE;
        List<String> lines = new ArrayList<>();
        for (Row row : rows) {
            if (row instanceof Row.Section section) {
                if (heading != null) {
                    trimTrailingBlankLines(lines);
                    exported.add(new DebugReportExport.Section(heading, category, lines));
                }
                heading = section.title();
                category = section.category();
                lines = new ArrayList<>();
            } else if (heading != null) {
                lines.add(rowAsPlainText(row));
            }
        }
        if (heading != null) {
            trimTrailingBlankLines(lines);
            exported.add(new DebugReportExport.Section(heading, category, lines));
        }
        return List.copyOf(exported);
    }

    private static void trimTrailingBlankLines(List<String> lines) {
        while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (mouseX >= sidebarX1 && mouseX <= sidebarX2
                && mouseY >= sidebarContentTop && mouseY <= mainBottom
                && !sections.isEmpty()) {
            int maxSidebarScroll = Math.max(0, sections.size() - sidebarVisibleRows);
            sidebarScrollRows = Mth.clamp(
                    sidebarScrollRows - (int) (vert * SCROLL_ROWS_PER_NOTCH),
                    0, maxSidebarScroll);
            return true;
        }
        if (mouseX >= mainX1 && mouseX <= mainX2 && mouseY >= mainTop && mouseY <= mainBottom
                && !rows.isEmpty()) {
            int maxScroll = Math.max(0, rows.size() - visibleRowCount);
            scrollRows = Mth.clamp(scrollRows - (int) (vert * SCROLL_ROWS_PER_NOTCH), 0, maxScroll);
            syncSelectedSectionWithScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horiz, vert);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || sections.isEmpty()) return false;

        double mx = event.x();
        double my = event.y();
        if (mx < sidebarX1 || mx > sidebarX2 || my < sidebarContentTop) return false;

        int rowIdx = sidebarScrollRows + (int) ((my - sidebarContentTop) / ROW_H);
        if (rowIdx < 0 || rowIdx >= sections.size()) return false;
        jumpToSection(rowIdx);
        return true;
    }

    private void jumpToSection(int idx) {
        selectedSection = idx;
        int maxScroll = Math.max(0, rows.size() - visibleRowCount);
        scrollRows = Mth.clamp(sections.get(idx).rowIndex(), 0, maxScroll);
        ensureSelectedSidebarVisible();
    }

    private void syncSelectedSectionWithScroll() {
        int best = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).rowIndex() <= scrollRows) best = i;
            else break;
        }
        selectedSection = best;
        ensureSelectedSidebarVisible();
    }

    private void ensureSelectedSidebarVisible() {
        if (sidebarVisibleRows <= 0) return;
        if (selectedSection < sidebarScrollRows) {
            sidebarScrollRows = selectedSection;
        } else if (selectedSection >= sidebarScrollRows + sidebarVisibleRows) {
            sidebarScrollRows = selectedSection - sidebarVisibleRows + 1;
        }
        sidebarScrollRows = Mth.clamp(sidebarScrollRows, 0,
                Math.max(0, sections.size() - sidebarVisibleRows));
    }


    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        if (copyFeedbackUntil != 0 && System.currentTimeMillis() > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            if (copyButton != null) {
                copyButton.setMessage(Component.translatable(
                        "waypointer.screen.debug.copy_report"));
            }
        }

        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);

        int top = PAD_OUTER + 10 + GAP_SECTION;
        int bottom = height - FOOTER_H - GAP_SECTION;

        this.sidebarX1 = PAD_OUTER;
        this.sidebarX2 = sidebarX1 + debugSidebarWidth(width);
        this.mainX1 = sidebarX2 + GAP_SECTION;
        this.mainX2 = width - PAD_OUTER;
        this.mainTop = top;
        this.mainBottom = bottom;

        renderSidebar(g, sidebarX1, top, sidebarX2, bottom, mouseX, mouseY);
        renderMain(g, mainX1, top, mainX2, bottom);
    }

    static int debugSidebarWidth(int screenWidth) {
        int available = Math.max(0, screenWidth - PAD_OUTER * 2 - GAP_SECTION);
        return Math.min(available, Math.max(80,
                Math.min(DEBUG_SIDEBAR_W, available - DEBUG_MAIN_MIN_W)));
    }


    private void renderSidebar(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                int mouseX, int mouseY) {
        g.fill(x1, y1, x2, y2, SURFACE);
        g.fill(x2, y1, x2 + 1, y2, BORDER);

        int labelY = y1 + 10;
        g.text(font, Component.translatable("waypointer.screen.debug.report"),
                x1 + GAP, labelY, TEXT_DIM, false);
        this.sidebarContentTop = labelY + 14;

        if (sections.isEmpty()) {
            g.text(font, Component.translatable(debug == null
                            ? "waypointer.screen.debug.no_data"
                            : "waypointer.screen.debug.empty"),
                    x1 + GAP, sidebarContentTop + 4, TEXT_MUTED, false);
            return;
        }

        sidebarVisibleRows = Math.max(1, (y2 - sidebarContentTop) / ROW_H);
        sidebarScrollRows = Mth.clamp(sidebarScrollRows, 0,
                Math.max(0, sections.size() - sidebarVisibleRows));
        int rowY = sidebarContentTop;
        int end = Math.min(sections.size(), sidebarScrollRows + sidebarVisibleRows);
        for (int i = sidebarScrollRows; i < end; i++, rowY += ROW_H) {

            SectionAnchor s = sections.get(i);
            boolean selected = i == selectedSection;
            boolean hovered = mouseX >= x1 && mouseX <= x2
                    && mouseY >= rowY && mouseY <= rowY + ROW_H;
            drawSidebarRow(g, x1, rowY, x2, s, selected, hovered);
        }
        if (sections.size() > sidebarVisibleRows) {
            drawScrollbar(g, x2 - 4, sidebarContentTop + 2, y2 - 2,
                    sidebarScrollRows, sidebarVisibleRows, sections.size());
        }
    }

    private void drawSidebarRow(GuiGraphicsExtractor g, int x1, int y, int x2,
                                 SectionAnchor s, boolean selected, boolean hovered) {
        int bg = selected ? SELECTED : hovered ? HOVER : 0;
        if (bg != 0) g.fill(x1, y, x2, y + ROW_H, bg);
        if (selected) g.fill(x1, y, x1 + 2, y + ROW_H, ACCENT);

        int textColor = selected ? TEXT : TEXT_DIM;
        String shown = font.plainSubstrByWidth(s.label(),
                Math.max(0, x2 - x1 - GAP * 2 - 4));
        g.text(font, shown, x1 + GAP + 2, y + 6, textColor, false);
    }


    private void renderMain(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, SURFACE_SUBTLE);

        if (lastError != null) {
            renderError(g, x1, y1, x2, y2);
            this.visibleRowCount = 0;
            return;
        }
        if (rows.isEmpty()) {
            renderEmpty(g, x1, y1, x2, y2);
            this.visibleRowCount = 0;
            return;
        }

        int lineH = font.lineHeight + 1;
        int innerX = x1 + GAP + GAP_TIGHT;
        int innerTop = y1 + 6;
        int innerH = y2 - y1 - 12;
        this.visibleRowCount = Math.max(1, innerH / lineH);

        int maxScroll = Math.max(0, rows.size() - visibleRowCount);
        scrollRows = Mth.clamp(scrollRows, 0, maxScroll);
        int start = scrollRows;
        int end = Math.min(rows.size(), start + visibleRowCount);

        g.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
        int y = innerTop;
        for (int i = start; i < end; i++, y += lineH) {
            drawRow(g, rows.get(i), innerX, y, x2 - GAP);
        }
        g.disableScissor();

        if (rows.size() > visibleRowCount) {
            drawScrollbar(g, x2 - 4, y1 + 4, y2 - 4, start, visibleRowCount, rows.size());
        }
    }

    private void drawRow(GuiGraphicsExtractor g, Row row, int x, int y, int xEnd) {
        switch (row) {
            case Row.Section s -> {
                g.text(font, s.title(), x, y, ACCENT, false);
                int lineX = x + font.width(s.title()) + GAP;
                if (lineX < xEnd) g.fill(lineX, y + 5, xEnd, y + 6, BORDER);
            }
            case Row.KV kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y,
                        valueColor(kv.key(), kv.value()), false);
            }
            case Row.KVDim kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y, TEXT_DIM, false);
            }
            case Row.KVWarn kv -> {
                drawKey(g, kv.key(), x, y);
                g.text(font, kv.value(), x + KEY_COL_W, y, WARN_TONE, false);
            }
            case Row.Bit b -> {
                g.text(font, "bit " + b.bit(), x, y, TEXT_DIM, false);
                g.text(font, b.label(), x + BIT_LABEL_OFFSET, y, KEYWORD_TONE, false);
                g.text(font, b.set() ? "true" : "false", x + KEY_COL_W, y,
                        b.set() ? SUCCESS_TONE : TEXT_MUTED, false);
            }
            case Row.BitNote n -> g.text(font, n.text(), x, y, TEXT_MUTED, false);
            case Row.PoolEntry p -> {
                g.text(font, "[" + p.index() + "]", x, y, NUMBER_TONE, false);
                String content = p.text().isEmpty() ? "(empty)" : p.text();
                g.text(font, content, x + POOL_CONTENT_OFFSET, y,
                        p.text().isEmpty() ? TEXT_MUTED : STRING_TONE, false);
            }
            case Row.WP wp -> drawWaypointRow(g, wp.wp(), x, y, xEnd);
            case Row.Blank ignored -> { /* deliberate breathing room */ }
        }
    }

    private void drawKey(GuiGraphicsExtractor g, String key, int x, int y) {
        String shown = key;
        while (font.width(shown) > KEY_COL_W - GAP && shown.length() > 3) {
            shown = shown.substring(0, shown.length() - 4) + "...";
        }
        g.text(font, shown, x, y, TEXT_DIM, false);
    }

    private static int valueColor(String key, String value) {
        String lowerKey = key.toLowerCase(Locale.ROOT);
        String lowerValue = value.toLowerCase(Locale.ROOT);

        if (lowerValue.equals("on") || lowerValue.equals("enabled")
                || lowerValue.equals("true") || lowerValue.equals("unlimited")) {
            return SUCCESS_TONE;
        }
        if (lowerValue.equals("off") || lowerValue.equals("disabled")
                || lowerValue.equals("false") || lowerValue.equals("(none)")) {
            return TEXT_MUTED;
        }
        if (lowerValue.startsWith("0x") || lowerValue.contains("  0b")
                || lowerKey.contains("byte") || lowerKey.contains("flags")) {
            return HEX_TONE;
        }
        if (lowerValue.startsWith("\"") || lowerValue.startsWith("(")) {
            return STRING_TONE;
        }
        if (!value.isEmpty() && Character.isDigit(value.charAt(0))) {
            return NUMBER_TONE;
        }
        if (lowerKey.contains("mode") || lowerKey.contains("zone")) {
            return KEYWORD_TONE;
        }
        return TEXT;
    }

    private void drawWaypointRow(GuiGraphicsExtractor g, DecodeDebug.WaypointDebug wp,
                                  int x, int y, int xEnd) {
        int xIdx    = x;
        int xCoords = x + 20;
        int xFlags  = x + 20 + 120;
        int xSwatch = x + 20 + 120 + 56;
        int xHex    = xSwatch + 10;
        int xExtras = xHex + 58;

        g.text(font, "#" + wp.index(), xIdx, y, NUMBER_TONE, false);

        String coords = String.format(Locale.ROOT, "%d, %d, %d", wp.x(), wp.y(), wp.z());
        g.text(font, coords, xCoords, y, NUMBER_TONE, false);

        g.text(font, shortByte(wp.wpFlagsByte()), xFlags, y, HEX_TONE, false);

        if (wp.hasColor()) {
            int swatchColor = 0xFF000000 | (wp.color() & 0xFFFFFF);
            g.fill(xSwatch, y + 1, xSwatch + 7, y + 8, swatchColor);
            g.text(font, String.format(Locale.ROOT, "#%06X", wp.color() & 0xFFFFFF),
                    xHex, y, HEX_TONE, false);
        }

        int cx = xExtras;
        if (wp.hasName()) {
            String name = "\"" + wp.name() + "\"";
            g.text(font, name, cx, y, STRING_TONE, false);
            cx += font.width(name) + GAP;
        }
        if (wp.hasRadius() && cx < xEnd) {
            String r = "r=" + formatRadius(wp.customRadius());
            g.text(font, r, cx, y, NUMBER_TONE, false);
            cx += font.width(r) + GAP;
        }
        if (wp.extended() && cx < xEnd) {
            g.text(font, "ext=" + formatIntHex(wp.extendedFlags()), cx, y, HEX_TONE, false);
        }
    }

    private void renderEmpty(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        String a = Component.translatable(
                "waypointer.screen.debug.payload.empty").getString();
        String b = Component.translatable(
                "waypointer.screen.debug.payload.empty_hint",
                WaypointCodec.MAGIC).getString();
        int cy = y1 + (y2 - y1) / 2 - 8;
        int ax = x1 + ((x2 - x1) - font.width(a)) / 2;
        int bx = x1 + ((x2 - x1) - font.width(b)) / 2;
        g.text(font, a, ax, cy, TEXT, false);
        g.text(font, b, bx, cy + 14, TEXT_DIM, false);
    }

    private void renderError(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        String[] lines = lastError.split("\n");
        int lineH = font.lineHeight + 2;
        int totalH = lines.length * lineH;
        int cy = y1 + (y2 - y1 - totalH) / 2;
        for (int i = 0; i < lines.length; i++) {
            int tw = font.width(lines[i]);
            int tx = x1 + ((x2 - x1) - tw) / 2;
            g.text(font, lines[i], tx, cy + i * lineH, i == 0 ? ERROR_TONE : TEXT_DIM, false);
        }
    }

    private static void drawScrollbar(GuiGraphicsExtractor g, int x, int y1, int y2,
                                       int start, int visible, int total) {
        int trackH = y2 - y1;
        int thumbH = Math.max(8, (int) ((double) visible / total * trackH));
        int thumbY = y1 + (int) ((double) start / Math.max(1, total - visible) * (trackH - thumbH));
        g.fill(x, y1, x + 2, y2, 0x30FFFFFF);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF);
    }


    private static void buildReport(DecodeDebug d, List<Row> rows, List<SectionAnchor> sections) {
        addSection(rows, sections, "Codec Pipeline", null,
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        rows.add(new Row.KV("Input",       d.inputChars() + " chars"));
        rows.add(new Row.KVDim("Prefix",   d.magic()));
        rows.add(new Row.KV("Payload",     d.payloadChars() + " chars"));
        rows.add(new Row.KVDim("Encoding", d.textEncoding()));
        rows.add(new Row.KV("Compressed",  d.compressedBytes() + " bytes"));
        rows.add(new Row.KV("Raw body",    d.rawBodyBytes() + " bytes"));
        rows.add(new Row.KV("Density",     String.format(Locale.ROOT, "%.2f chars / raw byte", d.charsPerRawByte())));
        rows.add(new Row.KV("Decode time", formatNanos(d.decodeNanos())));

        addSection(rows, sections, "Codec Header", shortByte(d.headerByte()),
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        rows.add(new Row.KV("Byte",    formatByteFull(d.headerByte())));
        rows.add(new Row.KV("Version", "v" + d.version() + " (bits 0..3)"));
        if (d.version() == 9) {
            int contentKind = (d.headerByte() >>> 4) & 0b111;
            String contentKindName = switch (contentKind) {
                case 0 -> "general route";
                case 1 -> "compact full route";
                case 2 -> "coordinate-only route";
                case 3 -> "config (reserved)";
                case 4 -> "dungeon route (reserved)";
                case 5 -> "coordinate route with metadata";
                default -> "reserved";
            };
            rows.add(new Row.BitNote("bits 4-6  content kind = " + contentKind
                    + " (" + contentKindName + ")"));
            rows.add(new Row.Bit(4, "contentKind bit 0", (contentKind & 0b001) != 0));
            rows.add(new Row.Bit(5, "contentKind bit 1", (contentKind & 0b010) != 0));
            rows.add(new Row.Bit(6, "contentKind bit 2", (contentKind & 0b100) != 0));
            rows.add(new Row.Bit(7, "hasLabel", d.hasLabel()));
        } else {
            rows.add(new Row.Bit(4, "includesNames", d.includesNames()));
            rows.add(new Row.Bit(5, "hasLabel",      d.hasLabel()));
            rows.add(new Row.Bit(6, "anonymous",     d.reservedBit6()));
            rows.add(new Row.Bit(7, "reserved",      d.reservedBit7()));
        }
        if (d.hasLabel()) {
            String shown = d.label().isEmpty() ? "(empty after sanitize)" : "\"" + d.label() + "\"";
            rows.add(new Row.KV("Label", shown));
        }

        String poolSub = d.stringPool().size() + (d.stringPool().size() == 1 ? " entry" : " entries");
        addSection(rows, sections, "Codec String Pool", poolSub,
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        for (int i = 0; i < d.stringPool().size(); i++) {
            rows.add(new Row.PoolEntry(i, d.stringPool().get(i)));
        }

        for (DecodeDebug.GroupDebug gd : d.groups()) {
            String subtitle = gd.name().isEmpty() ? "(unnamed)" : gd.name();
            addSection(rows, sections, "Codec Group " + gd.index(), subtitle,
                    DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
            WaypointGroup decodedGroup = d.decodedGroups().get(gd.index());

            rows.add(new Row.KV("Zone",          gd.zoneId().isEmpty() ? "(none)" : gd.zoneId()));
            if (gd.groupFlagsByte() < 0) {
                rows.add(new Row.KV("Group flags", "n/a (compact v9 layout)"));
                rows.add(new Row.KV("Coordinate model", gd.coordMode()));
            } else {
                rows.add(new Row.KV("Group flags", formatByteFull(gd.groupFlagsByte())));
                rows.add(new Row.Bit(0, d.version() == 2 ? "enabled" : "bodyless",
                        d.version() == 2 ? gd.enabled() : (gd.groupFlagsByte() & 1) != 0));
                rows.add(new Row.Bit(1, "gradientAuto", gd.gradientAuto()));
                rows.add(new Row.Bit(2, "loadSequence", gd.loadSequence()));
                rows.add(new Row.Bit(3, "customRadius", gd.customRadius()));
                rows.add(new Row.BitNote("bits 4-5  coord mode = " + gd.coordMode()
                        + " (ord " + gd.coordModeOrdinal() + ")"));
                if (d.version() >= 5) {
                    rows.add(new Row.Bit(6, "coordMode bit 2", (gd.groupFlagsByte() & 0x40) != 0));
                }
                if (d.version() == 9) {
                    rows.add(new Row.Bit(7, "persistentMeta", (gd.groupFlagsByte() & 0x80) != 0));
                }
            }
            rows.add(new Row.KV("Gradient mode", decodedGroup.gradientMode().name()));
            rows.add(new Row.KV("Skip ahead", String.valueOf(decodedGroup.skipAheadEnabled())));
            rows.add(new Row.KV("Static color", String.format(Locale.ROOT, "#%06X",
                    decodedGroup.staticColor() & 0xFFFFFF)));
            rows.add(new Row.KV("Gradient colors", String.format(Locale.ROOT, "#%06X -> #%06X",
                    decodedGroup.gradientStartColor() & 0xFFFFFF,
                    decodedGroup.gradientEndColor() & 0xFFFFFF)));
            rows.add(new Row.KV("Default radius", formatRadius(gd.defaultRadius())));
            rows.add(new Row.KV("Current index",  String.valueOf(gd.currentIndex())));
            rows.add(new Row.KV("Point count",    String.valueOf(gd.pointCount())));
            if (gd.coordBlockBytes() < 0) {
                rows.add(new Row.KV("Compact payload", gd.bodyBlockBytes() + " bytes"));
            } else {
                rows.add(new Row.KV("Coord bytes", gd.coordBlockBytes() + " (" + gd.coordMode() + ")"));
                rows.add(new Row.KV("Body bytes", String.valueOf(gd.bodyBlockBytes())));
            }

            if (!gd.waypoints().isEmpty()) {
                rows.add(new Row.Blank());
                for (DecodeDebug.WaypointDebug wp : gd.waypoints()) {
                    rows.add(new Row.WP(wp));
                }
            }
        }
    }

    private static void buildCodecClipboardReport(List<Row> rows,
                                                  List<SectionAnchor> sections,
                                                  String message) {
        addSection(rows, sections, "Codec Clipboard", "not loaded",
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        rows.add(new Row.KVWarn("Status", message));
        rows.add(new Row.KVDim("Hint", "Copy a Waypointer export and hit Refresh."));
    }

    private static void buildDungeonDiagnosticsReport(DebugSignals.DungeonDebugSnapshot snapshot,
                                                     List<Row> rows,
                                                     List<SectionAnchor> sections) {
        DungeonStateTracker.DebugSnapshot tracker = snapshot == null ? null : snapshot.tracker;
        addSection(rows, sections, "Dungeon Overview",
                tracker == null ? "not installed" : tracker.roomName,
                DebugReportExport.Category.SERVER_CONTEXT);
        rows.add(new Row.KVDim("Config", DebugSignals.dungeonConfigLine()));
        rows.add(new Row.KVDim("Zone source", "Scoreboard sidebar"));
        if (tracker == null) {
            rows.add(new Row.KVWarn("Tracker", "Dungeon tracker is not installed."));
        } else {
            rows.add(new Row.KV("In dungeon", String.valueOf(tracker.inDungeon)));
            rows.add(new Row.KV("Room", tracker.roomPresent ? tracker.roomName : "(none)"));
            rows.add(new Row.KV("Room id", tracker.roomId));
            rows.add(new Row.KV("Confidence", DebugSignals.detectionConfidenceLabel(tracker.confidence)));
            rows.add(new Row.KV("Type/shape", tracker.roomType + " / " + tracker.roomShape));
            rows.add(new Row.KV("Direction", tracker.roomDirection
                    + " effective=" + tracker.effectiveDirection
                    + " override=" + tracker.directionOverride));
            rows.add(new Row.KV("Corner", tracker.physicalCornerX + ", " + tracker.physicalCornerZ));
            rows.add(new Row.KV("Segments", formatSegments(tracker.roomSegments)));
        }

        addSection(rows, sections, "Room Detection", tracker == null ? "unavailable" : tracker.lastScanStage,
                DebugReportExport.Category.SERVER_CONTEXT);
        if (tracker == null) {
            rows.add(new Row.KVWarn("Unavailable", "No tracker snapshot is available."));
        } else {
            rows.add(new Row.KV("Last scan", tracker.lastScanStage + " -> " + tracker.lastScanResult));
            rows.add(new Row.KVDim("Scan age", formatAge(tracker.lastScanAtMillis)));
            rows.add(new Row.KV("Scan time", formatNanos(tracker.lastScanDurationNanos)));
            rows.add(new Row.KV("Player segment", formatSegment(tracker.lastPlayerSegment)));
            rows.add(new Row.KV("Core signature", formatCoreSignature(tracker.lastPlayerSegmentSignature)));
            rows.add(new Row.KV("Matched room", tracker.lastMatchedRoomName + " (" + tracker.lastMatchedRoomId + ")"));
            rows.add(new Row.KV("Matched pieces", String.valueOf(tracker.lastMatchedComponentCount)));
            rows.add(new Row.KV("Room cache", tracker.knownRoomCacheSize + " segment entries"));
            rows.add(new Row.KV("Core cache", tracker.coreSignatureCacheSize + " segment signatures"));
            rows.add(new Row.KVDim("Scoreboard text", DebugSignals.scoreboardLine()));
            rows.add(new Row.KVDim("Tab text", DebugSignals.tabListLine()));
        }

        DungeonRoomZoneBridge.DebugSnapshot bridge = snapshot == null ? null : snapshot.bridge;
        addSection(rows, sections, "Zone Bridge", bridge == null ? "unavailable" : bridge.lastAction,
                DebugReportExport.Category.SERVER_CONTEXT);
        if (bridge == null) {
            rows.add(new Row.KVWarn("Unavailable", "No bridge snapshot is available."));
        } else {
            rows.add(new Row.KV("Installed", String.valueOf(bridge.installed)));
            rows.add(new Row.KV("Current zone", bridge.currentZone));
            rows.add(new Row.KV("Last broad", bridge.lastBroadZone));
            rows.add(new Row.KV("Applying room", String.valueOf(bridge.applyingRoomZone)));
            rows.add(new Row.KV("Action", bridge.lastAction));
            rows.add(new Row.KVDim("Reason", bridge.lastReason));
            rows.add(new Row.KVDim("Line", bridge.line));
        }

    }

    private static void buildPerformanceReport(PerformanceStats stats,
                                                WaypointerConfig config,
                                                List<Row> rows,
                                                List<SectionAnchor> sections) {
        addSection(rows, sections, "Performance Snapshot", null);
        rows.add(new Row.KV("Captured", stats.capturedAt().toString()));
        rows.add(new Row.KVDim("Meaning", "counts before camera/distance culling unless noted"));

        addSection(rows, sections, "Route Library", stats.totalWaypoints() + " pts",
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        rows.add(new Row.KV("Groups", stats.totalGroups() + " total, "
                + stats.enabledGroups() + " enabled, " + stats.tempGroups() + " temp"));
        rows.add(new Row.KV("Zones", stats.knownZoneCount() + " known"));
        rows.add(new Row.KV("Waypoints", stats.totalWaypoints() + " total, "
                + stats.tempWaypoints() + " temp"));
        rows.add(new Row.KV("Load modes", stats.staticGroups() + " static groups, "
                + stats.sequenceGroups() + " sequence groups"));
        rows.add(new Row.KV("Largest group", groupSummary(stats.largestGroup())));

        addSection(rows, sections, "Active Zone", stats.activeGroups() + " groups",
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        rows.add(new Row.KV("Active points", stats.activeWaypoints() + " total"));
        rows.add(new Row.KV("Static points", String.valueOf(stats.activeStaticWaypoints())));
        rows.add(new Row.KV("Sequence points", String.valueOf(stats.activeSequenceWaypoints())));
        rows.add(new Row.KV("Renderable", stats.activeVisibleWaypoints() + " waypoint slots"));
        rows.add(new Row.KV("Label candidates", stats.activeLabelCandidates() + " before budget"));
        rows.add(new Row.KV("Largest active", groupSummary(stats.largestActiveGroup())));

        addSection(rows, sections, "Render Estimate", null,
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KV("Box style", config.boxStyle().name()));
        rows.add(new Row.KV("Beacon mode", config.beaconBeamMode().name()));
        rows.add(new Row.KV("Line vertices", String.valueOf(stats.estimatedLineBoxVertices())));
        rows.add(new Row.KV("Fill vertices", String.valueOf(stats.estimatedFillBoxVertices())));
        rows.add(new Row.KV("Beam vertices", String.valueOf(stats.estimatedBeamVertices())));
        rows.add(new Row.KV("Label budget", config.maxWaypointLabels() == 0
                ? "unlimited" : String.valueOf(config.maxWaypointLabels())));
        rows.add(new Row.KV("Static distance", config.maxStaticWaypointRenderDistance() <= 0.0
                ? "unlimited" : String.format(Locale.ROOT, "%.1f blocks",
                config.maxStaticWaypointRenderDistance())));

        addSection(rows, sections, "Tick Estimate", null,
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KV("Proximity visits", stats.estimatedProximityIndexVisitsPerTick()
                + " nearby candidates/tick"));
        rows.add(new Row.KVDim("Skip-ahead", config.skipAheadMechanicEnabled() ? "enabled" : "disabled"));
        rows.add(new Row.KVDim("Static reached hide",
                config.hideReachedStaticWaypointsUntilCycleComplete() ? "enabled" : "disabled"));

        addSection(rows, sections, "Config Toggles", null,
                DebugReportExport.Category.SETTINGS_AND_CHANGES);
        rows.add(new Row.KV("Names", config.showWaypointNames() ? "on" : "off"));
        rows.add(new Row.KV("Distances", config.showWaypointDistances() ? "on" : "off"));
        rows.add(new Row.KV("Backdrop", config.showLabelBackdrop() ? "on" : "off"));
        rows.add(new Row.KV("Tracer", config.showTracer() ? "on" : "off"));
        rows.add(new Row.KV("Hide static tracer", config.hideTracerOnStaticRoutes() ? "on" : "off"));

        addSection(rows, sections, "Active Groups", String.valueOf(stats.activeGroupStats().size()),
                DebugReportExport.Category.ROUTES_AND_WAYPOINTS);
        if (stats.activeGroupStats().isEmpty()) {
            rows.add(new Row.KVDim("None", "No active groups in the current zone."));
            return;
        }
        for (PerformanceStats.GroupStats group : stats.activeGroupStats()) {
            rows.add(new Row.KV(shortGroupName(group),
                    group.waypoints() + " pts, "
                            + group.renderableWaypoints() + " renderable, "
                            + group.labelCandidates() + " labels, "
                            + group.proximityIndexVisitsPerTick() + " tick candidates, "
                            + group.loadMode().toLowerCase(Locale.ROOT)));
        }
    }

    private static void addSection(List<Row> rows, List<SectionAnchor> sections,
                                    String label, String subtitle) {
        addSection(rows, sections, label, subtitle, DebugReportExport.Category.CORE);
    }

    private static void addSection(List<Row> rows, List<SectionAnchor> sections,
                                   String label, String subtitle,
                                   DebugReportExport.Category category) {
        if (!rows.isEmpty()) rows.add(new Row.Blank());
        sections.add(new SectionAnchor(sidebarSectionLabel(label), rows.size()));
        rows.add(new Row.Section(label, category));
    }

    static String sidebarSectionLabel(String label) {
        if (label == null) return "";
        String unavailableSuffix = " (Unavailable)";
        boolean unavailable = label.endsWith(unavailableSuffix);
        String base = unavailable
                ? label.substring(0, label.length() - unavailableSuffix.length())
                : label;
        String concise = switch (base) {
            case "Troubleshooting Report" -> "Summary";
            case "Active Mods and Versions" -> "Mods";
            case "Server, Player, and Location" -> "Server & location";
            case "Storage Health" -> "Storage";
            case "Performance Snapshot" -> "Performance";
            case "All Settings" -> "Settings";
            case "Tracer and Dungeon Path Settings" -> "Tracers & paths";
            case "Dungeon Entry Path Outcomes" -> "Dungeon paths";
            case "Active Routes and Waypoints" -> "Active routes";
            case "Dungeon Overview" -> "Dungeon";
            case "Built-in Dungeon Secret Progress" -> "Secret progress";
            case "Recent Settings Changes" -> "Recent changes";
            case "Recent Logs and Activity" -> "Recent activity";
            case "Codec Clipboard" -> "Codec";
            default -> base;
        };
        return unavailable ? concise + " unavailable" : concise;
    }


    private static String formatSegments(List<Long> segments) {
        if (segments == null || segments.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Long segment : segments) {
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(formatSegment(segment == null ? Long.MIN_VALUE : segment));
        }
        return builder.toString();
    }

    private static String formatSegment(long segment) {
        if (segment == Long.MIN_VALUE) return "(none)";
        return DungeonRoom.segmentX(segment) + "," + DungeonRoom.segmentZ(segment);
    }

    private static String formatCoreSignature(DungeonCoreSignature signature) {
        if (signature == null) return "(none)";
        return "hash=" + signature.hash()
                + ", topY=" + signature.topY()
                + ", samples=" + signature.sampleCount();
    }

    private static String formatAge(long timestampMillis) {
        if (timestampMillis <= 0L) return "(never)";
        long ageMillis = Math.max(0L, System.currentTimeMillis() - timestampMillis);
        if (ageMillis < 1_000L) return ageMillis + " ms ago";
        return String.format(Locale.ROOT, "%.1f s ago", ageMillis / 1_000.0);
    }

    private static String rowAsPlainText(Row row) {
        return switch (row) {
            case Row.Section s -> "== " + s.title() + " ==";
            case Row.KV kv -> String.format(Locale.ROOT, "  %-16s %s",
                    oneLine(kv.key()) + ":", oneLine(kv.value()));
            case Row.KVDim kv -> String.format(Locale.ROOT, "  %-16s %s",
                    oneLine(kv.key()) + ":", oneLine(kv.value()));
            case Row.KVWarn kv -> String.format(Locale.ROOT, "  %-16s %s",
                    oneLine(kv.key()) + ":", oneLine(kv.value()));
            case Row.Bit b -> String.format(Locale.ROOT, "    bit %d  %-17s = %s",
                    b.bit(), oneLine(b.label()), b.set() ? "true" : "false");
            case Row.BitNote n -> "    " + oneLine(n.text());
            case Row.PoolEntry p -> String.format(Locale.ROOT, "  [%d] %s",
                    p.index(), p.text().isEmpty() ? "\"\"" : "\"" + oneLine(p.text()) + "\"");
            case Row.WP wp -> formatWaypointPlain(wp.wp());
            case Row.Blank ignored -> "";
        };
    }

    private static String groupSummary(PerformanceStats.GroupStats group) {
        if (group == null) return "(none)";
        return oneLine(shortGroupName(group)) + ": " + group.waypoints()
                + " pts, " + group.loadMode().toLowerCase(Locale.ROOT);
    }

    private static String shortGroupName(PerformanceStats.GroupStats group) {
        String name = group.name() == null || group.name().isBlank()
                ? "(unnamed)"
                : group.name();
        if (name.length() <= 28) return name;
        return name.substring(0, 25) + "...";
    }

    private static String formatWaypointPlain(DecodeDebug.WaypointDebug wp) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "  #%-2d (%6d,%4d,%6d)  flags=%s",
                wp.index(), wp.x(), wp.y(), wp.z(), formatByteFull(wp.wpFlagsByte())));
        if (wp.hasName())   sb.append("  name=\"").append(oneLine(wp.name())).append('"');
        if (wp.hasColor())  sb.append(String.format(Locale.ROOT, "  color=#%06X", wp.color() & 0xFFFFFF));
        if (wp.hasRadius()) sb.append("  r=").append(formatRadius(wp.customRadius()));
        if (wp.extended())  sb.append("  ext=").append(formatIntHex(wp.extendedFlags()));
        return sb.toString();
    }

    private static String shortByte(int byteValue) {
        return String.format(Locale.ROOT, "0x%02X", byteValue & 0xFF);
    }

    private static String formatByteFull(int byteValue) {
        int b = byteValue & 0xFF;
        String bin = String.format(Locale.ROOT, "%8s", Integer.toBinaryString(b)).replace(' ', '0');
        return String.format(Locale.ROOT, "0x%02X  0b%s", b, bin);
    }

    private static String formatRadius(double radius) {
        return Double.toString(radius);
    }

    private static String formatIntHex(int value) {
        return String.format(Locale.ROOT, "0x%08X", value);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024.0) return String.format(Locale.ROOT, "%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024.0) return String.format(Locale.ROOT, "%.1f MiB", mib);
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    private static String formatNanos(long nanos) {
        if (nanos < 1_000)              return nanos + " ns";
        if (nanos < 1_000_000)          return String.format(Locale.ROOT, "%.1f us", nanos / 1_000.0);
        if (nanos < 1_000_000_000L)     return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }

    @Override
    public void removed() {
        RenderDiagnostics.setDetailedCaptureEnabled(false);
        super.removed();
    }
}
