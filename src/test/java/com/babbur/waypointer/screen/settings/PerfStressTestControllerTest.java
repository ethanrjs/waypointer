package com.babbur.waypointer.screen.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfStressTestControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void staleOrInvalidFramesDoNotConsumeTheActiveTimeBudget() {
        assertEquals(0, PerfStressTestController.sanitizeActiveDelta(-1));
        assertEquals(0, PerfStressTestController.sanitizeActiveDelta(0));
        assertEquals(500_000_000L,
                PerfStressTestController.sanitizeActiveDelta(500_000_000L));
        assertEquals(0,
                PerfStressTestController.sanitizeActiveDelta(500_000_001L));
    }

    @Test
    void adaptiveRampDoublesDensityAndStopsAtTheSafetyCeiling() {
        assertEquals(15, PerfStressTestController.nextAdaptiveChildren(7));
        assertEquals(31, PerfStressTestController.nextAdaptiveChildren(15));
        assertEquals(63, PerfStressTestController.nextAdaptiveChildren(31));
        assertEquals(127, PerfStressTestController.nextAdaptiveChildren(63));
        assertEquals(127, PerfStressTestController.nextAdaptiveChildren(127));
    }

    @Test
    void activeBudgetFinishesAtExactlySixtySecondsAndExcludesStaleTime() {
        PerfStressTestController.ActiveBudget budget =
                new PerfStressTestController.ActiveBudget(60_000_000_000L);

        budget.accept(59_999_999_999L); // stale as one frame, intentionally ignored
        assertEquals(0, budget.activeNanos());

        for (int i = 0; i < 119; i++) budget.accept(500_000_000L);
        budget.accept(499_999_999L);
        assertEquals(59_999_999_999L, budget.activeNanos());
        assertFalse(budget.finished());

        budget.accept(1L);
        assertEquals(60_000_000_000L, budget.activeNanos());
        assertTrue(budget.finished());
        assertEquals(0, budget.remainingNanos());
    }

    @Test
    void lagCutoffRequiresThreeConsecutiveValidQuarterSecondFrames() {
        int streak = 0;
        streak = PerfStressTestController.nextLagStreak(streak, 250_000_000L);
        streak = PerfStressTestController.nextLagStreak(streak, 250_000_000L);
        assertFalse(PerfStressTestController.sustainedLag(streak));

        streak = PerfStressTestController.nextLagStreak(streak, 16_000_000L);
        assertEquals(0, streak, "a healthy frame resets the cutoff streak");
        streak = PerfStressTestController.nextLagStreak(streak, 500_000_001L);
        assertEquals(1, streak, "a very slow render frame must not evade the lag guard");
        streak = PerfStressTestController.nextLagStreak(streak, 16_000_000L);
        assertEquals(0, streak);

        for (int i = 0; i < 3; i++) {
            streak = PerfStressTestController.nextLagStreak(streak, 250_000_000L);
        }
        assertTrue(PerfStressTestController.sustainedLag(streak));
        assertFalse(PerfStressTestController.hardStall(999_999_999L));
        assertTrue(PerfStressTestController.hardStall(1_000_000_000L));
    }

    @Test
    void backupReplacementIsAtomicAndQuarantineRemovesTheActivePath() throws IOException {
        Path backup = tempDir.resolve("perf-test-backup.wpc");
        Files.writeString(backup, "old");

        PerfStressTestController.writeBackupAtomically(backup, "new");

        assertEquals("new", Files.readString(backup));
        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }

        Path quarantined = PerfStressTestController.quarantineBackup(backup);

        assertFalse(Files.exists(backup));
        assertEquals("new", Files.readString(quarantined));
    }

    @Test
    void interruptedRecoveryResumesFromItsDeterministicClaimPath() throws IOException {
        Path backup = tempDir.resolve("perf-test-backup.wpc");
        Path recovery = tempDir.resolve("perf-test-backup.wpc.recovery");
        Files.writeString(backup, "snapshot");

        assertEquals(
                recovery,
                PerfStressTestController.claimRecoveryBackup(backup, recovery));
        assertFalse(Files.exists(backup));
        assertEquals("snapshot", Files.readString(recovery));

        assertEquals(
                recovery,
                PerfStressTestController.claimRecoveryBackup(backup, recovery));
    }
}
