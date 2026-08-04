package com.babbur.waypointer.screen.settings;

import com.mojang.blaze3d.systems.RenderSystem;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Frame-driven state machine behind the settings screen's performance stress
 * test. {@code onFrame()} is called once per rendered frame while the settings
 * screen remains open with its normal overlay suppressed; the controller sweeps the {@link PerfScenarios} list over
 * the live config (settle window discarded, sample window recorded), collects
 * frame times plus CPU/GPU/heap utilization, restores the user's settings from
 * a snapshot, and hands {@link PerfReport} the numbers.
 *
 * <p>A sweep over whatever happens to be in the scene measures nothing when
 * the user has two waypoints, so {@code start()} installs the synthetic
 * {@link PerfStressRoute} profiles around the player and removes them with the
 * settings restore. The final phase doubles subwaypoint density until it hits
 * the configured ceiling or sustained four-FPS frame time.
 *
 * <p>State is static so a finished report survives screen rebuilds and
 * reopen. The pre-test snapshot is additionally written to disk
 * ({@code perf-test-backup.wpc}) so a crash mid-sweep cannot permanently
 * strand the user on stress-test settings — the next settings-screen open
 * recovers it. Stress groups are runtime-only and never persist past the session.
 */
public final class PerfStressTestController {

    private static final long UTIL_SAMPLE_INTERVAL_MS = 200;
    /** Frames longer than this are screen-transition hitches, not render cost. */
    private static final long STALE_FRAME_NANOS = 500_000_000L;
    private static final long LAG_FRAME_NANOS = 250_000_000L;
    private static final long HARD_STALL_NANOS = 1_000_000_000L;
    private static final int LAG_FRAME_STREAK = 3;
    private static final int ADAPTIVE_MIN_CHILDREN = 3;
    private static final int ADAPTIVE_MAX_CHILDREN = 127;
    private static final String BACKUP_FILE_NAME = "perf-test-backup.wpc";

    private static boolean running;
    private static WaypointerConfig target;
    private static WaypointerConfig snapshot;
    private static List<PerfScenarios.Scenario> scenarios = List.of();
    private static int scenarioIndex;
    private static boolean sampling;
    private static ActiveBudget activeBudget = ActiveBudget.production();
    private static long lastFrameNanos;
    private static long lastUtilSampleNanos;
    private static long heapAtSampleStart;
    private static List<Long> frameNanos = new ArrayList<>();
    private static List<double[]> utilSamples = new ArrayList<>();
    private static List<PerfReport.ScenarioResult> results = new ArrayList<>();
    private static int syntheticWaypoints;
    private static int peakSyntheticWaypoints;
    private static double stressX;
    private static double stressY;
    private static double stressZ;
    private static int adaptiveChildren;
    private static int consecutiveLagFrames;
    private static boolean adaptiveStopped;
    private static String adaptiveStopReason;

