package dev.ethan.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DungeonRouteDownloaderTest {

    @Test
    void boundedReaderAcceptsPayloadAtLimit() throws Exception {
        byte[] payload = "route data".getBytes(StandardCharsets.UTF_8);

        assertEquals("route data", DungeonRouteDownloader.readBoundedUtf8(
                new ByteArrayInputStream(payload), payload.length));
    }

    @Test
    void boundedReaderRejectsPayloadPastLimit() {
        byte[] payload = "oversized".getBytes(StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class,
                () -> DungeonRouteDownloader.readBoundedUtf8(
                        new ByteArrayInputStream(payload), payload.length - 1));

        assertEquals("response is too large (max 8 bytes)", error.getMessage());
    }
}
