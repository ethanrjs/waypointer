package com.babbur.waypointer.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Stores chat share codes behind short handles that fit in click commands. */
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
        if (entry == null) return null;
        order.remove(handle);
        order.addLast(handle);
        return entry.codec();
    }

    public synchronized void clear() {
        entries.clear();
        order.clear();
    }

    public synchronized int size() {
        pruneExpired(clock.getAsLong());
        return entries.size();
    }

    /** Returns a copy of live handles in least-to-most-recently-used order. */
    public synchronized List<String> handles() {
        pruneExpired(clock.getAsLong());
        return new ArrayList<>(order);
    }

    private void pruneExpired(long now) {
        Iterator<String> handles = order.iterator();
        while (handles.hasNext()) {
            String handle = handles.next();
            Entry entry = entries.get(handle);
            if (entry != null && entry.createdAtMillis() + ttlMillis > now) continue;
            handles.remove();
            if (entry != null) entries.remove(handle);
        }
    }

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
