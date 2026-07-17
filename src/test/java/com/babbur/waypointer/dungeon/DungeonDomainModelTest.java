package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonDomainModelTest {

    @Test
    void roomDefaultsNullDirectionAndCopiesSegments() {
        List<Long> segments = new ArrayList<>();
        segments.add(DungeonRoom.packSegment(-8, 24));

        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                null,
                -8,
                24,
                segments);
        segments.add(DungeonRoom.packSegment(24, 24));

        assertEquals(Direction.NW, room.direction());
        assertEquals(List.of(DungeonRoom.packSegment(-8, 24)), room.segments());
        assertThrows(UnsupportedOperationException.class,
                () -> room.segments().add(DungeonRoom.packSegment(56, 24)));
    }

    @Test
    void packSegmentRoundTripsNegativeAndPositiveCoordinates() {
        long packed = DungeonRoom.packSegment(-40, 88);

        assertEquals(-40, DungeonRoom.segmentX(packed));
        assertEquals(88, DungeonRoom.segmentZ(packed));
    }

    @Test
    void identityKeyIgnoresSegmentCoordinatesButTracksCount() {
        DungeonRoom oneSegment = new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.SE,
                100,
                200,
                List.of(DungeonRoom.packSegment(100, 200)));
        DungeonRoom twoSegments = new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.SE,
                100,
                200,
                List.of(
                        DungeonRoom.packSegment(100, 200),
                        DungeonRoom.packSegment(132, 200)));

        assertEquals("PUZZLE:ONE_BY_ONE:SE:100,200:n=1", oneSegment.identityKey());
        assertEquals("PUZZLE:ONE_BY_ONE:SE:100,200:n=2", twoSegments.identityKey());
    }

    @Test
    void displayNameUsesShapeForGenericRoomsAndTypeForSpecialRooms() {
        DungeonRoom genericRoom = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_TWO,
                Direction.NW,
                0,
                0,
                List.of());
        DungeonRoom puzzleRoom = new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of());

        assertEquals("1x2 Room", genericRoom.displayName());
        assertEquals("Puzzle Room", puzzleRoom.displayName());
    }

    @Test
    void roomDefinitionMetadataOverridesFallbackDisplayName() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_TWO,
                Direction.NW,
                0,
                0,
                List.of()).withDefinition("lava-ravine", "Lava Ravine");

        assertTrue(room.hasRoomId());
        assertEquals("lava-ravine", room.roomId());
        assertEquals("Lava Ravine", room.displayName());
    }

    @Test
    void waypointDefaultsNullFieldsAndCopiesHighlights() {
        List<DungeonHighlight> highlights = new ArrayList<>();
        highlights.add(DungeonHighlight.outline(1, 70, 1));

        DungeonWaypoint waypoint = new DungeonWaypoint(
                "secret-1",
                1,
                null,
                10,
                65,
                20,
                null,
                highlights);
        highlights.add(DungeonHighlight.filled(2, 70, 2));

        assertEquals(DungeonSecretCategory.DEFAULT, waypoint.category());
        assertEquals(DungeonWaypointTrigger.MANUAL, waypoint.trigger());
        assertEquals("", waypoint.name());
        assertFalse(waypoint.hasName());
        assertTrue(waypoint.hasHighlights());
        assertEquals(List.of(DungeonHighlight.outline(1, 70, 1)), waypoint.highlights());
        assertThrows(UnsupportedOperationException.class,
                () -> waypoint.highlights().add(DungeonHighlight.outline(3, 70, 3)));
    }

    @Test
    void plainWaypointHasNoHighlightsAndUsesCategoryColor() {
        DungeonWaypoint waypoint = DungeonWaypoint.plain(
                "wither-1",
                DungeonSecretCategory.WITHER,
                1,
                70,
                2,
                "Wither essence");

        assertFalse(waypoint.hasHighlights());
        assertTrue(waypoint.hasName());
        assertEquals(1, waypoint.secretIndex());
        assertEquals(DungeonSecretCategory.WITHER.defaultColor, waypoint.color());
    }

    @Test
    void nearestEntityTriggerUsesDistanceAndTriggerType() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -8,
                24,
                List.of(DungeonRoom.packSegment(-8, 24)));
        DungeonWaypoint first = entityWaypoint("first", DungeonWaypointTrigger.PICKUP_ITEM, 1);
        DungeonWaypoint second = entityWaypoint("second", DungeonWaypointTrigger.PICKUP_ITEM, 10);
        DungeonWaypoint wrongTrigger = entityWaypoint("bat", DungeonWaypointTrigger.KILL_BAT, 10);
        DungeonWaypoint support = entityWaypoint("support", DungeonWaypointTrigger.PICKUP_ITEM, 0, 10);

        DungeonWaypoint selected = DungeonTriggerSelection.nearestEntityTrigger(
                room,
                List.of(first, support, second, wrongTrigger),
                DungeonWaypointTrigger.PICKUP_ITEM,
                2.6,
                70.5,
                25.5,
                200.0);

        assertEquals(second, selected);
        assertNull(DungeonTriggerSelection.nearestEntityTrigger(
                room,
                List.of(first, second),
                DungeonWaypointTrigger.PICKUP_ITEM,
                100.0,
                70.5,
                100.0,
                4.0));
    }

    @Test
    void superboomTriggerRequiresExactItemName() {
        assertTrue(DungeonTriggerSelection.itemNameMatchesSuperboom("Superboom TNT"));
        assertTrue(DungeonTriggerSelection.itemNameMatchesSuperboom("\u00A7aSuperboom TNT"));
        assertFalse(DungeonTriggerSelection.itemNameMatchesSuperboom("Not Superboom TNT"));
        assertFalse(DungeonTriggerSelection.itemNameMatchesSuperboom("Superboom Fragment"));
    }

    @Test
    void chatTriggerMatchesWholeAuthoredPhraseOnly() {
        DungeonWaypoint named = new DungeonWaypoint(
                "chat-secret",
                1,
                DungeonSecretCategory.DEFAULT,
                DungeonWaypointTrigger.CHAT_MESSAGE,
                1,
                70,
                1,
                "Secret Found",
                List.of());
        DungeonWaypoint unnamedDefault = new DungeonWaypoint(
                "chat-default",
                2,
                DungeonSecretCategory.DEFAULT,
                DungeonWaypointTrigger.CHAT_MESSAGE,
                1,
                70,
                1,
                "",
                List.of());
        DungeonWaypoint unnamedCategory = new DungeonWaypoint(
                "chat-wither",
                3,
                DungeonSecretCategory.WITHER,
                DungeonWaypointTrigger.CHAT_MESSAGE,
                1,
                70,
                1,
                "",
                List.of());

        assertTrue(DungeonTriggerSelection.chatMessageMatchesWaypoint(
                "Party > Secret Found!", named));
        assertFalse(DungeonTriggerSelection.chatMessageMatchesWaypoint(
                "Party > secret founded by someone else", named));
        assertFalse(DungeonTriggerSelection.chatMessageMatchesWaypoint(
                "The default setting changed.", unnamedDefault));
        assertTrue(DungeonTriggerSelection.chatMessageMatchesWaypoint(
                "You found wither essence!", unnamedCategory));
    }

    @Test
    void waypointDefaultTriggerFollowsCategory() {
        assertEquals(DungeonWaypointTrigger.OPEN_CHEST,
                DungeonWaypoint.defaultTrigger(DungeonSecretCategory.CHEST));
        assertEquals(DungeonWaypointTrigger.FLIP_LEVER,
                DungeonWaypoint.defaultTrigger(DungeonSecretCategory.LEVER));
        assertEquals(DungeonWaypointTrigger.USE_SUPERBOOM,
                DungeonWaypoint.defaultTrigger(DungeonSecretCategory.SUPERBOOM));
        assertEquals(DungeonWaypointTrigger.BREAK_BLOCKS,
                DungeonWaypoint.defaultTrigger(DungeonSecretCategory.DUNGEONBREAKER));
        assertEquals(DungeonWaypointTrigger.KILL_BAT,
                DungeonWaypoint.defaultTrigger(DungeonSecretCategory.BAT));
    }

    @Test
    void highlightDefaultsNullStyleAndHelperMethodsInheritParentColor() {
        DungeonHighlight explicitNullStyle = new DungeonHighlight(1, 2, 3, null, 0x123456);
        DungeonHighlight outline = DungeonHighlight.outline(4, 5, 6);
        DungeonHighlight filled = DungeonHighlight.filled(7, 8, 9);

        assertEquals(DungeonHighlightStyle.OUTLINE, explicitNullStyle.style());
        assertTrue(explicitNullStyle.hasOwnColor());
        assertEquals(DungeonHighlightStyle.OUTLINE, outline.style());
        assertEquals(DungeonHighlightStyle.FILLED, filled.style());
        assertFalse(outline.hasOwnColor());
        assertFalse(filled.hasOwnColor());
    }

    @Test
    void secretCategoryLookupIsCaseInsensitiveAndFallsBackToDefault() {
        assertEquals(DungeonSecretCategory.CHEST, DungeonSecretCategory.fromId(" chest "));
        assertEquals(DungeonSecretCategory.FAIRYSOUL, DungeonSecretCategory.fromId("FAIRYSOUL"));
        assertEquals(DungeonSecretCategory.DEFAULT, DungeonSecretCategory.fromId("not-real"));
        assertEquals(DungeonSecretCategory.DEFAULT, DungeonSecretCategory.fromId(""));
        assertEquals(DungeonSecretCategory.DEFAULT, DungeonSecretCategory.fromId(null));
    }

    @Test
    void roomTypeLookupReturnsNullForUnknownMapColors() {
        assertEquals(DungeonRoomType.ENTRANCE,
                DungeonRoomType.fromMapColor(DungeonRoomType.ENTRANCE.packedColor));
        assertEquals(DungeonRoomType.BLOOD,
                DungeonRoomType.fromMapColor(DungeonRoomType.BLOOD.packedColor));
        assertNull(DungeonRoomType.fromMapColor((byte) 0));
    }

    private static DungeonWaypoint entityWaypoint(
            String id,
            DungeonWaypointTrigger trigger,
            int x) {
        return entityWaypoint(id, trigger, x, x);
    }

    private static DungeonWaypoint entityWaypoint(
            String id,
            DungeonWaypointTrigger trigger,
            int secretIndex,
            int x) {
        return new DungeonWaypoint(
                id,
                secretIndex,
                DungeonSecretCategory.ITEM,
                trigger,
                x,
                70,
                1,
                id,
                List.of());
    }
}
