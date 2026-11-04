package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecWorkerTest {

    @Test
    void previewRunnerExecutesAndDeliversOnlyTheLatestPendingRequest() throws Exception {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        List<Integer> executed = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
        CodecWorker.LatestRunner previews = new CodecWorker.LatestRunner(
                worker, Runnable::run);

        try {
            previews.submit(() -> {
                executed.add(1);
                firstStarted.countDown();
                await(releaseFirst);
                return 1;
            }, result -> {
                results.add(result);
                delivered.countDown();
            });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            previews.submit(() -> {
                executed.add(2);
                return 2;
            }, result -> {
                results.add(result);
                delivered.countDown();
            });
            previews.submit(() -> {
                executed.add(3);
                return 3;
            }, result -> {
                results.add(result);
                delivered.countDown();
            });
            releaseFirst.countDown();

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 3), executed);
            assertEquals(List.of(3), results);
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void failedStalePreviewRecoversWhenClientDeliveryWasDelayed() throws Exception {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        ArrayBlockingQueue<Runnable> clientTasks = new ArrayBlockingQueue<>(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> executed = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
        CodecWorker.LatestRunner previews = new CodecWorker.LatestRunner(
                worker, clientTasks::add);

        try {
            previews.<Integer>submit(() -> {
                executed.add(1);
                firstStarted.countDown();
                await(releaseFirst);
                throw new IllegalStateException("broken preview");
            }, results::add);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            releaseFirst.countDown();

            Runnable staleCompletion = clientTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(staleCompletion);
            previews.submit(() -> {
                executed.add(2);
                return 2;
            }, results::add);
            previews.submit(() -> {
                executed.add(3);
                return 3;
            }, results::add);

            staleCompletion.run();
            Runnable latestCompletion = clientTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(latestCompletion);
            latestCompletion.run();

            assertEquals(List.of(1, 3), executed);
            assertEquals(List.of(3), results);
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void rejectsWorkWhenTheWorkerAndSinglePendingSlotAreOccupied() throws Exception {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<Integer> results = new ArrayList<>();

        try {
            assertTrue(CodecWorker.submit(worker, Runnable::run, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return 1;
            }, result -> {
                results.add(result);
                delivered.countDown();
            }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(CodecWorker.submit(worker, Runnable::run, () -> 2, result -> {
                results.add(result);
                delivered.countDown();
            }));

            assertFalse(CodecWorker.submit(worker, Runnable::run, () -> 3, results::add));
            releaseFirst.countDown();

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), results);
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }
}
