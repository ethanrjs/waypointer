package dev.ethan.waypointer.screen.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfReportTest {

    @Test
    void aggregatesFrameTimesIntoFpsAndPercentiles() {
        List<Long> frames = new ArrayList<>();
        for (int i = 0; i < 99; i++) frames.add(10_000_000L); // 10 ms
        frames.add(50_000_000L);                              // one 50 ms hitch

        PerfReport.ScenarioResult result = PerfReport.result("test", "desc", frames,
                List.of(new double[]{50, 60, 70}, new double[]{70, 80, 90}), 2048);

        assertEquals(100, result.frames());
        assertEquals(10.4, result.avgFrameMs(), 0.01);
        assertEquals(10.0, result.minFrameMs(), 0.001);
        assertEquals(50.0, result.maxFrameMs(), 0.001);
        assertEquals(50.0, result.p99FrameMs(), 0.001, "p99 catches the hitch frame");
        assertEquals(1000.0 / 10.4, result.avgFps(), 0.1);
        assertEquals(20.0, result.onePercentLowFps(), 0.001);
        assertEquals(60.0, result.avgProcessCpuPct(), 0.001);
        assertEquals(70.0, result.maxProcessCpuPct(), 0.001);
        assertEquals(80.0, result.avgGpuPct(), 0.001);
    }

    @Test
    void unavailableUtilizationSamplesStayOutOfAverages() {
        PerfReport.ScenarioResult result = PerfReport.result("test", "desc",
                List.of(16_000_000L),
                List.of(new double[]{-1, -1, 40}, new double[]{-1, -1, 60}), 0);

        assertEquals(-1, result.avgProcessCpuPct(), 0.001);
        assertEquals(50.0, result.avgGpuPct(), 0.001);
    }

    @Test
    void emptySampleSetDoesNotDivideByZero() {
        PerfReport.ScenarioResult result = PerfReport.result("test", "desc", List.of(), List.of(), 0);
        assertEquals(0, result.frames());
        assertEquals(0.0, result.avgFps(), 0.001);
    }

    @Test
    void formatIsVerboseAndComparesAgainstTheBaseline() {
        PerfReport.Environment env = new PerfReport.Environment(
                "1.8.0-beta", "26.1.2", "Windows 11 10.0", "25", 16, 4096,
                "NVIDIA", "OpenGL", "RTX 4080/PCIe", false, 260, "test note");
        PerfReport.ScenarioResult baseline = PerfReport.result("Baseline", "nothing rendered",
                framesOf(100, 5_000_000L), List.of(new double[]{20, 30, 10}), 0);
        PerfReport.ScenarioResult heavy = PerfReport.result("Everything", "all features",
                framesOf(100, 10_000_000L), List.of(new double[]{60, 70, 90}), 4096);

        String report = PerfReport.format(env, List.of(baseline, heavy), 19.5);

        assertTrue(report.contains("Waypointer performance report"));
        assertTrue(report.contains("Waypointer 1.8.0-beta | Minecraft 26.1.2"));
        assertTrue(report.contains("GPU: NVIDIA (OpenGL) RTX 4080/PCIe"));
        assertTrue(report.contains("CPU threads: 16"));
        assertTrue(report.contains("Avg FPS"));
        assertTrue(report.contains("Baseline"));
        assertTrue(report.contains("Everything"));
        assertTrue(report.contains("Cost relative to \"Baseline\""));
        assertTrue(report.contains("-50.0% FPS (200.0 -> 100.0)"));
        assertTrue(report.contains("Scenario details:"));
        assertTrue(report.contains("test note"));
        assertFalse(report.contains("!! Frame rate is capped"),
                "no cap warning when uncapped without vsync");
    }

    @Test
    void formatWarnsWhenTheFrameRateIsCapped() {
        PerfReport.Environment vsynced = new PerfReport.Environment(
                "1.8.0-beta", "26.1.2", "os", "25", 16, 4096,
                "gpu", "gl", "", true, 260, "");
        String report = PerfReport.format(vsynced, List.of(), 0);
        assertTrue(report.contains("!! Frame rate is capped"));
        assertTrue(report.contains("VSync: ON"));

        PerfReport.Environment capped = new PerfReport.Environment(
                "1.8.0-beta", "26.1.2", "os", "25", 16, 4096,
                "gpu", "gl", "", false, 120, "");
        assertTrue(PerfReport.format(capped, List.of(), 0).contains("FPS cap: 120"));
    }

    private static List<Long> framesOf(int count, long nanos) {
        List<Long> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(nanos);
        return out;
    }
}
