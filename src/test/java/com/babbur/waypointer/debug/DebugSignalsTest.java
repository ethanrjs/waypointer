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
}
