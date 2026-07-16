package dev.ethan.waypointer.screen.settings;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.compat.MinecraftCompat;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.config.WaypointerConfigCodec;
import dev.ethan.waypointer.update.UpdateChecker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frame-driven state machine behind the settings screen's performance stress
 * test. {@code onFrame()} is called once per rendered frame while the settings
 * screen is open; the controller sweeps the {@link PerfScenarios} list over
 * the live config (settle window discarded, sample window recorded), collects
 * frame times plus CPU/GPU/heap utilization, restores the user's settings from
 * a snapshot, and hands {@link PerfReport} the numbers.
 *
 * <p>A sweep over whatever happens to be in the scene measures nothing when
 * the user has two waypoints, so {@code start()} installs the synthetic
 * {@link PerfStressRoute} grid around the player and removes it with the
 * settings restore.
 *
 * <p>State is static so a finished report survives screen rebuilds and
 * reopen. The pre-test snapshot is additionally written to disk
 * ({@code perf-test-backup.wpc}) so a crash mid-sweep cannot permanently
 * strand the user on stress-test settings — the next settings-screen open
 * recovers it. The stress grid needs no disk backup: it is a temp group,
 * which never persists past the session.
 */
public final class PerfStressTestController {

    private static final long UTIL_SAMPLE_INTERVAL_MS = 200;
    /** Frames longer than this are screen-transition hitches, not render cost. */
    private static final long STALE_FRAME_NANOS = 500_000_000L;
    private static final String BACKUP_FILE_NAME = "perf-test-backup.wpc";

    private static boolean running;
    private static WaypointerConfig target;
    private static WaypointerConfig snapshot;
    private static List<PerfScenarios.Scenario> scenarios = List.of();
    private static int scenarioIndex;
    private static boolean sampling;
    private static long phaseStartNanos;
    private static long lastFrameNanos;
    private static long lastUtilSampleNanos;
    private static long heapAtSampleStart;
    private static long testStartNanos;
    private static List<Long> frameNanos = new ArrayList<>();
    private static List<double[]> utilSamples = new ArrayList<>();
    private static List<PerfReport.ScenarioResult> results = new ArrayList<>();
    private static int syntheticWaypoints;

    private static String lastReport;
    private static String statusOverride;

    private PerfStressTestController() {}

    public static synchronized boolean running() {
        return running;
    }

    public static synchronized boolean hasReport() {
        return lastReport != null;
    }

    public static synchronized String report() {
        return lastReport;
    }

    public static synchronized void noteReportCopied() {
        if (!running) statusOverride = "Report copied to clipboard.";
    }

    public static synchronized String statusLine() {
        if (running) {
            PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
            return String.format(Locale.ROOT, "Testing %d/%d: %s%s",
                    scenarioIndex + 1, scenarios.size(), scenario.label(),
                    sampling ? "" : " (settling)");
        }
        if (statusOverride != null) return statusOverride;
        int count = PerfScenarios.all().size();
        double seconds = count * (PerfScenarios.SETTLE_MS + PerfScenarios.SAMPLE_MS) / 1000.0;
        return String.format(Locale.ROOT,
                "Sweeps %d render scenarios (~%.0fs) over a temporary %d-waypoint stress grid "
                        + "spawned around you and removed afterwards. Stand still while it runs.",
                count, seconds, PerfStressRoute.WAYPOINT_COUNT);
    }

