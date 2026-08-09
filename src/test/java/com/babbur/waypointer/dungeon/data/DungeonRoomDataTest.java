package com.babbur.waypointer.dungeon.data;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
    void rejectsDuplicateNormalizedIds() {
        assertThrows(IllegalArgumentException.class, () -> DungeonRoomData.parseEntries("""
                {"rooms":[{"id":"Same Room"},{"id":"same-room"}]}
                """));
    }
}
