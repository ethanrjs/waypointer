package com.babbur.waypointer.update;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNotificationControllerTest {

    @Test
    void firstJoinChecksOnceAndWaitsForTheDelay() {
        AvailableUpdate update = update();
        AtomicInteger lookups = new AtomicInteger();
        List<Runnable> delayedTasks = new ArrayList<>();
        List<AvailableUpdate> notifications = new ArrayList<>();
        UpdateNotificationController controller = new UpdateNotificationController(
                () -> {
                    lookups.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.of(update));
                },
                delayedTasks::add,
                notifications::add);

        controller.onJoin();
        controller.onJoin();

        assertEquals(1, lookups.get());
        assertEquals(1, delayedTasks.size());
        assertTrue(notifications.isEmpty());

        delayedTasks.getFirst().run();
        assertEquals(List.of(update), notifications);
    }

    @Test
    void currentVersionDoesNotCreateAChatMessage() {
        List<Runnable> delayedTasks = new ArrayList<>();
        List<AvailableUpdate> notifications = new ArrayList<>();
        UpdateNotificationController controller = new UpdateNotificationController(
                () -> CompletableFuture.completedFuture(Optional.empty()),
                delayedTasks::add,
                notifications::add);

        controller.onJoin();
        delayedTasks.getFirst().run();

        assertTrue(notifications.isEmpty());
    }

    @Test
    void failedLookupCanRetryOnTheNextJoin() {
        AtomicInteger lookups = new AtomicInteger();
        List<Runnable> delayedTasks = new ArrayList<>();
        AvailableUpdate update = update();
        List<AvailableUpdate> notifications = new ArrayList<>();
        UpdateNotificationController controller = new UpdateNotificationController(
                () -> lookups.getAndIncrement() == 0
                        ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                        : CompletableFuture.completedFuture(Optional.of(update)),
                delayedTasks::add,
                notifications::add);

        controller.onJoin();
        delayedTasks.removeFirst().run();
        controller.onJoin();
        delayedTasks.removeFirst().run();

        assertEquals(2, lookups.get());
        assertEquals(List.of(update), notifications);
    }

    private static AvailableUpdate update() {
        return new AvailableUpdate(
                "1.8.7",
                Instant.parse("2026-08-10T00:00:33Z"),
                "1.9.0",
                Instant.parse("2026-12-19T12:00:00Z"),
                21);
    }
}
