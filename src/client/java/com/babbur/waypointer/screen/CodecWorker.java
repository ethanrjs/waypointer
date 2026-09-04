package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CodecWorker {

    private static final Executor WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "waypointer-codec");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Executor PREVIEW_WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "waypointer-codec-preview");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final LatestRunner PREVIEW = new LatestRunner(
            PREVIEW_WORKER, command -> Minecraft.getInstance().execute(command));

    private CodecWorker() {}

    public static <T> boolean run(Supplier<T> work, Consumer<T> onClientThread) {
        return submit(WORKER, command -> Minecraft.getInstance().execute(command),
                work, onClientThread);
    }

    public static <T> void runLatestPreview(
            Supplier<T> work, Consumer<T> onClientThread) {
        PREVIEW.submit(work, onClientThread);
    }

    static <T> boolean submit(Executor worker, Executor clientThread,
                              Supplier<T> work, Consumer<T> onClientThread) {
        try {
            worker.execute(() -> {
                T result;
                try {
                    result = work.get();
                } catch (RuntimeException failure) {
                    result = null;
                    Waypointer.LOGGER.debug("Codec work failed", failure);
                }
                T delivered = result;
                clientThread.execute(() -> onClientThread.accept(delivered));
            });
            return true;
        } catch (RejectedExecutionException busy) {
            Waypointer.LOGGER.debug("Codec worker is busy; rejecting queued work");
            return false;
        }
    }

    /**
     * Runs one preview at a time and retains only the newest request while it runs.
     * This keeps automatic recompression bounded without occupying the worker used
     * by explicit import actions.
     */
    static final class LatestRunner {
        private final Executor worker;
        private final Executor clientThread;
        private long latestGeneration;
        private boolean running;
        private Request<?> pending;

        LatestRunner(Executor worker, Executor clientThread) {
            this.worker = worker;
            this.clientThread = clientThread;
        }

        <T> void submit(Supplier<T> work, Consumer<T> delivery) {
            Request<?> start = null;
            synchronized (this) {
                pending = new Request<>(++latestGeneration, work, delivery);
                if (!running) {
                    running = true;
                    start = pending;
                    pending = null;
                }
            }
            if (start != null) start(start);
        }

        private <T> void start(Request<T> request) {
            if (!CodecWorker.submit(worker, clientThread, request.work(),
                    result -> complete(request, result))) {
                // The runner submits at most one task at a time, so this can only
                // happen if its executor is unavailable. Complete on the client
                // thread and let a newer pending request advance.
                clientThread.execute(() -> complete(request, null));
            }
        }

        private <T> void complete(Request<T> request, T result) {
            Request<?> next;
            boolean deliver;
            synchronized (this) {
                deliver = request.generation() == latestGeneration;
                next = pending;
                pending = null;
                if (next == null) running = false;
            }
            if (next != null) start(next);
            if (deliver) request.delivery().accept(result);
        }

        private record Request<T>(
                long generation, Supplier<T> work, Consumer<T> delivery) {
        }
    }
}
