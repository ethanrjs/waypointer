package com.babbur.waypointer;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerClientTest {

    @TempDir
    Path tempDir;

    @Test
    void staleDisconnectOnlyResetsWhenConnectionClearedOrStillCurrent() {
        Object disconnected = new Object();
        Object current = new Object();

        assertTrue(WaypointerClient.shouldResetAfterDisconnect(disconnected, null));
        assertTrue(WaypointerClient.shouldResetAfterDisconnect(disconnected, disconnected));
        assertFalse(WaypointerClient.shouldResetAfterDisconnect(disconnected, current));
    }

    @Test
    void recoversValidLegacyPerformanceBackupAndArchivesIt() throws IOException {
        Path backup = tempDir.resolve("perf-test-backup.wpc");
        Path recovery = tempDir.resolve("perf-test-backup.wpc.recovery");
        WaypointerConfig saved = new WaypointerConfig();
        saved.setMaxWaypointLabels(7);
        saved.setShowWaypointNames(false);
        Files.writeString(backup, WaypointerConfigCodec.encode(saved));

        WaypointerConfig target = new WaypointerConfig();
        WaypointerClient.recoverLegacyPerfTestBackup(target, backup);

        assertEquals(7, target.maxWaypointLabels());
        assertFalse(target.showWaypointNames());
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(recovery));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("perf-test-backup.wpc.recovery.recovered-")));
        }
    }

    @Test
    void retainsInvalidLegacyPerformanceBackupForManualRecovery() throws IOException {
        Path backup = tempDir.resolve("perf-test-backup.wpc");
        Path recovery = tempDir.resolve("perf-test-backup.wpc.recovery");
        Files.writeString(backup, "not-a-waypointer-config");

        WaypointerConfig target = new WaypointerConfig();
        WaypointerClient.recoverLegacyPerfTestBackup(target, backup);

        assertEquals(32, target.maxWaypointLabels());
        assertFalse(Files.exists(backup));
        assertTrue(Files.exists(recovery));
    }
}
