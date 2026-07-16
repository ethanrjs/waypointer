package dev.ethan.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void communityRouteSourceIsCommitPinnedAndDigestChecked() {
        assertEquals(40, DungeonRouteDownloader.ROUTES_COMMIT.length());
        assertEquals(64, DungeonRouteDownloader.ROUTES_SHA256.length());
        assertTrue(DungeonRouteDownloader.ROUTES_COMMIT.matches("[0-9a-f]{40}"));
        assertTrue(DungeonRouteDownloader.ROUTES_SHA256.matches("[0-9a-f]{64}"));

        byte[] knownPayload = "known routes".getBytes(StandardCharsets.UTF_8);
        assertEquals("64daeb61983c42a849f34951dfd8cb8176df3531aa9f70e2a41905b8a1004935",
                DungeonRouteDownloader.sha256Hex(knownPayload));
        assertNotEquals(DungeonRouteDownloader.ROUTES_SHA256,
                DungeonRouteDownloader.sha256Hex(knownPayload));
    }
}
