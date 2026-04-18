package dev.ethan.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneTest {

    @Test
    void resolve_returnsNullForNonSkyblock() {
        assertNull(Zone.resolve("BEDWARS", "anything", null));
        assertNull(Zone.resolve(null, "anything", null));
    }

    @Test
    void resolve_knownHubMap() {
        Zone z = Zone.resolve("SKYBLOCK", "Hub", null);
        assertNotNull(z);
        assertEquals("hub", z.id());
        assertEquals("Hub", z.displayName());
    }

    @Test
    void resolve_dungeonFloorMergesMapAndMode() {
        Zone f7 = Zone.resolve("SKYBLOCK", "dungeon", "F7");
        assertEquals("dungeon_f7", f7.id());
        assertEquals("Catacombs F7", f7.displayName());

        Zone m7 = Zone.resolve("SKYBLOCK", "dungeon", "M7");
        assertEquals("dungeon_m7", m7.id());
    }

    @Test
    void resolve_unknownMapFallsThroughToSanitizedId() {
        Zone weird = Zone.resolve("SKYBLOCK", "My Custom Island!", null);
        assertEquals("my_custom_island", weird.id());
        assertEquals("My Custom Island!", weird.displayName());
    }

    @Test
    void resolveFromDisplayName_matchesKnownZone() {
        Zone z = Zone.resolveFromDisplayName("Crystal Hollows");
        assertEquals("crystal_hollows", z.id());
    }

    @Test
    void resolveFromDisplayName_handlesUnknown() {
        Zone z = Zone.resolveFromDisplayName("Some New Area");
        assertEquals("some_new_area", z.id());
    }

    @Test
    void resolveFromDisplayName_nullOrBlank() {
        assertNull(Zone.resolveFromDisplayName(null));
        assertNull(Zone.resolveFromDisplayName("   "));
    }
}
