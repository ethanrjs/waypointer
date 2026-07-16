package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonUnresolvedRetryTest {

    @Test
    void failedRoomResolutionUsesExponentialTickBackoff() throws Exception {
        DungeonStateTracker tracker = trackerWithQueuedRoom();
        CountingLookup lookup = new CountingLookup();

        setDungeonTick(tracker, 0);
        tracker.retryUnresolvedAssemblies(lookup);
        int firstAttemptCalls = lookup.calls;
        assertTrue(firstAttemptCalls > 0);

        for (int tick = 1; tick < 5; tick++) {
            setDungeonTick(tracker, tick);
            tracker.retryUnresolvedAssemblies(lookup);
        }
        assertEquals(firstAttemptCalls, lookup.calls);

        setDungeonTick(tracker, 5);
        tracker.retryUnresolvedAssemblies(lookup);
        int secondAttemptCalls = lookup.calls;
        assertTrue(secondAttemptCalls > firstAttemptCalls);

        for (int tick = 6; tick < 15; tick++) {
            setDungeonTick(tracker, tick);
            tracker.retryUnresolvedAssemblies(lookup);
        }
        assertEquals(secondAttemptCalls, lookup.calls);

        setDungeonTick(tracker, 15);
        tracker.retryUnresolvedAssemblies(lookup);
        assertTrue(lookup.calls > secondAttemptCalls);
    }

    @Test
    void chunkAvailabilityRequestRetriesBeforeTheBackoffDeadline() throws Exception {
        DungeonStateTracker tracker = trackerWithQueuedRoom();
        CountingLookup lookup = new CountingLookup();

        setDungeonTick(tracker, 0);
        tracker.retryUnresolvedAssemblies(lookup);
        int firstAttemptCalls = lookup.calls;

        setDungeonTick(tracker, 1);
        tracker.retryUnresolvedAssemblies(lookup);
        assertEquals(firstAttemptCalls, lookup.calls);

        Method requestRetry = DungeonStateTracker.class.getDeclaredMethod("requestUnresolvedRetry");
        requestRetry.setAccessible(true);
        requestRetry.invoke(tracker);
        tracker.retryUnresolvedAssemblies(lookup);

        assertTrue(lookup.calls > firstAttemptCalls);
    }

    private static DungeonStateTracker trackerWithQueuedRoom() throws Exception {
        DungeonStateTracker tracker = new DungeonStateTracker(new ActiveGroupManager(), new DungeonConfig());
        DungeonRoomDefinition definition = new DungeonRoomDefinition(
                "retry-test",
                "Retry Test",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(),
                List.of());
        Method attach = DungeonStateTracker.class.getDeclaredMethod(
                "attachScannedSegment",
                DungeonRoomDefinition.class,
                long.class,
                int.class);
        attach.setAccessible(true);
        Object assembly = attach.invoke(tracker, definition, DungeonRoom.packSegment(0, 0), 70);

        Method queue = DungeonStateTracker.class.getDeclaredMethod(
                "queueAssemblyForResolution",
                assembly.getClass());
        queue.setAccessible(true);
        queue.invoke(tracker, assembly);
        return tracker;
    }

    private static void setDungeonTick(DungeonStateTracker tracker, long tick) throws Exception {
        Field field = DungeonStateTracker.class.getDeclaredField("dungeonTick");
        field.setAccessible(true);
        field.setLong(tracker, tick);
    }

    private static final class CountingLookup implements DungeonRoomData.BlockLookup {
        private int calls;

        @Override
        public String blockIdAt(int x, int y, int z) {
            calls++;
            return "minecraft:air";
        }
    }
}
