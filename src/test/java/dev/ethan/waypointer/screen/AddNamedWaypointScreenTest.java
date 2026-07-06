package dev.ethan.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AddNamedWaypointScreenTest {

    @Test
    void sanitizeWaypointNameTrimsUsableNames() {
        assertEquals("Secret Lever", AddNamedWaypointScreen.sanitizeWaypointName("  Secret Lever  "));
    }

    @Test
    void sanitizeWaypointNameRejectsBlankNames() {
        assertNull(AddNamedWaypointScreen.sanitizeWaypointName(null));
        assertNull(AddNamedWaypointScreen.sanitizeWaypointName(""));
        assertNull(AddNamedWaypointScreen.sanitizeWaypointName("   "));
    }

    @Test
    void sanitizeWaypointNameCapsLongNamesAfterTrimming() {
        String capped = AddNamedWaypointScreen.sanitizeWaypointName("  " + "A".repeat(80) + "  ");

        assertEquals(64, capped.length());
        assertEquals("A".repeat(64), capped);
    }
}
