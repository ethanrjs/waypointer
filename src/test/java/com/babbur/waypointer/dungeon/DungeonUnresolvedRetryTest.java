package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonUnresolvedRetryTest {

    @Test
    void failedRoomResolutionUsesExponentialTickBackoff() {
        DungeonRoomResolver resolver = resolverWithQueuedRoom();
        CountingLookup lookup = new CountingLookup();

        resolver.retry(lookup, 0);
        int firstAttemptCalls = lookup.calls;
        assertTrue(firstAttemptCalls > 0);

        for (int tick = 1; tick < 5; tick++) {
            resolver.retry(lookup, tick);
        }
        assertEquals(firstAttemptCalls, lookup.calls);

        resolver.retry(lookup, 5);
        int secondAttemptCalls = lookup.calls;
        assertTrue(secondAttemptCalls > firstAttemptCalls);

        for (int tick = 6; tick < 15; tick++) {
            resolver.retry(lookup, tick);
        }
        assertEquals(secondAttemptCalls, lookup.calls);

        resolver.retry(lookup, 15);
        assertTrue(lookup.calls > secondAttemptCalls);
    }

    @Test
    void chunkAvailabilityRequestRetriesBeforeTheBackoffDeadline() {
        DungeonRoomResolver resolver = resolverWithQueuedRoom();
        CountingLookup lookup = new CountingLookup();

        resolver.retry(lookup, 0);
        int firstAttemptCalls = lookup.calls;

        resolver.retry(lookup, 1);
        assertEquals(firstAttemptCalls, lookup.calls);

        resolver.requestRetry();
        resolver.retry(lookup, 1);

        assertTrue(lookup.calls > firstAttemptCalls);
    }

    private static DungeonRoomResolver resolverWithQueuedRoom() {
        DungeonRoomResolver resolver = new DungeonRoomResolver();
        DungeonRoomCatalogEntry definition = new DungeonRoomCatalogEntry(
                "retry-test",
                "Retry Test",
                DungeonRoomType.ROOM,
                DungeonRoomShape.ONE_BY_ONE,
                List.of(),
                List.of(),
                -1,
                -1,
                -1);
        DungeonRoomResolver.RoomAssembly assembly = resolver.attachScannedSegment(
                definition,
                DungeonRoom.packSegment(0, 0),
                70);
        resolver.queueAssemblyForResolution(assembly);
        return resolver;
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
