package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.dungeon.DungeonHighlight;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.DungeonWaypointTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomShareCodecTest {

    @AfterEach
    void clearRuntimeData() {
        DungeonRoomData.clearAllCustom();
    }

    @Test
    void roundTripsRoomLocalDefinitionsWithTriggersAndHighlights() {
        DungeonRoomDefinition definition = definition("crypt-a", "Crypt A",
                new DungeonWaypoint(
                        "crypt-a:1",
                        1,
                        DungeonSecretCategory.DUNGEONBREAKER,
                        DungeonWaypointTrigger.DUNGEONBREAKER,
                        7, 68, 9,
                        "Break tunnel",
                        List.of(DungeonHighlight.outline(8, 68, 9))));

        String payload = DungeonRoomShareCodec.encode(List.of(definition));
        DungeonRoomShareCodec.Decoded decoded =
                DungeonRoomShareCodec.decode("```text\n" + payload + "\n```");

        assertTrue(payload.startsWith(DungeonRoomShareCodec.MAGIC));
        assertTrue(payload.startsWith(DungeonRoomShareCodec.MAGIC + "."),
                "new dungeon exports should use the compact chat-safe body");
        assertEquals(1, decoded.definitions().size());
        assertEquals(1, decoded.waypointCount());
        assertEquals(definition, decoded.definitions().get(0));
    }

    @Test
    void importCustomDefinitionsPreservesExistingAuthoredRouteById() {
        DungeonRoomDefinition existing = definition("replace-me", "Old",
                DungeonWaypoint.plain("old", DungeonSecretCategory.CHEST, 1, 70, 1, "Old"));
        DungeonRoomData.importCustomDefinitions(List.of(existing));
        DungeonRoomDefinition unrelated = definition("keep-me", "Keep",
                DungeonWaypoint.plain("keep", DungeonSecretCategory.CHEST, 3, 72, 3, "Keep"));
        DungeonRoomData.importCustomDefinitions(List.of(unrelated));

        DungeonRoomDefinition replacement = definition("replace-me", "New",
                DungeonWaypoint.plain("new", DungeonSecretCategory.LEVER, 2, 71, 2, "New"));
        int imported = DungeonRoomData.importCustomDefinitions(List.of(replacement));

        assertEquals(0, imported);
        assertEquals(existing, DungeonRoomData.customDefinition("replace-me"));
        assertEquals(unrelated, DungeonRoomData.customDefinition("keep-me"));
    }

    @Test
    void rejectsPayloadWithoutSecretRoutes() {
        DungeonRoomDefinition empty = new DungeonRoomDefinition(
                "empty",
                "Empty",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of());

        assertThrows(IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.encode(List.of(empty)));
    }

    @Test
    void decodeRejectsDuplicateNormalizedRoomIdsBeforeOverwrite() {
        DungeonRoomDefinition first = definition(
                "Room A", "First",
                DungeonWaypoint.plain(
                        "first", DungeonSecretCategory.CHEST, 1, 70, 1, "First"));
        DungeonRoomDefinition second = definition(
                "room-a", "Second",
                DungeonWaypoint.plain(
                        "second", DungeonSecretCategory.CHEST, 2, 70, 2, "Second"));
        String payload = DungeonRoomShareCodec.encode(List.of(first, second));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DungeonRoomShareCodec.decode(payload));

        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(error.getCause().getMessage().contains("Duplicate dungeon room id"));
    }

    @Test
    void decodesLegacyBase64GzipPayloads() throws Exception {
        DungeonRoomDefinition definition = definition("legacy", "Legacy",
                DungeonWaypoint.plain("legacy:1", DungeonSecretCategory.CHEST, 2, 70, 3, "Chest"));
        String json = DungeonRoomData.toJson(List.of(definition));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        String legacy = DungeonRoomShareCodec.MAGIC
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());

        DungeonRoomShareCodec.Decoded decoded = DungeonRoomShareCodec.decode(legacy);

        assertEquals(List.of(definition), decoded.definitions());
        assertTrue(DungeonRoomShareCodec.encode(List.of(definition)).length() < legacy.length(),
                "the compact form should be shorter than legacy Base64+GZIP for a normal room route");
    }

    private static DungeonRoomDefinition definition(String id, String name, DungeonWaypoint waypoint) {
        return new DungeonRoomDefinition(
                id,
                name,
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(waypoint));
    }
}
