package com.babbur.waypointer.screen.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session-only store of recently changed settings, backing the "Recent"
 * pseudo-category pinned at the top of the settings sidebar. It serves the
 * dominant settings journey — tweak, test in world, reopen, tweak again —
 * without persisting anything: the list resets on game restart.
 */
public final class RecentSettings {

    static final int MAX_ENTRIES = 8;

    /** Setting id → last-changed millis; insertion order is oldest-first. */
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

    /** Setting ids, most recently changed first. */
    public static synchronized List<String> mostRecentFirst() {
        List<String> ids = new ArrayList<>(CHANGES.keySet());
        java.util.Collections.reverse(ids);
        return ids;
    }

    public static synchronized boolean isEmpty() {
        return CHANGES.isEmpty();
    }

    /** Bulk operations (reset, import, presets) invalidate per-setting history. */
    public static synchronized void clear() {
        CHANGES.clear();
    }
}
