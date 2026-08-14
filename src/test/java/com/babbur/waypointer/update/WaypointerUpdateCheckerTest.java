package com.babbur.waypointer.update;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaypointerUpdateCheckerTest {
    @Test
    void loggingKeepsLookupFailureExceptional() {
        CompletableFuture<String> lookup = new CompletableFuture<>();
        CompletableFuture<String> logged = WaypointerUpdateChecker.logLookupFailure(lookup);
        IllegalStateException failure = new IllegalStateException("offline");

        assertFalse(logged.isDone());
        lookup.completeExceptionally(failure);
        CompletionException thrown = assertThrows(CompletionException.class, logged::join);

        assertSame(failure, thrown.getCause());
    }

    @Test
    void loggingKeepsLookupPendingAndPreservesItsResult() {
        CompletableFuture<String> lookup = new CompletableFuture<>();
        CompletableFuture<String> logged = WaypointerUpdateChecker.logLookupFailure(lookup);

        assertFalse(logged.isDone());
        lookup.complete("1.9.0");

        assertEquals("1.9.0", logged.join());
    }
}
