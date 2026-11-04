package com.babbur.waypointer.dungeon.data;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomDataTest {

    @Test
    void bundledCatalogContainsIdentityMetadataWithoutRoutes() {
        DungeonRoomCatalogEntry altar = DungeonRoomData.entry("altar");

        assertNotNull(altar);
        assertEquals("Altar", altar.displayName());
        assertTrue(DungeonRoomData.allEntries().size() > 100);
    }

    @Test
    void parsesAndNormalizesCatalogEntries() {
        Map<String, DungeonRoomCatalogEntry> entries = DungeonRoomData.parseEntries("""
                {"rooms":[{"id":"Test Room","name":"Test","type":"ROOM","shape":"ONE_BY_ONE",
                "coreHashes":[12],"secrets":3,"crypts":1,"trappedChests":0}]}
                """);

        DungeonRoomCatalogEntry entry = entries.get("test-room");
        assertNotNull(entry);
        assertEquals(3, entry.secretCount());
        assertEquals(1, entry.cryptCount());
        assertEquals(0, entry.trappedChestCount());
    }

    @Test
    void bundledZeroSecretCountsDoNotChangeMissingMetadataSemantics() {
        Set<String> knownZeroRooms = Set.of(
                "admin", "blood", "boulder", "creeper-beams", "default", "dragon",
                "entrance", "fairy", "hall", "ice-fill", "ice-path", "king-midas",
                "quiz", "shadow-assassin", "teleport-maze", "three-weirdos", "tomioka", "water-board");
        assertEquals(knownZeroRooms, DungeonRoomData.allEntries().stream()
                .filter(room -> room.secretCount() == 0)
                .map(DungeonRoomCatalogEntry::id)
                .collect(Collectors.toSet()));

        DungeonRoomCatalogEntry unknown = DungeonRoomData.parseEntries(
                "{\"rooms\":[{\"id\":\"new-room\"}]}").get("new-room");
        assertNotNull(unknown);
        assertEquals(DungeonRoomCatalogEntry.UNKNOWN_COUNT, unknown.secretCount());
        assertEquals(DungeonRoomCatalogEntry.UNKNOWN_COUNT, unknown.cryptCount());
        assertEquals(DungeonRoomCatalogEntry.UNKNOWN_COUNT, unknown.trappedChestCount());
    }

    @Test
    void rejectsDuplicateNormalizedIds() {
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomData.parseEntries("""
                {"rooms":[{"id":"Same Room"},{"id":"same-room"}]}
                """));
    }
}