    private static String lastReport;
    private static String statusOverride;
    private static Component statusOverrideComponent;

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
        if (!running) {
            statusOverride = "Report copied to clipboard.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.copied");
        }
    }

    public static synchronized String statusLine() {
        if (running) {
            PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
            long secondsLeft = Math.max(0, (long) Math.ceil(activeBudget.remainingNanos() / 1e9));
            return String.format(Locale.ROOT, "Testing %d/%d: %s%s - %ds left",
                    scenarioIndex + 1, scenarios.size(), scenario.label(),
                    sampling ? "" : " (settling)", secondsLeft);
        }
        if (statusOverride != null) return statusOverride;
        return String.format(Locale.ROOT,
                "Runs a 60-second active-frame sweep across 3D waypoints, dungeon secrets, "
                        + "and an adaptive subwaypoint ramp. The settings overlay hides while it runs.");
    }

    public static synchronized Component statusComponent() {
        if (running) {
            PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
            long secondsLeft = Math.max(0,
                    (long) Math.ceil(activeBudget.remainingNanos() / 1e9));
            return Component.translatable("waypointer.screen.settings.perf.status.testing",
                    scenarioIndex + 1,
                    scenarios.size(),
                    Component.translatable(PerfScenarios.labelTranslationKey(scenario)),
                    sampling ? Component.empty() : Component.translatable(
                            "waypointer.screen.settings.perf.status.settling"),
                    secondsLeft);
        }
        if (statusOverrideComponent != null) return statusOverrideComponent;
        return Component.translatable("waypointer.screen.settings.perf.status.description");
    }

    /** Begin a sweep over the live config. No-op when one is already running. */
    public static synchronized boolean start(WaypointerConfig config) {
        if (running || config == null) return false;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || WaypointerClient.manager() == null
                || WaypointerClient.manager().currentZone() == null) {
            statusOverride = "Enter a world with an active zone before running the test.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.enter_world");
            return false;
        }
        target = config;
        String encodedSnapshot = WaypointerConfigCodec.encode(config);
        snapshot = WaypointerConfigCodec.decode(encodedSnapshot);
        try {
            writeBackupAtomically(backupPath(), encodedSnapshot);
        } catch (IOException failure) {
            Waypointer.LOGGER.warn("Could not write perf-test settings backup", failure);
            target = null;
            snapshot = null;
            statusOverride = "Performance test could not start because its settings backup failed.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.start_failed");
            return false;
        }
        scenarios = PerfScenarios.all();
        results = new ArrayList<>();
        lastReport = null;
        statusOverride = null;
        statusOverrideComponent = null;
        running = true;
        activeBudget = ActiveBudget.production();
        stressX = player.getX();
        stressY = player.getY();
        stressZ = player.getZ();
        syntheticWaypoints = 0;
        peakSyntheticWaypoints = 0;
        lastFrameNanos = 0;
        adaptiveChildren = 7;
        adaptiveStopped = false;
        adaptiveStopReason = null;
        try {
            beginScenario(0);
            return true;
        } catch (Throwable failure) {
            Waypointer.LOGGER.error("Could not start performance test", failure);
            running = false;
            restoreSnapshot();
            statusOverride = "Performance test could not start; settings restored.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.start_failed");
            return false;
        }
    }

    /** Restore settings and stop; used by the Cancel button and screen close. */
    public static synchronized boolean cancelIfRunning() {
        if (!running) return false;
        running = false;
        restoreSnapshot();
        statusOverride = "Test cancelled - settings restored.";
        statusOverrideComponent = Component.translatable(
                "waypointer.screen.settings.perf.status.cancelled");
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
        try {
            return advanceFrame(System.nanoTime());
        } catch (Throwable failure) {
            Waypointer.LOGGER.error("Performance test failed", failure);
            running = false;
            restoreSnapshot();
            statusOverride = "Performance test failed; settings restored.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.failed");
            return true;
        }
    }

    private static boolean advanceFrame(long now) {
        long rawDelta = lastFrameNanos == 0 ? 0 : now - lastFrameNanos;
        lastFrameNanos = now;
        consecutiveLagFrames = nextLagStreak(consecutiveLagFrames, rawDelta);
        if (hardStall(rawDelta) || sustainedLag(consecutiveLagFrames)) {
            String reason = hardStall(rawDelta)
                    ? String.format(Locale.ROOT, "a %.2fs frame stall", rawDelta / 1e9)
                    : "three consecutive frames at or above 250 ms";
            finishEarlyForLag(reason);
            return true;
        }
        long activeDelta = activeBudget.accept(rawDelta);

        PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
        if (sampling && activeDelta > 0) {
            frameNanos.add(rawDelta);
            if (lastUtilSampleNanos == 0
                    || (now - lastUtilSampleNanos) / 1_000_000 >= UTIL_SAMPLE_INTERVAL_MS) {
                lastUtilSampleNanos = now;
                utilSamples.add(sampleUtilization());
            }
        }

        if (activeBudget.finished()) {
            recordCurrentSample("final partial window");
            finish();
            return true;
        }
        if (activeDelta == 0) return false;

        if (!sampling) {
            if (activeBudget.phaseNanos() >= PerfScenarios.SETTLE_MS * 1_000_000L) {
                startSampling();
            }
            return false;
        }

        long sampleTargetMs = scenario.adaptive()
                ? PerfScenarios.ADAPTIVE_SAMPLE_MS : PerfScenarios.SAMPLE_MS;
        if (activeBudget.phaseNanos() < sampleTargetMs * 1_000_000L) return false;

        recordCurrentSample(null);
        if (scenario.adaptive()) {
            advanceAdaptiveLoad();
            return true;
        }
        beginScenario(scenarioIndex + 1);
        return true;
    }

    private static void beginScenario(int index) {
        scenarioIndex = index;
        PerfScenarios.Scenario scenario = scenarios.get(index);
        scenario.apply().accept(target);
        PerfStressRoute.Load load = scenario.adaptive()
                ? adaptiveLoad(adaptiveChildren) : scenario.load();
        syntheticWaypoints = installStressRoute(load);
        if (syntheticWaypoints <= 0) throw new IllegalStateException("No active zone for stress route");
        peakSyntheticWaypoints = Math.max(peakSyntheticWaypoints, syntheticWaypoints);
        sampling = false;
        activeBudget.resetPhase();
        consecutiveLagFrames = 0;
        frameNanos = new ArrayList<>();
        utilSamples = new ArrayList<>();
    }

    private static void startSampling() {
        sampling = true;
        activeBudget.resetPhase();
        lastUtilSampleNanos = 0;
        heapAtSampleStart = usedHeap();
        frameNanos.clear();
        utilSamples.clear();
        consecutiveLagFrames = 0;
    }

    private static void recordCurrentSample(String suffix) {
        if (!sampling || frameNanos.isEmpty()) return;
        PerfScenarios.Scenario scenario = scenarios.get(scenarioIndex);
        String label = scenario.label() + " (" + syntheticWaypoints + " points)";
        String description = scenario.description() + "; synthetic profile "
                + (scenario.adaptive()
                ? PerfStressRoute.Profile.SUBWAYPOINTS_3D : scenario.load().profile());
        if (scenario.adaptive()) {
            if (adaptiveStopped && adaptiveStopReason != null) {
                description += "; ramp stopped: " + adaptiveStopReason;
            }
        }
        if (suffix != null) description += "; " + suffix;
        results.add(PerfReport.result(label, description,
                frameNanos, utilSamples, usedHeap() - heapAtSampleStart));
    }

    private static void advanceAdaptiveLoad() {
        if (adaptiveStopped || adaptiveChildren >= ADAPTIVE_MAX_CHILDREN) {
            adaptiveStopped = true;
            if (adaptiveStopReason == null) adaptiveStopReason = "maximum 8,192-point load reached";
            startSampling();
            return;
        }

        adaptiveChildren = nextAdaptiveChildren(adaptiveChildren);
        installAdaptiveLoad();
    }

    private static void installAdaptiveLoad() {
        syntheticWaypoints = installStressRoute(adaptiveLoad(adaptiveChildren));
        peakSyntheticWaypoints = Math.max(peakSyntheticWaypoints, syntheticWaypoints);
        sampling = false;
        activeBudget.resetPhase();
        frameNanos = new ArrayList<>();
        utilSamples = new ArrayList<>();
        consecutiveLagFrames = 0;
    }

    static long sanitizeActiveDelta(long delta) {
        return delta <= 0 || delta > STALE_FRAME_NANOS ? 0 : delta;
    }

    static final class ActiveBudget {
        private final long targetNanos;
        private long activeNanos;
        private long phaseNanos;

        ActiveBudget(long targetNanos) {
            this.targetNanos = targetNanos;
        }

        static ActiveBudget production() {
            return new ActiveBudget(PerfScenarios.TARGET_ACTIVE_MS * 1_000_000L);
        }

        long accept(long rawDelta) {
            long activeDelta = sanitizeActiveDelta(rawDelta);
            long budgeted = Math.min(activeDelta, remainingNanos());
            activeNanos += budgeted;
            phaseNanos += budgeted;
            return activeDelta;
        }

        boolean finished() {
            return activeNanos >= targetNanos;
        }

        long remainingNanos() {
            return Math.max(0, targetNanos - activeNanos);
        }

        long activeNanos() {
            return activeNanos;
        }

        long phaseNanos() {
            return phaseNanos;
        }

        void resetPhase() {
            phaseNanos = 0;
        }
    }

    static int nextAdaptiveChildren(int current) {
        return Math.min(ADAPTIVE_MAX_CHILDREN, Math.max(ADAPTIVE_MIN_CHILDREN, current * 2 + 1));
    }

    static int nextLagStreak(int currentStreak, long rawDelta) {
        return rawDelta >= LAG_FRAME_NANOS ? currentStreak + 1 : 0;
    }

    static boolean sustainedLag(int streak) {
        return streak >= LAG_FRAME_STREAK;
    }

    static boolean hardStall(long rawDelta) {
        return rawDelta >= HARD_STALL_NANOS;
    }

    private static PerfStressRoute.Load adaptiveLoad(int childrenPerMain) {
        return new PerfStressRoute.Load(
                PerfStressRoute.Profile.SUBWAYPOINTS_3D, 64, childrenPerMain);
    }

    private static void finish() {
        double totalSeconds = activeBudget.activeNanos() / 1e9;
        lastReport = PerfReport.format(environment(), results, totalSeconds);
        running = false;
        restoreSnapshot();
        statusOverride = String.format(Locale.ROOT,
                "Test complete - %d samples in %.1fs. Settings restored; copy the report to share.",
                results.size(), totalSeconds);
        statusOverrideComponent = Component.translatable(
                "waypointer.screen.settings.perf.status.complete",
                results.size(), String.format(Locale.ROOT, "%.1f", totalSeconds));
    }

    private static void finishEarlyForLag(String reason) {
        recordCurrentSample("lag cutoff");
        adaptiveStopReason = reason + " at " + syntheticWaypoints + " points";
        double totalSeconds = activeBudget.activeNanos() / 1e9;
        lastReport = PerfReport.format(environment(), results, totalSeconds);
        running = false;
        restoreSnapshot();
        statusOverride = String.format(Locale.ROOT,
                "Test stopped early after %.1fs: %s. Settings restored; report available.",
                totalSeconds, reason);
        statusOverrideComponent = Component.translatable(
                "waypointer.screen.settings.perf.status.stopped",
                String.format(Locale.ROOT, "%.1f", totalSeconds), reason);
    }

    private static void restoreSnapshot() {
        WaypointerConfig restoreTarget = target;
        WaypointerConfig restoreSnapshot = snapshot;
        target = null;
        snapshot = null;
        try {
            if (restoreTarget != null && restoreSnapshot != null) {
                restoreTarget.replaceWith(restoreSnapshot);
            }
        } catch (Throwable failure) {
            Waypointer.LOGGER.warn("Could not restore settings after performance test", failure);
        }
        try {
            PerfStressRoute.remove(WaypointerClient.manager());
        } catch (Throwable failure) {
            Waypointer.LOGGER.warn("Could not remove performance stress route", failure);
        }
        deleteBackup();
    }

    private static int installStressRoute(PerfStressRoute.Load load) {
        return PerfStressRoute.install(WaypointerClient.manager(),
                stressX, stressY, stressZ, load);
    }

    /**
     * Crash safety net: if a previous sweep never restored (JVM died
     * mid-test), put the user's settings back the next time the settings
     * screen opens. Returns true when a recovery happened.
     */
    public static synchronized boolean recoverInterruptedTest(WaypointerConfig config) {
        if (running || config == null) return false;
        Path backup = backupPath();
        Path recovery = backup.resolveSibling(backup.getFileName() + ".recovery");
        try {
            recovery = claimRecoveryBackup(backup, recovery);
            if (recovery == null) return false;
        } catch (IOException failure) {
            Waypointer.LOGGER.warn(
                    "Could not claim perf-test settings backup for recovery", failure);
            return false;
        }

        try {
            WaypointerConfig recovered =
                    WaypointerConfigCodec.decode(Files.readString(recovery).trim());
            config.replaceWith(recovered);
            statusOverride = "Recovered settings from an interrupted performance test.";
            statusOverrideComponent = Component.translatable(
                    "waypointer.screen.settings.perf.status.recovered");
        } catch (Exception e) {
            Waypointer.LOGGER.warn(
                    "Could not recover perf-test settings backup; retained it at {}",
                    recovery,
                    e);
            return false;
        }
        retireRecoveredBackup(recovery);
        return true;
    }

    static void writeBackupAtomically(Path backup, String encoded) throws IOException {
        Path parent = backup.getParent();
        if (parent == null) throw new IOException("backup path has no parent");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent, backup.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(temporary, encoded);
            Files.move(
                    temporary,
                    backup,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteBackup() {
        Path backup = backupPath();
        try {
            if (!Files.exists(backup)) return;
            deleteQuarantinedBackup(quarantineBackup(backup));
        } catch (IOException failure) {
            Waypointer.LOGGER.warn(
                    "Could not quarantine stale perf-test settings backup at {}",
                    backup,
                    failure);
        }
    }

    static Path quarantineBackup(Path backup) throws IOException {
        Path quarantined = backup.resolveSibling(
                backup.getFileName() + ".quarantine-" + UUID.randomUUID());
        return Files.move(backup, quarantined, StandardCopyOption.ATOMIC_MOVE);
    }

    static Path claimRecoveryBackup(Path backup, Path recovery) throws IOException {
        if (Files.exists(backup)) {
            return Files.move(
                    backup,
                    recovery,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.exists(recovery) ? recovery : null;
    }

    private static void retireRecoveredBackup(Path recovery) {
        try {
            deleteQuarantinedBackup(quarantineBackup(recovery));
        } catch (IOException failure) {
            Waypointer.LOGGER.warn(
                    "Recovered perf-test settings backup could not be retired at {}",
                    recovery,
                    failure);
        }
    }

    private static void deleteQuarantinedBackup(Path quarantined) {
        try {
            Files.deleteIfExists(quarantined);
        } catch (IOException failure) {
            Waypointer.LOGGER.warn(
                    "Retired perf-test settings backup could not be deleted; retained at {}",
                    quarantined,
                    failure);
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
        var waypointerContainer = FabricLoader.getInstance().getModContainer(Waypointer.MOD_ID);
        String waypointerVersion = waypointerContainer.isPresent()
                ? waypointerContainer.get().getMetadata().getVersion().getFriendlyString()
                : "unknown";

        return new PerfReport.Environment(
                waypointerVersion,
                minecraftVersion,
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                gpuVendor, gpuBackend, gpuInfo,
                vsync, fpsCap,
                peakSyntheticWaypoints > 0
                        ? String.format(Locale.ROOT, "Measured over the live world with the normal settings "
                                + "overlay hidden. Synthetic 3D/secret/subwaypoint loads peaked at %d points "
                                + "and were removed when the test ended.%s", peakSyntheticWaypoints,
                                adaptiveStopReason == null ? ""
                                        : " Safety cutoff: " + adaptiveStopReason + ".")
                        : "No synthetic stress route was available; results are incomplete.");
    }
}
