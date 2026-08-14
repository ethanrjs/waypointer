package com.babbur.waypointer.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLocalesLifecycleTest {
    @Test
    void sharedPendingDownloadRunsAndStoresOnlyOnce() {
        ConcurrentHashMap<String, CompletableFuture<byte[]>> operations =
                new ConcurrentHashMap<>();
        CompletableFuture<byte[]> transport = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stores = new AtomicInteger();

        RemoteLocales.SharedOperation<byte[]> first = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> {
                    starts.incrementAndGet();
                    return RemoteLocales.cacheDownload(transport, ignored -> stores.incrementAndGet());
                });
        RemoteLocales.SharedOperation<byte[]> repeated = RemoteLocales.shareOperation(
                operations, "fr_fr", () -> {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(new byte[] {9});
                });

        assertTrue(first.started());
        assertFalse(repeated.started());
        assertSame(first.future(), repeated.future());
        assertFalse(first.future().isDone());
        assertEquals(1, starts.get());
        assertEquals(0, stores.get());

        byte[] downloaded = {1, 2, 3};
        transport.complete(downloaded);

        assertArrayEquals(downloaded, first.future().join());
        assertEquals(1, stores.get());
    }

    @Test
    void transportFailureSkipsTheCacheAndStaysExceptional() {
        CompletableFuture<byte[]> transport = new CompletableFuture<>();
        AtomicInteger stores = new AtomicInteger();
        CompletableFuture<byte[]> cached = RemoteLocales.cacheDownload(
                transport, ignored -> stores.incrementAndGet());
        IOException failure = new IOException("offline");

        transport.completeExceptionally(failure);

        CompletionException thrown = assertThrows(CompletionException.class, cached::join);
        assertSame(failure, thrown.getCause());
        assertEquals(0, stores.get());
    }

    @Test
    void cacheFailureTurnsTheCompletedDownloadIntoAnExceptionalOperation() {
        IOException failure = new IOException("disk full");
        CompletableFuture<byte[]> cached = RemoteLocales.cacheDownload(
                CompletableFuture.completedFuture(new byte[] {1, 2, 3}),
                ignored -> {
                    throw failure;
                });

        CompletionException thrown = assertThrows(CompletionException.class, cached::join);

        assertSame(failure, thrown.getCause());
    }

    @Test
    void successfulDownloadReplacesRetryStateAndClearsItsOperation() {
        ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, byte[]> overlays = new ConcurrentHashMap<>();
        CompletableFuture<byte[]> operation = new CompletableFuture<>();
        inFlight.put("fr_fr", operation);
        retries.put("fr_fr", 99L);
        byte[] downloaded = {4, 5, 6};

        RemoteLocales.DownloadCompletion completion = RemoteLocales.finishDownloadState(
                inFlight, retries, overlays, "fr_fr", operation, downloaded,
                null, 10L, 60L);

        assertEquals(RemoteLocales.DownloadCompletion.STORED, completion);
        assertSame(downloaded, overlays.get("fr_fr"));
        assertFalse(retries.containsKey("fr_fr"));
        assertFalse(inFlight.containsKey("fr_fr"));
    }

    @Test
    void failedDownloadSchedulesRetryAndClearsItsOperation() {
        ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, byte[]> overlays = new ConcurrentHashMap<>();
        CompletableFuture<byte[]> operation = new CompletableFuture<>();
        inFlight.put("fr_fr", operation);

        RemoteLocales.DownloadCompletion completion = RemoteLocales.finishDownloadState(
                inFlight, retries, overlays, "fr_fr", operation, null,
                new IOException("offline"), 10L, 60L);

        assertEquals(RemoteLocales.DownloadCompletion.FAILED, completion);
        assertEquals(70L, retries.get("fr_fr"));
        assertFalse(overlays.containsKey("fr_fr"));
        assertFalse(inFlight.containsKey("fr_fr"));
    }

    @Test
    void staleDownloadCompletionCannotReplaceANewerOperation() {
        ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, byte[]> overlays = new ConcurrentHashMap<>();
        CompletableFuture<byte[]> stale = new CompletableFuture<>();
        CompletableFuture<byte[]> current = new CompletableFuture<>();
        inFlight.put("fr_fr", current);

        RemoteLocales.DownloadCompletion completion = RemoteLocales.finishDownloadState(
                inFlight, retries, overlays, "fr_fr", stale, new byte[] {1},
                null, 10L, 60L);

        assertEquals(RemoteLocales.DownloadCompletion.STALE, completion);
        assertSame(current, inFlight.get("fr_fr"));
        assertTrue(retries.isEmpty());
        assertTrue(overlays.isEmpty());
    }

    @Test
    void successfulReloadAppliesAndClearsAPreviousRetry() {
        RemoteLocaleReloadState reloads = new RemoteLocaleReloadState();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        String token = "fr_fr:abc";
        assertTrue(reloads.begin(token));
        retries.put("fr_fr", 99L);

        RemoteLocales.ReloadCompletion completion = RemoteLocales.finishReloadState(
                reloads, retries, "fr_fr", token, "fr_fr", null, 10L, 60L);

        assertTrue(completion.applied());
        assertFalse(completion.retryScheduled());
        assertTrue(reloads.applied(token));
        assertFalse(retries.containsKey("fr_fr"));
    }

    @Test
    void reloadFailureForSelectedLocaleSchedulesRetryAndReopensState() {
        RemoteLocaleReloadState reloads = new RemoteLocaleReloadState();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        String token = "fr_fr:abc";
        assertTrue(reloads.begin(token));

        RemoteLocales.ReloadCompletion completion = RemoteLocales.finishReloadState(
                reloads, retries, "fr_fr", token, "fr_fr",
                new IOException("reload failed"), 10L, 60L);

        assertFalse(completion.applied());
        assertTrue(completion.retryScheduled());
        assertEquals(70L, retries.get("fr_fr"));
        assertTrue(reloads.begin(token));
    }

    @Test
    void localeChangeDuringReloadClearsPendingStateWithoutAStaleRetry() {
        RemoteLocaleReloadState reloads = new RemoteLocaleReloadState();
        ConcurrentHashMap<String, Long> retries = new ConcurrentHashMap<>();
        String token = "fr_fr:abc";
        assertTrue(reloads.begin(token));
        retries.put("fr_fr", 99L);

        RemoteLocales.ReloadCompletion completion = RemoteLocales.finishReloadState(
                reloads, retries, "fr_fr", token, "de_de",
                new IOException("stale reload failure"), 10L, 60L);

        assertFalse(completion.applied());
        assertFalse(completion.retryScheduled());
        assertFalse(retries.containsKey("fr_fr"));
        assertTrue(reloads.begin(token));
    }
}
