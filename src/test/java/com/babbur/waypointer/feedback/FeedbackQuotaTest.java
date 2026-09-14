package com.babbur.waypointer.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackQuotaTest {
    @Test void countsSendsPerUtcDayAndPersistsAcrossInstances(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("feedback-quota.json");
        var today = new AtomicReference<>(LocalDate.of(2026, 9, 11));
        var quota = new FeedbackQuota(file, today::get);
        assertEquals(FeedbackQuota.DAILY_LIMIT, quota.remainingToday());
        assertFalse(quota.exhausted());
        for (int i = 0; i < FeedbackQuota.DAILY_LIMIT; i++) quota.recordSend();
        assertEquals(0, quota.remainingToday());
        assertTrue(quota.exhausted());
        assertTrue(Files.readString(file).contains("\"day\":\"2026-09-11\""));

        var reloaded = new FeedbackQuota(file, today::get);
        assertEquals(FeedbackQuota.DAILY_LIMIT, reloaded.sentToday());
        assertTrue(reloaded.exhausted());

        today.set(LocalDate.of(2026, 9, 12));
        assertEquals(0, reloaded.sentToday());
        assertFalse(reloaded.exhausted());
        reloaded.recordSend();
        assertEquals(1, new FeedbackQuota(file, today::get).sentToday());
    }

    @Test void unreadableOrMissingFilesStartFromZero(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("feedback-quota.json");
        Files.writeString(file, "not json");
        var quota = new FeedbackQuota(file, () -> LocalDate.of(2026, 9, 11));
        assertEquals(0, quota.sentToday());
        quota.recordSend();
        assertEquals(1, new FeedbackQuota(file, () -> LocalDate.of(2026, 9, 11)).sentToday());
        assertEquals(0, new FeedbackQuota(null, () -> LocalDate.of(2026, 9, 11)).sentToday());
    }
}
