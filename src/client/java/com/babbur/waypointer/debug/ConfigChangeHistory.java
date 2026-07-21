package com.babbur.waypointer.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Session-only audit trail for settings changes that may explain a new problem. */
public final class ConfigChangeHistory {

    private static final int MAX_ENTRIES = 24;
    private static final Entry[] ENTRIES = new Entry[MAX_ENTRIES];
    private static int nextIndex;
    private static int count;

    private ConfigChangeHistory() {
    }

    public static synchronized void recordSetting(String settingId, String before, String after) {
        String normalizedId = normalize(settingId, "(unknown setting)");
        String normalizedBefore = normalize(before, "(empty)");
        String normalizedAfter = normalize(after, "(empty)");
        if (Objects.equals(normalizedBefore, normalizedAfter)) return;
        append(new Entry(System.currentTimeMillis(), "setting", normalizedId,
                normalizedBefore, normalizedAfter));
    }

    public static synchronized void recordBulk(String action) {
        append(new Entry(System.currentTimeMillis(), "bulk", normalize(action, "bulk update"),
                "(multiple settings)", "(current values shown below)"));
    }

    /** Entries ordered newest first for fast troubleshooting. */
    public static synchronized List<Entry> snapshot() {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = (nextIndex - 1 - i + MAX_ENTRIES) % MAX_ENTRIES;
            Entry entry = ENTRIES[index];
            if (entry != null) entries.add(entry);
        }
        return List.copyOf(entries);
    }

    public static synchronized void clear() {
        for (int i = 0; i < ENTRIES.length; i++) ENTRIES[i] = null;
        nextIndex = 0;
        count = 0;
    }

    private static void append(Entry entry) {
        ENTRIES[nextIndex] = entry;
        nextIndex = (nextIndex + 1) % MAX_ENTRIES;
        if (count < MAX_ENTRIES) count++;
    }

    private static String normalize(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public record Entry(long capturedAtMillis, String kind, String subject,
                        String before, String after) {
    }
}
