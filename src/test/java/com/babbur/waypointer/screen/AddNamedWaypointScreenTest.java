package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddNamedWaypointScreenTest {

    @AfterEach
    void resetRememberedDungeonType() {
        AddNamedWaypointScreen.resetRememberedSelectionForTests();
    }

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

    @Test
    void dungeonTypeSelectionIsExclusiveAndPreservesNonTypeFlags() {
        int baseFlags = Waypoint.FLAG_SKIP_ON_INTERACT
                | Waypoint.FLAG_DUNGEON_SECRET
                | Waypoint.FLAG_DUNGEON_ITEM;

        int flags = AddNamedWaypointScreen.creationFlags(baseFlags,
                AddNamedWaypointScreen.creationOptions(true, true),
                DungeonWaypointType.ETHERWARP);

        assertTrue((flags & Waypoint.FLAG_SUBWAYPOINT) != 0);
        assertTrue((flags & Waypoint.FLAG_SKIP_ON_INTERACT) != 0);
        assertEquals(Waypoint.FLAG_DUNGEON_ETHERWARP,
                flags & Waypoint.DUNGEON_METADATA_FLAGS);
        assertEquals(0, AddNamedWaypointScreen.creationFlags(baseFlags,
                        AddNamedWaypointScreen.creationOptions(false, false), null)
                & Waypoint.DUNGEON_METADATA_FLAGS);
    }

    @Test
    void clickingTheActiveDungeonTypeClearsIt() {
        assertEquals(DungeonWaypointType.BAT,
                AddNamedWaypointScreen.toggleDungeonType(null, DungeonWaypointType.BAT));
        assertEquals(null,
                AddNamedWaypointScreen.toggleDungeonType(
                        DungeonWaypointType.BAT, DungeonWaypointType.BAT));
    }

    @Test
    void dungeonTypePersistsInOneRoomAndResetsInTheNextRoom() {
        assertEquals(DungeonWaypointType.SECRET,
                AddNamedWaypointScreen.selectionForRoom(
                        "room-a", DungeonWaypointType.SECRET));
        AddNamedWaypointScreen.rememberSelection("room-a", DungeonWaypointType.PEARL);
        assertEquals(DungeonWaypointType.PEARL,
                AddNamedWaypointScreen.selectionForRoom(
                        "room-a", DungeonWaypointType.SECRET));
        assertEquals(DungeonWaypointType.BAT,
                AddNamedWaypointScreen.selectionForRoom(
                        "room-b", DungeonWaypointType.BAT));
    }

    @Test
    void dungeonRoomSelectionKeyUsesPhysicalRoomThenStoredRoomFallback() {
        WaypointGroup normal = WaypointGroup.create("Normal", "hub");
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "stored-room");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);

        assertEquals(null, AddNamedWaypointScreen.dungeonRoomSelectionKey(null, "physical"));
        assertEquals(null, AddNamedWaypointScreen.dungeonRoomSelectionKey(normal, "physical"));
        assertEquals("physical",
                AddNamedWaypointScreen.dungeonRoomSelectionKey(dungeon, "physical"));
        assertEquals("stored-room",
                AddNamedWaypointScreen.dungeonRoomSelectionKey(dungeon, ""));
        assertEquals("stored-room",
                AddNamedWaypointScreen.dungeonRoomSelectionKey(dungeon, null));
    }

    @Test
    void dungeonTypeHitTestingCoversEveryCellAndRejectsGaps() {
        int rowX = 100;
        int rowY = 50;
        assertEquals(-1, AddNamedWaypointScreen.dungeonTypeAt(
                false, rowX, rowY, rowX, rowY));
        for (int i = 0; i < DungeonWaypointType.values().length; i++) {
            int cellX = AddNamedWaypointScreen.dungeonTypeCellX(rowX, i);
            assertEquals(i, AddNamedWaypointScreen.dungeonTypeAt(
                    true, rowX, rowY, cellX + 10, rowY + 10));
            if (i < DungeonWaypointType.values().length - 1) {
                assertEquals(-1, AddNamedWaypointScreen.dungeonTypeAt(
                        true, rowX, rowY, cellX + 21, rowY + 10));
            }
        }
        assertEquals(-1, AddNamedWaypointScreen.dungeonTypeAt(
                true, rowX, rowY, rowX - 1, rowY + 10));
        assertEquals(-1, AddNamedWaypointScreen.dungeonTypeAt(
                true, rowX, rowY, rowX + 10, rowY - 1));
        assertEquals(-1, AddNamedWaypointScreen.dungeonTypeAt(
                true, rowX, rowY, rowX + 10, rowY + 20));
    }
}
