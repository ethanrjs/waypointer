package dev.ethan.waypointer.location;

import dev.ethan.waypointer.core.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatacombsFloorRefinerTest {

    @Test
    void resolvesFloorSevenFromCatacombsSidebarText() {
        Zone zone = CatacombsFloorRefiner.tryResolveFromSidebarBlob(
                "Time Elapsed: 48m 00s\nThe Catacombs (F7)\nCleared: 75%");

        assertEquals("dungeon_f7", zone.id());
        assertEquals("Catacombs F7", zone.displayName());
    }

    @Test
    void resolvesMasterSevenFromCatacombsSidebarText() {
        Zone zone = CatacombsFloorRefiner.tryResolveFromSidebarBlob("The Catacombs (M7)");

        assertEquals("dungeon_m7", zone.id());
        assertEquals("Master Mode M7", zone.displayName());
    }

    @Test
    void ignoresCatacombsTextWithoutFloorMarker() {
        assertNull(CatacombsFloorRefiner.tryResolveFromSidebarBlob("Dungeon: Catacombs"));
    }

    @Test
    void refinesGenericDungeonZoneToSidebarFloor() {
        Zone refined = CatacombsFloorRefiner.refine(
                Zone.fromId("dungeon"),
                "The Catacombs (F7)");

        assertEquals("dungeon_f7", refined.id());
    }

    @Test
    void preservesExistingFloorZoneWhenSidebarHasNoFloorMarker() {
        Zone floor = Zone.fromId("dungeon_f6");

        Zone refined = CatacombsFloorRefiner.refine(floor, "Dungeon: Catacombs");

        assertSame(floor, refined);
    }

    @Test
    void updatesExistingFloorZoneWhenSidebarShowsDifferentFloor() {
        Zone refined = CatacombsFloorRefiner.refine(
                Zone.fromId("dungeon_f6"),
                "The Catacombs (M6)");

        assertEquals("dungeon_m6", refined.id());
    }

    @Test
    void leavesDungeonHubAndUnrelatedZonesUnchanged() {
        Zone dungeonHub = Zone.fromId("dungeon_hub");
        Zone hub = Zone.fromId("hub");

        assertSame(dungeonHub, CatacombsFloorRefiner.refine(dungeonHub, "The Catacombs (F7)"));
        assertSame(hub, CatacombsFloorRefiner.refine(hub, "The Catacombs (F7)"));
    }

    @Test
    void pollEligibilityMatchesGeneratedCatacombsZonesOnly() {
        assertTrue(CatacombsFloorRefiner.shouldPoll(Zone.fromId("dungeon")));
        assertTrue(CatacombsFloorRefiner.shouldPoll(Zone.fromId("dungeon_f7")));
        assertTrue(CatacombsFloorRefiner.shouldPoll(Zone.fromId("dungeon_m7")));
        assertFalse(CatacombsFloorRefiner.shouldPoll(Zone.fromId("dungeon_hub")));
    }
}
