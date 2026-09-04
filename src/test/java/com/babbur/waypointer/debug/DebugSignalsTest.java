package com.babbur.waypointer.debug;

import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugSignalsTest {

    @Test
    void dungeonRoomLineShowsMapFallbackAsLowerConfidence() {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                0,
                0,
                List.of(DungeonRoom.packSegment(0, 0)));

        String line = DebugSignals.dungeonRoomLine(true, room, null);

        assertTrue(line.contains("room=1x1 Room"));
        assertTrue(line.contains("id=<unmatched>"));
        assertTrue(line.contains("confidence=map fallback (lower confidence)"));
    }

    @Test
    void crystalHollowsLineIncludesLobbyAndCompassState() {
        String line = DebugSignals.crystalHollowsLine(
                true, "mini123A", 17, 4, "NEED_SECOND_USE", 1, "ray_captured");

        assertTrue(line.contains("active=true"));
        assertTrue(line.contains("serverId=mini123A"));
        assertTrue(line.contains("day=17"));
        assertTrue(line.contains("sightings=4"));
        assertTrue(line.contains("compass=NEED_SECOND_USE"));
        assertTrue(line.contains("rays=1"));
        assertTrue(line.contains("lastEvent=ray_captured"));
    }

    @Test
    void crystalHollowsLineLabelsUnknownIdentityAndDay() {
        String line = DebugSignals.crystalHollowsLine(
                false, null, -1, 0, "not installed", 0, null);

        assertTrue(line.contains("serverId=(none)"));
        assertTrue(line.contains("day=unknown"));
        assertTrue(line.contains("lastEvent=(none)"));
    }
}
