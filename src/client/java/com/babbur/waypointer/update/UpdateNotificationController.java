package com.babbur.waypointer.update;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class UpdateNotificationController {

    private final Supplier<CompletableFuture<Optional<AvailableUpdate>>> lookup;
    private final Consumer<Runnable> delayedExecutor;
    private final Consumer<AvailableUpdate> notifier;
    private final AtomicBoolean firstJoinHandled = new AtomicBoolean();

    UpdateNotificationController(
            Supplier<CompletableFuture<Optional<AvailableUpdate>>> lookup,
            Consumer<Runnable> delayedExecutor,
            Consumer<AvailableUpdate> notifier) {
        this.lookup = lookup;
        this.delayedExecutor = delayedExecutor;
        this.notifier = notifier;
    }

    void onJoin() {
        if (!firstJoinHandled.compareAndSet(false, true)) return;

        CompletableFuture<Optional<AvailableUpdate>> result;
        try {
            result = lookup.get();
        } catch (RuntimeException ignored) {
            firstJoinHandled.set(false);
            return;
        }
        delayedExecutor.accept(() -> result.whenComplete((update, failure) -> {
            if (failure != null) {
                firstJoinHandled.set(false);
                return;
            }
            update.ifPresent(notifier);
        }));
    }
}
