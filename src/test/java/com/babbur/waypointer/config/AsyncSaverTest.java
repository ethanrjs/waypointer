package com.babbur.waypointer.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncSaverTest {

    @Test
    void markingDirtyDoesNotWaitForAWriteInProgress() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondWrite = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AsyncSaver saver = new AsyncSaver("test", () -> {
            if (writes.incrementAndGet() == 1) {
                writing.countDown();
                await(release);
            } else {
                secondWrite.countDown();
            }
        }, 1);

        saver.markDirty();
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        saver.markDirty();
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertTrue(waitedMillis < 1_000, "markDirty waited " + waitedMillis + " ms for the disk");
        assertTrue(secondWrite.await(5, TimeUnit.SECONDS), "a change made during a write is written afterwards");
    }

    @Test
    void flushWritesPendingChangesOnceAndKeepsThemAfterAFailure() {
        AtomicInteger writes = new AtomicInteger();
        boolean[] fail = {true};
        AsyncSaver saver = new AsyncSaver("test", () -> {
            writes.incrementAndGet();
            if (fail[0]) throw new IllegalStateException("disk full");
        }, 60_000);

        saver.flush();
        assertEquals(0, writes.get(), "nothing to write");

        saver.markDirty();
        assertThrows(IllegalStateException.class, saver::flush);
        fail[0] = false;
        saver.flush();
        saver.flush();
        assertEquals(2, writes.get(), "the failed write is retried once, then nothing is left");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
