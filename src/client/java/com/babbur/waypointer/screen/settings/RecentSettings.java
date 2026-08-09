package com.babbur.waypointer.screen.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores the session's recently changed settings for the Recent category. */
public final class RecentSettings {

    static final int MAX_ENTRIES = 8;

    private static final LinkedHashMap<String, Long> CHANGES = new LinkedHashMap<>();

    private RecentSettings() {}

    public static synchronized void record(String settingId) {
        if (settingId == null || settingId.isEmpty()) return;
        CHANGES.remove(settingId);
        CHANGES.put(settingId, System.currentTimeMillis());
        while (CHANGES.size() > MAX_ENTRIES) {
            Map.Entry<String, Long> eldest = CHANGES.entrySet().iterator().next();
            CHANGES.remove(eldest.getKey());
        }
    }

    public static synchronized List<String> mostRecentFirst() {
        List<String> ids = new ArrayList<>(CHANGES.keySet());
        java.util.Collections.reverse(ids);
        return ids;
    }

    public static synchronized boolean isEmpty() {
        return CHANGES.isEmpty();
    }

    public static synchronized void clear() {
        CHANGES.clear();
    }
}
