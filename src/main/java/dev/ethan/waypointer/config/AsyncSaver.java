package dev.ethan.waypointer.config;

import dev.ethan.waypointer.Waypointer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debounced, off-thread writer for config-like files.
 *
 * <p>The stock "save on every setter" path ran the full JSON serialize + atomic
 * file rename on whatever thread the setter ran on, which in practice meant the
 * render or tick thread. Typing "15" into a numeric field fired {@code save()}
 * three times in close succession; a drag-reorder in the group list could fire
 * dozens of saves per second. Each one was a synchronous disk round-trip.
 *
 * <p>Callers wrap their actual write body in a {@link Runnable} and poke
 * {@link #markDirty()} whenever state changes. Multiple marks within
 * {@link #delayMs} collapse into a single write; after the quiet window elapses
 * the writer runs on the shared background thread. {@link #flush()} forces an
 * immediate synchronous write on the calling thread -- used on shutdown so the
 * atomic-rename step has a guaranteed completion point before the JVM exits.
 *
 * <p>Not reusable after a flush race: serializing with {@code synchronized} on
 * {@code lock} keeps the writer body single-threaded and cheap, which matters
 * because the writer itself is allowed to throw and we don't want a partial
 * rename-in-flight colliding with the shutdown flush.
 */
public final class AsyncSaver {

    /**
     * Shared across every saver so we don't leak one thread per config file.
     * Daemon so a lingering write never blocks JVM exit; the shutdown hook's
     * {@link #flush()} is the only path we actually want to wait on.
     */
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

    /**
     * Record a pending change and schedule a write after the quiet window.
     * Cancels any in-flight schedule so rapid-fire mutations collapse into
     * one write instead of fighting for the disk.
     */
    public void markDirty() {
        synchronized (lock) {
            dirty = true;
            if (pending != null) pending.cancel(false);
            pending = EXEC.schedule(this::runScheduled, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Write synchronously on the calling thread if anything is pending.
     * Safe to call from a shutdown hook -- the guaranteed-completion semantics
     * here are the reason saves aren't purely async.
     */
    public void flush() {
        synchronized (lock) {
            if (pending != null) { pending.cancel(false); pending = null; }
            if (dirty) doWrite();
        }
    }

    private void runScheduled() {
        synchronized (lock) {
            pending = null;
            if (dirty) doWrite();
        }
    }

    private void doWrite() {
        dirty = false;
        try {
            writer.run();
        } catch (Throwable t) {
            Waypointer.LOGGER.error("AsyncSaver[{}] write failed", name, t);
        }
    }
}
