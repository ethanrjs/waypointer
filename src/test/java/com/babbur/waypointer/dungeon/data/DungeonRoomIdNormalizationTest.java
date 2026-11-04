package com.babbur.waypointer.dungeon.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonRoomIdNormalizationTest {
    private static String regexNormalize(String id) {
        if (id == null) return "";
        return id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    @Test
    void matchesTheRegexDefinitionOnAwkwardIds() {
        for (String id : List.of("", " ", "-", "--", "Water Board", "water-board", "  Mines  ", "a--b", "-a-",
                "Crystal_Hollows", "dungeon_f7", "Ÿes Room", "über-room", "room  #2!", "1x1", "A.B.C", "a-b-",
                "already-normal-id", "Trailing ", "ﬁsh room", "İstanbul")) {
            assertEquals(regexNormalize(id), DungeonRoomCatalogEntry.normalizeId(id), id);
        }
        assertEquals("", DungeonRoomCatalogEntry.normalizeId(null));
    }
}
