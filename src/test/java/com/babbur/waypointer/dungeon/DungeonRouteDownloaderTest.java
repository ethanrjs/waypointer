package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRouteDownloaderTest {

    @Test
    void downloadsOnlyThePinnedAttributedFlameOfWarPack() {
        assertEquals("FlameOfWar", DungeonRouteDownloader.PACK_NAME);
        assertEquals("https://raw.githubusercontent.com/yourboykyle/SecretRoutes/"
                        + "a6a88c831206df5c16ec49d416c9d7e6b5a533e0/fowroutes.json",
                DungeonRouteDownloader.SOURCE_URL);
        assertEquals("17d150a9e070642cbb6b34b3f2a88516faa7632340bf27d10193a3ee67e28750",
                DungeonRouteDownloader.SHA256);
        assertTrue(DungeonRouteDownloader.ATTRIBUTION.contains("FlameOfWar"));
        assertTrue(DungeonRouteDownloader.ATTRIBUTION.contains("(GPL-3.0)"));
    }

    @Test
    void downloadedRoutesExcludeRoomsWithoutSecretsAndKeepVariants() {
        var result = DungeonRouteDownloader.parseDownloadedRoutes("""
                {
                  "Arrow-Trap-1":[{"locations":[[1,69,1]],"secret":{"type":"bat","location":[2,70,2]}}],
                  "Arrow-Trap-1:1":[{"secret":{"type":"item","location":[3,70,3]}}],
                  "Creeper-Room":[{"secret":{"type":"exit","location":[4,70,4]}}],
                  "Entrance Room":[{"secret":{"type":"interact","location":[5,70,5]}}],
                  "Unmatched-Room":[{"secret":{"type":"interact","location":[6,70,6]}}]
                }
                """);
        assertEquals(2, result.groups().size());
        assertEquals(1, DungeonRoomRouteLibrary.secretRoomCount(result.groups()));
        assertTrue(result.groups().stream().allMatch(route -> route.zoneId().equals("arrow-trap")));
        assertEquals(3, result.waypointCount());
        assertEquals(List.of("Unmatched-Room"), result.unmatchedRooms());
    }

    @Test
    void rejectsChangedOrTruncatedDataBeforeImporting() {
        byte[] changed = "{\"Arrow-Trap-1\": []}".getBytes(StandardCharsets.UTF_8);
        IOException failure = assertThrows(IOException.class,
                () -> DungeonRouteDownloader.readVerifiedPayload(new ByteArrayInputStream(changed)));
        assertEquals("route download failed integrity verification", failure.getMessage());
    }

    @Test
    void rejectsOversizedResponsesBeforeHashingOrParsing() {
        byte[] oversized = new byte[DungeonRouteDownloader.MAX_DOWNLOAD_BYTES + 1];
        IOException failure = assertThrows(IOException.class,
                () -> DungeonRouteDownloader.readVerifiedPayload(new ByteArrayInputStream(oversized)));
        assertTrue(failure.getMessage().contains("exceeds"));
    }
}
