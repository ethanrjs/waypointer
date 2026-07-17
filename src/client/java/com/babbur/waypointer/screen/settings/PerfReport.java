package com.babbur.waypointer.screen.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Statistics and verbose text formatting for the performance stress test.
 *
 * <p>MC-free on purpose: the controller feeds raw frame times and utilization
 * samples in; this class owns percentile math and the report layout, so both
 * are unit-testable without a running client.
 */
public final class PerfReport {

    /** Machine/session context printed at the top of the report. */
    public record Environment(String modVersion, String minecraftVersion, String osName,
                              String javaVersion, int cpuThreads, long maxHeapMb,
                              String gpuVendor, String gpuBackend, String gpuInfo,
                              boolean vsync, int fpsCap, String note) {}

    /** One scenario's aggregated numbers. Negative utilization = unavailable. */
    public record ScenarioResult(String label, String description, int frames, long elapsedNanos,
                                 double minFrameMs, double avgFrameMs, double maxFrameMs,
                                 double p99FrameMs, double avgProcessCpuPct, double maxProcessCpuPct,
                                 double avgSystemCpuPct, double avgGpuPct, double maxGpuPct,
                                 long heapDeltaBytes) {

        public double avgFps() {
            return avgFrameMs <= 0 ? 0 : 1000.0 / avgFrameMs;
        }

        /** "1% low" style figure: the frame rate at the 99th-percentile frame time. */
        public double onePercentLowFps() {
            return p99FrameMs <= 0 ? 0 : 1000.0 / p99FrameMs;
        }
    }

    private PerfReport() {}

    /**
     * Aggregate raw samples into a result. {@code utilSamples} entries are
     * {@code {processCpuPct, systemCpuPct, gpuPct}} with negatives meaning the
     * source was unavailable.
     */
    public static ScenarioResult result(String label, String description,
                                        List<Long> frameNanos, List<double[]> utilSamples,
                                        long heapDeltaBytes) {
        int frames = frameNanos.size();
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long nanos : frameNanos) {
            total += nanos;
            min = Math.min(min, nanos);
            max = Math.max(max, nanos);
        }
        double avgMs = frames == 0 ? 0 : total / 1e6 / frames;
        double minMs = frames == 0 ? 0 : min / 1e6;
        double maxMs = frames == 0 ? 0 : max / 1e6;
        double p99Ms = frames == 0 ? 0 : percentile(frameNanos, 0.99) / 1e6;

        double[] proc = averageAndMax(utilSamples, 0);
        double[] sys = averageAndMax(utilSamples, 1);
        double[] gpu = averageAndMax(utilSamples, 2);

        return new ScenarioResult(label, description, frames, total,
                minMs, avgMs, maxMs, p99Ms,
                proc[0], proc[1], sys[0], gpu[0], gpu[1], heapDeltaBytes);
    }

    /**
     * Upper nearest-rank percentile: the boundary lands inside the slow tail,
     * so with 100 samples the p99 IS the single worst frame — which is what a
     * "1% low" figure should report.
     */
    static double percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int index = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** {@code {average, max}} over the given component, ignoring negative (unavailable) samples. */
    private static double[] averageAndMax(List<double[]> samples, int component) {
        double sum = 0;
        double max = -1;
        int count = 0;
        for (double[] sample : samples) {
            double value = sample[component];
            if (value < 0) continue;
            sum += value;
            max = Math.max(max, value);
            count++;
        }
        return count == 0 ? new double[]{-1, -1} : new double[]{sum / count, max};
    }

    public static String format(Environment env, List<ScenarioResult> results, double totalSeconds) {
        StringBuilder out = new StringBuilder(4_096);
        out.append("=== Waypointer performance report ===\n");
        out.append("Generated: ").append(java.time.ZonedDateTime.now()
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)).append('\n');
        out.append("Waypointer ").append(env.modVersion())
                .append(" | Minecraft ").append(env.minecraftVersion()).append('\n');
        out.append("OS: ").append(env.osName())
                .append(" | Java: ").append(env.javaVersion())
                .append(" | CPU threads: ").append(env.cpuThreads())
                .append(" | Max heap: ").append(env.maxHeapMb()).append(" MB\n");
        out.append("GPU: ").append(env.gpuVendor()).append(" (").append(env.gpuBackend()).append(") ")
                .append(env.gpuInfo()).append('\n');
        out.append("VSync: ").append(env.vsync() ? "ON" : "off")
                .append(" | FPS cap: ").append(env.fpsCap() >= 260 ? "unlimited" : String.valueOf(env.fpsCap()))
                .append('\n');
        if (env.vsync() || env.fpsCap() < 260) {
            out.append("!! Frame rate is capped (vsync or FPS limit). Scenario differences are\n")
               .append("!! compressed against the cap; uncap for meaningful comparisons.\n");
        }
        if (env.note() != null && !env.note().isBlank()) {
            out.append("Note: ").append(env.note()).append('\n');
        }
        out.append(String.format(Locale.ROOT,
                "Sweep: %d recorded windows, %.1fs active sweep time (%.0fs target; "
                        + "includes settle windows; stale/minimized gaps excluded)%n",
                results.size(), totalSeconds, PerfScenarios.TARGET_ACTIVE_MS / 1000.0));
        out.append('\n');

        out.append(String.format(Locale.ROOT,
                "%-38s %6s %8s %8s %9s %8s %8s %8s %8s %7s%n",
                "Scenario", "Frames", "Avg FPS", "1% low",
                "Avg ms", "Min ms", "Max ms", "CPU proc", "CPU sys", "GPU"));
        ScenarioResult baseline = results.isEmpty() ? null : results.get(0);
        for (ScenarioResult result : results) {
            out.append(String.format(Locale.ROOT,
                    "%-38s %6d %8.1f %8.1f %9.2f %8.2f %8.2f %8s %8s %7s%n",
                    clip(result.label(), 38), result.frames(), result.avgFps(),
                    result.onePercentLowFps(), result.avgFrameMs(), result.minFrameMs(),
                    result.maxFrameMs(),
                    pct(result.avgProcessCpuPct()), pct(result.avgSystemCpuPct()),
                    pct(result.avgGpuPct())));
        }

        if (baseline != null && baseline.avgFps() > 0) {
            out.append('\n').append("Cost relative to \"").append(baseline.label()).append("\":\n");
            for (int i = 1; i < results.size(); i++) {
                ScenarioResult result = results.get(i);
                double deltaPct = (result.avgFps() - baseline.avgFps()) / baseline.avgFps() * 100.0;
                out.append(String.format(Locale.ROOT, "%-38s %+7.1f%% FPS (%.1f -> %.1f)%n",
                        clip(result.label(), 38), deltaPct, baseline.avgFps(), result.avgFps()));
            }
        }

        out.append('\n').append("Scenario details:\n");
        for (ScenarioResult result : results) {
            out.append("- ").append(result.label()).append(": ").append(result.description())
               .append(String.format(Locale.ROOT,
                       " [p99 %.2f ms, CPU proc max %s, GPU max %s, heap delta %+d KB]%n",
                       result.p99FrameMs(), pct(result.maxProcessCpuPct()),
                       pct(result.maxGpuPct()), result.heapDeltaBytes() / 1024));
        }
        return out.toString();
    }

    private static String pct(double value) {
        return value < 0 ? "n/a" : String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
