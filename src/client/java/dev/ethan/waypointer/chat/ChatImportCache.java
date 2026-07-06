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
    private String nextHandle() {
        long n = ++counter;
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.toString();
    }

    private record Entry(String codec, long createdAtMillis) {}
}
