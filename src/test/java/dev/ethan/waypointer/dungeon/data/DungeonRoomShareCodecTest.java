package dev.ethan.waypointer.dungeon.data;

import dev.ethan.waypointer.dungeon.DungeonHighlight;
import dev.ethan.waypointer.dungeon.DungeonRoomShape;
import dev.ethan.waypointer.dungeon.DungeonRoomType;
import dev.ethan.waypointer.dungeon.DungeonSecretCategory;
import dev.ethan.waypointer.dungeon.DungeonWaypoint;
import dev.ethan.waypointer.dungeon.DungeonWaypointTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
