package dev.ethan.waypointer.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory LRU cache of codec strings the detector has seen in chat.
 *
 * The alternative -- stuffing the entire codec into a {@code /wp import} command
 * via a click event -- hits the vanilla 256-char chat input cap for any route of
 * meaningful size. Stashing the payload here and only passing a short handle through
 * the click event sidesteps that limit and keeps the chat log readable.
 *
 * Handles live only for this client session on purpose. An imported route persists
 * to the usual waypoint storage; the cache just shuttles the raw codec from the
 * message-modify hook to the click handler.
 */
public final class ChatImportCache {

    private static final int CAPACITY = 16;
    private static final long DEFAULT_TTL_MS = 15 * 60_000L;

    private final Map<String, Entry> entries = new HashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private final LongSupplier clock;
    private final long ttlMillis;
    private long counter;

    public ChatImportCache() {
        this(System::currentTimeMillis, DEFAULT_TTL_MS);
    }

    ChatImportCache(LongSupplier clock, long ttlMillis) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    /** Returns the handle to use in click events; caller stores the mapping. */
    public synchronized String put(String codec) {
        pruneExpired(clock.getAsLong());
        String handle = nextHandle();
        entries.put(handle, new Entry(codec, clock.getAsLong()));
        order.addLast(handle);
        while (order.size() > CAPACITY) {
            String evicted = order.removeFirst();
            entries.remove(evicted);
        }
        return handle;
    }

    public synchronized String get(String handle) {
        pruneExpired(clock.getAsLong());
        Entry entry = entries.get(handle);
        return entry == null ? null : entry.codec();
    }

    public synchronized void clear() {
        entries.clear();
        order.clear();
    }

    public synchronized int size() {
        pruneExpired(clock.getAsLong());
        return entries.size();
    }

    /**
     * Snapshot of live handles in insertion order, newest last. Copied so the
     * caller can iterate without holding the cache lock or racing the eviction
     * path. Powers {@code /wp importchat} tab-complete.
     */
    public synchronized List<String> handles() {
        pruneExpired(clock.getAsLong());
        return new ArrayList<>(order);
    }

    private void pruneExpired(long now) {
        while (!order.isEmpty()) {
            String handle = order.peekFirst();
            Entry entry = entries.get(handle);
            if (entry == null) {
                order.removeFirst();
                continue;
            }
            if (entry.createdAtMillis() + ttlMillis > now) return;
            order.removeFirst();
            entries.remove(handle);
        }
    }

    /**
     * Produces short (2-3 char) alphanumeric handles in order. Brevity matters
     * because the handle ends up inside the click command and eats into the chat
     * input limit if the user ever manually expands the pill.
     */
    /*[[AI-FN-DOC
Function:
nextHandle
Purpose:
Generate the next short cache handle that is not currently live in the chat import cache.
Why this exists:
Click events need a compact token instead of embedding the full codec payload, and the cache must not overwrite a still-live entry if the counter ever wraps far in the future.
When to use:
Use only from synchronized cache mutation paths immediately after pruning expired entries. Do not call it without holding this cache's monitor because it reads entries.
Inputs:
No parameters. Reads and increments the instance counter and checks the live entries map.
Outputs:
Returns a lowercase alphabetic handle string. The returned handle is absent from entries at the moment it is returned.
Side effects:
Increments counter at least once and may increment it more if a generated handle is still live. Does not mutate entries or order directly.
Failure modes:
If counter wraps and every possible generated live-window handle is occupied, the loop continues until it finds a free generated value; with capacity 16 this is bounded in practice.
Important invariants:
Generated handles must remain compact for normal operation. A returned handle must not collide with a live cache entry. put remains responsible for storing the returned handle.
Internal logic:
Increment the counter, encode it as base-26 lowercase letters, and repeat while entries already contains that handle.
Pseudocode:
Declare handle.
Do:
Increment counter into n.
Create a string builder.
Append base-26 letters for n until n reaches zero.
Set handle to the builder string.
While entries contains handle, repeat.
Return handle.
Implementation notes:
The do/while collision guard is simpler than documenting a theoretical monotonic invariant, and CAPACITY keeps the live set tiny.
AI self-check:
Verify the method still returns short handles normally, does not overwrite live entries after wrap, and remains private to synchronized callers.
]]*/
    private String nextHandle() {
        String handle;
        do {
            long n = ++counter;
            StringBuilder sb = new StringBuilder();
            do {
                sb.append((char) ('a' + (int) (n % 26)));
                n /= 26;
            } while (n > 0);
            handle = sb.toString();
        } while (entries.containsKey(handle));
        return handle;
    }

    private record Entry(String codec, long createdAtMillis) {}
}
