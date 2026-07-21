package com.babbur.waypointer.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLogTailTest {

    @Test
    void returnsOnlyRecentRelevantLines(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("latest.log");
        Files.writeString(log, String.join("\n",
                "[INFO] ordinary minecraft line",
                "[INFO] [Waypointer] loaded routes",
                "[WARN] renderer warning",
                "[INFO] another ordinary line",
                "[ERROR] newest failure"));

        List<String> lines = DebugLogTail.readRelevant(log, 2);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("renderer warning"));
        assertTrue(lines.get(1).contains("newest failure"));
    }

    @Test
    void missingLogIsAnEmptyCapture(@TempDir Path directory) {
        assertTrue(DebugLogTail.readRelevant(directory.resolve("missing.log"), 10).isEmpty());
    }

    @Test
    void keepsBoundedStackTraceContextAfterAnError(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("latest.log");
        Files.writeString(log, String.join("\n",
                "[ERROR] Failed to render a Waypointer path",
                "java.lang.IllegalStateException: broken renderer",
                "\tat com.example.Renderer.draw(Renderer.java:42)",
                "\tat com.example.Client.tick(Client.java:9)",
                "Caused by: java.lang.IllegalArgumentException: bad target",
                "\tat com.example.Path.find(Path.java:7)",
                "[INFO] ordinary line after the stack trace"));

        List<String> lines = DebugLogTail.readRelevant(log, 20);

        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("IllegalStateException")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Renderer.draw")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Caused by")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("ordinary line")));
    }
}
