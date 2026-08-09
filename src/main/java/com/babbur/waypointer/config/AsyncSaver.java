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
    private ScheduledFuture<?> pending;
    private boolean dirty;

    /**
     * @param name    log label so error output distinguishes config vs waypoints
     * @param writer  the actual serialize+write body (runs on the saver thread
     *                when debounced, on the caller thread when flushing)
     * @param delayMs quiet window before a dirty marker triggers a write
     */
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

    /** Writes pending data on the calling thread. A failure remains pending. */
    public void flush() {
        synchronized (lock) {
            if (pending != null) { pending.cancel(false); pending = null; }
            if (dirty) doWrite();
        }
    }

    public void discard() {
        synchronized (lock) {
            if (pending != null) { pending.cancel(false); pending = null; }
            dirty = false;
        }
    }

    private void runScheduled() {
        synchronized (lock) {
            pending = null;
            if (!dirty) return;
            try {
                doWrite();
            } catch (RuntimeException t) {
                Waypointer.LOGGER.error("AsyncSaver[{}] write failed", name, t);
            }
        }
    }

    private void doWrite() {
        writer.run();
        dirty = false;
    }
}