    /** Begin a sweep over the live config. No-op when one is already running. */
    public static synchronized boolean start(WaypointerConfig config) {
        if (running || config == null) return false;
        target = config;
        snapshot = WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(config));
        writeBackup(config);
        syntheticWaypoints = installStressRoute();
        scenarios = PerfScenarios.all();
        results = new ArrayList<>();
        statusOverride = null;
        running = true;
        testStartNanos = System.nanoTime();
        lastFrameNanos = 0;
        beginScenario(0);
        return true;
    }

    /** Restore settings and stop; used by the Cancel button and screen close. */
    public static synchronized boolean cancelIfRunning() {
        if (!running) return false;
        restoreSnapshot();
        running = false;
        statusOverride = "Test cancelled - settings restored.";
        return true;
    }

    /**
     * Advance the sweep by one rendered frame.
     *
     * @return true when the scenario set changed (the screen should rebuild
     *         its rows so controls reflect the scenario's config values).
     */
    public static synchronized boolean onFrame() {
        if (!running) return false;
        long now = System.nanoTime();
        long delta = lastFrameNanos == 0 ? 0 : now - lastFrameNanos;
        lastFrameNanos = now;
        if (delta < 0 || delta > STALE_FRAME_NANOS) delta = 0;

        if (sampling && delta > 0) {
            frameNanos.add(delta);
            if (lastUtilSampleNanos == 0
                    || (now - lastUtilSampleNanos) / 1_000_000 >= UTIL_SAMPLE_INTERVAL_MS) {
                lastUtilSampleNanos = now;
                utilSamples.add(sampleUtilization());
            }
        }

        long phaseMs = (now - phaseStartNanos) / 1_000_000;
        if (!sampling) {
            if (phaseMs >= PerfScenarios.SETTLE_MS) {
                sampling = true;
                phaseStartNanos = now;
                lastUtilSampleNanos = 0;
                heapAtSampleStart = usedHeap();
                frameNanos.clear();
                utilSamples.clear();
            }
            return false;
        }

        if (phaseMs < PerfScenarios.SAMPLE_MS) return false;

        PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
        results.add(PerfReport.result(scenario.label(), scenario.description(),
                frameNanos, utilSamples, usedHeap() - heapAtSampleStart));

        if (scenarioIndex + 1 < scenarios.size()) {
            beginScenario(scenarioIndex + 1);
            return true;
        }
        finish();
        return true;
    }

    private static void beginScenario(int index) {
        scenarioIndex = index;
        scenarios.get(index).apply().accept(target);
        sampling = false;
        phaseStartNanos = System.nanoTime();
        frameNanos = new ArrayList<>();
        utilSamples = new ArrayList<>();
    }

    private static void finish() {
        double totalSeconds = (System.nanoTime() - testStartNanos) / 1e9;
        lastReport = PerfReport.format(environment(), results, totalSeconds);
        restoreSnapshot();
        running = false;
        statusOverride = String.format(Locale.ROOT,
                "Test complete - %d scenarios in %.1fs. Settings restored; copy the report to share.",
                results.size(), totalSeconds);
    }

    private static void restoreSnapshot() {
        if (target != null && snapshot != null) {
            target.replaceWith(snapshot);
        }
        target = null;
        snapshot = null;
        PerfStressRoute.remove(WaypointerClient.manager());
        deleteBackup();
    }

    private static int installStressRoute() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        return PerfStressRoute.install(WaypointerClient.manager(),
                player.getX(), player.getY(), player.getZ());
    }

    /**
     * Crash safety net: if a previous sweep never restored (JVM died
     * mid-test), put the user's settings back the next time the settings
     * screen opens. Returns true when a recovery happened.
     */
    public static synchronized boolean recoverInterruptedTest(WaypointerConfig config) {
        if (running || config == null) return false;
        Path backup = backupPath();
        try {
            if (!Files.exists(backup)) return false;
            WaypointerConfig recovered = WaypointerConfigCodec.decode(Files.readString(backup).trim());
            config.replaceWith(recovered);
            statusOverride = "Recovered settings from an interrupted performance test.";
            return true;
        } catch (Exception e) {
            Waypointer.LOGGER.warn("Could not recover perf-test settings backup", e);
            return false;
        } finally {
            deleteBackup();
        }
    }

    private static void writeBackup(WaypointerConfig config) {
        try {
            Path backup = backupPath();
            Files.createDirectories(backup.getParent());
            Files.writeString(backup, WaypointerConfigCodec.encode(config));
        } catch (Exception e) {
            Waypointer.LOGGER.warn("Could not write perf-test settings backup", e);
        }
    }

    private static void deleteBackup() {
        try {
            Files.deleteIfExists(backupPath());
        } catch (Exception ignored) {
        }
    }

    private static Path backupPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID).resolve(BACKUP_FILE_NAME);
    }

    // --- metrics -------------------------------------------------------------------------------

    /** {@code {processCpuPct, systemCpuPct, gpuPct}}; negative = unavailable. */
    private static double[] sampleUtilization() {
        double processCpu = -1;
        double systemCpu = -1;
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double process = sun.getProcessCpuLoad();
                double system = sun.getCpuLoad();
                if (process >= 0) processCpu = process * 100.0;
                if (system >= 0) systemCpu = system * 100.0;
            }
        } catch (Throwable ignored) {
        }

        double gpu = -1;
        try {
            gpu = Minecraft.getInstance().getGpuUtilization();
        } catch (Throwable ignored) {
        }
        return new double[]{processCpu, systemCpu, gpu};
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static PerfReport.Environment environment() {
        Minecraft minecraft = Minecraft.getInstance();

        String gpuVendor = "unknown";
        String gpuBackend = "unknown";
        String gpuInfo = "";
        try {
            var device = RenderSystem.tryGetDevice();
            if (device != null) {
                MinecraftCompat.GpuInfo info = MinecraftCompat.gpuInfo(device);
                gpuVendor = info.vendor();
                gpuBackend = info.backend();
                gpuInfo = info.implementation();
            }
        } catch (Throwable ignored) {
        }

        boolean vsync = false;
        int fpsCap = 260;
        try {
            vsync = minecraft.options.enableVsync().get();
            fpsCap = minecraft.options.framerateLimit().get();
        } catch (Throwable ignored) {
        }

        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        return new PerfReport.Environment(
                UpdateChecker.currentModVersion(),
                minecraftVersion,
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                gpuVendor, gpuBackend, gpuInfo,
                vsync, fpsCap,
                syntheticWaypoints > 0
                        ? String.format(Locale.ROOT, "Measured with the settings screen open over the live "
                                + "world plus a temporary %d-waypoint stress grid centered on your position "
                                + "(removed when the test ends).", syntheticWaypoints)
                        : "Measured with the settings screen open over the live world. No synthetic stress "
                                + "grid could be installed (no active zone), so results reflect only your "
                                + "existing waypoints.");
    }
}
