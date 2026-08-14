package com.babbur.waypointer.i18n;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLocaleReloadStateTest {
    @Test
    void pendingReloadCannotBeScheduledTwice() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();

        assertTrue(state.begin("fr_fr:abc"));
        assertFalse(state.begin("fr_fr:abc"));
    }

    @Test
    void failedOrAbandonedReloadCanRetry() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();
        assertTrue(state.begin("fr_fr:abc"));

        state.finish("fr_fr:abc", false);

        assertFalse(state.applied("fr_fr:abc"));
        assertTrue(state.begin("fr_fr:abc"));
    }

    @Test
    void successfulReloadIsNotRequestedAgain() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();
        assertTrue(state.begin("fr_fr:abc"));

        state.finish("fr_fr:abc", true);

        assertTrue(state.applied("fr_fr:abc"));
        assertFalse(state.begin("fr_fr:abc"));
    }

    @Test
    void staleFailureCannotClearAnAppliedReload() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();
        assertTrue(state.begin("fr_fr:abc"));
        state.finish("fr_fr:abc", true);

        state.finish("fr_fr:abc", false);

        assertTrue(state.applied("fr_fr:abc"));
        assertFalse(state.begin("fr_fr:abc"));
    }

    @Test
    void changedCatalogDigestGetsItsOwnReload() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();
        assertTrue(state.begin("fr_fr:abc"));
        state.finish("fr_fr:abc", true);

        assertTrue(state.begin("fr_fr:def"));
    }

    @Test
    void localeChangedDuringReloadLeavesTheOldLocaleRetryable() {
        RemoteLocaleReloadState state = new RemoteLocaleReloadState();
        assertTrue(state.begin("fr_fr:abc"));

        assertFalse(state.finishSelected(
                "fr_fr:abc", "fr_fr", "de_de", true));

        assertFalse(state.applied("fr_fr:abc"));
        assertTrue(state.begin("fr_fr:abc"));
    }

    @Test
    void repeatedChecksShareOneCompleteDownloadOperation() {
        ConcurrentHashMap<String, CompletableFuture<String>> operations =
                new ConcurrentHashMap<>();
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<String> pending = new CompletableFuture<>();

        RemoteLocales.SharedOperation<String> first = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> {
                    starts.incrementAndGet();
                    return pending.thenApply(value -> value + " cached");
                });
        RemoteLocales.SharedOperation<String> repeated = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture("duplicate");
                });

        assertTrue(first.started());
        assertFalse(repeated.started());
        assertSame(first.future(), repeated.future());
        assertEquals(1, starts.get());
        pending.complete("downloaded");
        assertEquals("downloaded cached", repeated.future().join());
        assertTrue(operations.remove("fr_fr", first.future()));

        RemoteLocales.SharedOperation<String> retry = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> CompletableFuture.completedFuture("retry"));
        assertTrue(retry.started());
        assertFalse(operations.remove("fr_fr", first.future()),
                "a stale completion cannot remove a newer retry");
        assertSame(retry.future(), operations.get("fr_fr"));
    }

    @Test
    void synchronouslyCompletedOperationCanAttachAndRemoveAfterInsertion() {
        ConcurrentHashMap<String, CompletableFuture<String>> operations =
                new ConcurrentHashMap<>();
        RemoteLocales.SharedOperation<String> shared = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> CompletableFuture.completedFuture("cached"));
        AtomicInteger completions = new AtomicInteger();
        AtomicBoolean removed = new AtomicBoolean();

        CompletableFuture<String> observed = shared.future().whenComplete((value, failure) -> {
            completions.incrementAndGet();
            removed.set(operations.remove("fr_fr", shared.future()));
        });

        assertEquals("cached", observed.join());
        assertTrue(shared.started());
        assertEquals(1, completions.get());
        assertTrue(removed.get());
        assertFalse(operations.containsKey("fr_fr"));
    }
}
