package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddNamedWaypointScreenTest {

    @Test
    void sanitizeWaypointNameTrimsUsableNames() {
        assertEquals("Secret Lever", AddNamedWaypointScreen.sanitizeWaypointName("  Secret Lever  "));
    }

    @Test
    void sanitizeWaypointNameKeepsBlankNamesAsUnnamedWaypoints() {
        assertEquals("", AddNamedWaypointScreen.sanitizeWaypointName(null));
        assertEquals("", AddNamedWaypointScreen.sanitizeWaypointName(""));
        assertEquals("", AddNamedWaypointScreen.sanitizeWaypointName("   "));
    }

    @Test
    void sanitizeWaypointNameCapsLongNamesAfterTrimming() {
        String capped = AddNamedWaypointScreen.sanitizeWaypointName("  " + "A".repeat(80) + "  ");

        assertEquals(64, capped.length());
        assertEquals("A".repeat(64), capped);
    }

    @Test
    void smallOptionRequiresSubwaypoint() {
        AddNamedWaypointScreen.CreationOptions regular =
                AddNamedWaypointScreen.creationOptions(false, true);
        AddNamedWaypointScreen.CreationOptions smallSubwaypoint =
                AddNamedWaypointScreen.creationOptions(true, true);

        assertFalse(regular.subwaypoint());
        assertFalse(regular.small());
        assertTrue(smallSubwaypoint.subwaypoint());
        assertTrue(smallSubwaypoint.small());
    }

    @Test
    void subwaypointOptionRequiresAnExistingParentWaypoint() {
        WaypointGroup empty = WaypointGroup.create("route", "hub");

        assertFalse(AddNamedWaypointScreen.canCreateSubwaypoint(empty));
        assertFalse(AddNamedWaypointScreen.creationOptions(false, true, true).subwaypoint());

        empty.add(Waypoint.at(0, 0, 0));

        assertTrue(AddNamedWaypointScreen.canCreateSubwaypoint(empty));
        assertTrue(AddNamedWaypointScreen.creationOptions(true, true, true).small());
    }

    @Test
    void creationFlagsPreserveBaseFlagsAndAddOnlyValidSubwaypointFlags() {
        int baseFlags = Waypoint.FLAG_SKIP_ON_STAND;

        assertEquals(baseFlags, AddNamedWaypointScreen.creationFlags(baseFlags,
                AddNamedWaypointScreen.creationOptions(false, true)));
        assertEquals(baseFlags | Waypoint.FLAG_SUBWAYPOINT,
                AddNamedWaypointScreen.creationFlags(baseFlags,
                        AddNamedWaypointScreen.creationOptions(true, false)));
        assertEquals(baseFlags | Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                AddNamedWaypointScreen.creationFlags(baseFlags,
                        AddNamedWaypointScreen.creationOptions(true, true)));
    }
}
