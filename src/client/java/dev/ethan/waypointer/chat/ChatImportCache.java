package dev.ethan.waypointer.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Map<String, String> entries = new HashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private long counter;

    /** Returns the handle to use in click events; caller stores the mapping. */
    public synchronized String put(String codec) {
        String handle = nextHandle();
        entries.put(handle, codec);
        order.addLast(handle);
        while (order.size() > CAPACITY) {
            String evicted = order.removeFirst();
            entries.remove(evicted);
        }
        return handle;
    }

    public synchronized String get(String handle) {
        return entries.get(handle);
    }

    public synchronized void clear() {
        entries.clear();
        order.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Snapshot of live handles in insertion order, newest last. Copied so the
     * caller can iterate without holding the cache lock or racing the eviction
     * path. Powers {@code /wp importchat} tab-complete.
     */
    public synchronized List<String> handles() {
        return new ArrayList<>(order);
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
}
