package com.babbur.waypointer.config;

import com.babbur.waypointer.Waypointer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AsyncSaver {

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "waypointer-saver");
        t.setDaemon(true);
        return t;
    });

    private final Runnable writer;
    private final long delayMs;
    private final String name;

    private final Object lock = new Object();

    private final Object writeLock = new Object();
    private ScheduledFuture<?> pending;
    private boolean dirty;

    public AsyncSaver(String name, Runnable writer, long delayMs) {
        this.name = name;
        this.writer = writer;
        this.delayMs = delayMs;
    }

    public void markDirty() {
        synchronized (lock) {
            dirty = true;
            if (pending != null) pending.cancel(false);
            pending = EXEC.schedule(this::runScheduled, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    public void flush() {
        synchronized (writeLock) {
            synchronized (lock) {
                if (pending != null) { pending.cancel(false); pending = null; }
            }
            writeIfDirty();
        }
    }

    public void discard() {
        synchronized (lock) {
            if (pending != null) { pending.cancel(false); pending = null; }
            dirty = false;
        }
    }

    private void runScheduled() {
        synchronized (writeLock) {
            try {
                writeIfDirty();
            } catch (RuntimeException t) {
                Waypointer.LOGGER.error("AsyncSaver[{}] write failed", name, t);
            }
        }
    }

    private void writeIfDirty() {
        synchronized (lock) {
            if (!dirty) return;
            dirty = false;
        }
        try {
            writer.run();
        } catch (RuntimeException failure) {
            synchronized (lock) { dirty = true; }
            throw failure;
        }
    }
}
