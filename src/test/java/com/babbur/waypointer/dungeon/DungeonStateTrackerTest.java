package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DungeonStateTrackerTest {

    @Test
    void rejectsNullListeners() {
        DungeonStateTracker tracker = tracker();

        assertThrows(NullPointerException.class, () -> tracker.addRoomListener(null));
    }

    @Test
    void dispatchUsesSnapshotAndContinuesAfterListenerFailure() {
        DungeonStateTracker tracker = tracker();
        AtomicInteger laterCalls = new AtomicInteger();
        AtomicInteger addedDuringDispatchCalls = new AtomicInteger();
        tracker.addRoomListener(room -> {
            tracker.addRoomListener(ignored -> addedDuringDispatchCalls.incrementAndGet());
            throw new IllegalStateException("broken listener");
        });
        tracker.addRoomListener(room -> laterCalls.incrementAndGet());

        tracker.setCurrentRoom(room(0));

        assertEquals(1, laterCalls.get());
        assertEquals(0, addedDuringDispatchCalls.get());

        tracker.setCurrentRoom(room(32));

        assertEquals(2, laterCalls.get());
        assertEquals(1, addedDuringDispatchCalls.get());
    }

    private static DungeonStateTracker tracker() {
        return new DungeonStateTracker(new ActiveGroupManager(), new DungeonConfig());
    }

    private static DungeonRoom room(int x) {
        return new DungeonRoom(
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                x,
                0,
                List.of(DungeonRoom.packSegment(x, 0)));
    }
}
