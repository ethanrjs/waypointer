package dev.ethan.waypointer.dungeon;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonTriggerDetectorTest {

    @Test
    void worldTargetsIncludesBaseWaypointAndHighlights() throws Exception {
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                100,
                200,
                List.of(DungeonRoom.packSegment(100, 200)));
        DungeonWaypoint waypoint = new DungeonWaypoint(
                "break",
                1,
                DungeonSecretCategory.STONK,
                DungeonWaypointTrigger.BREAK_BLOCKS,
                4,
                70,
                7,
                "Break",
                List.of(DungeonHighlight.filled(5, 71, 8)));
        Method worldTargets = DungeonTriggerDetector.class.getDeclaredMethod(
                "worldTargets", DungeonRoom.class, DungeonWaypoint.class);
        worldTargets.setAccessible(true);

        Object rawTargets = worldTargets.invoke(null, room, waypoint);
        List<?> targets = (List<?>) rawTargets;

        assertEquals(List.of(
                new BlockPos(104, 70, 207),
                new BlockPos(105, 71, 208)), targets);
    }
}
