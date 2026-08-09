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

    private CodecWorker() {}

    public static <T> boolean run(Supplier<T> work, Consumer<T> onClientThread) {
        return submit(WORKER, command -> Minecraft.getInstance().execute(command),
                work, onClientThread);
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
}
