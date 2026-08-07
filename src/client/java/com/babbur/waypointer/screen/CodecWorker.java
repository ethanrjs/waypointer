package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs share-code encoding and decoding off the render thread.
 *
 * <p>Both directions are slow enough on a large route library to drop frames --
 * varint packing plus compression one way, decompression plus group
 * construction the other -- and both used to run inline on a button press, so
 * the click that asked for the work appeared to freeze the game.
 *
 * <p>Single-threaded on purpose. Codec work is naturally serialized (a player
 * imports or exports one thing at a time), one idle daemon thread is cheaper
 * than a pool, and serializing means a queued task can never interleave with
 * the one that superseded it. Daemon so pending work never holds the game open.
 *
 * <p>Callers are responsible for handing the worker data it can safely read:
 * codec input is either an immutable string or a snapshot detached from the
 * live routes (see {@code WaypointGroup.exportSnapshot()}). Everything that
 * touches the manager or the screen happens in the callback, which is
 * dispatched back onto the client thread.
 */
final class CodecWorker {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "waypointer-codec");
        thread.setDaemon(true);
        return thread;
    });

    private CodecWorker() {}

    /**
     * Run {@code work} on the codec thread, then deliver its result to
     * {@code onClientThread}. A thrown {@link RuntimeException} is delivered as
     * a {@code null} result rather than killing the worker, so one malformed
     * payload cannot take the thread down for the rest of the session.
     *
     * @param onClientThread receives the result, or {@code null} if {@code work}
     *     threw. Always runs on the client thread.
     */
    static <T> void run(Supplier<T> work, Consumer<T> onClientThread) {
        WORKER.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException failure) {
                result = null;
                Waypointer.LOGGER.debug("Codec work failed", failure);
            }
            T delivered = result;
            Minecraft.getInstance().execute(() -> onClientThread.accept(delivered));
        });
    }
}
