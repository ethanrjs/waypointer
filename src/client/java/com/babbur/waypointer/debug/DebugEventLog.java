package com.babbur.waypointer.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DebugEventLog {

    private static final int MAX_ENTRIES = 32;
    private static final Entry[] ENTRIES = new Entry[MAX_ENTRIES];
    private static int nextIndex;
    private static int count;
    private static long nextSequence;

    private DebugEventLog() {
    }

    public static synchronized void record(String screen,
                                           String targetKind,
                                           String targetId,
                                           int rowIndex,
                                           String selectedBefore,
                                           String selectedAfter,
                                           boolean doubleClick,
                                           boolean shiftDown,
                                           boolean controlDown,
                                           String hitTarget,
                                           String action) {
        Entry entry = new Entry(
                nextSequence++,
                System.currentTimeMillis(),
                normalize(screen, "(unknown screen)"),
                normalize(targetKind, "(unknown target)"),
                normalize(targetId, "(none)"),
                rowIndex,
                normalize(selectedBefore, "(none)"),
                normalize(selectedAfter, "(none)"),
                doubleClick,
                shiftDown,
                controlDown,
                normalize(hitTarget, "(unknown hit)"),
                normalize(action, "(unknown action)"));
        ENTRIES[nextIndex] = entry;
        nextIndex = (nextIndex + 1) % MAX_ENTRIES;
        if (count < MAX_ENTRIES) {
            count++;
        }
    }

    public static synchronized List<Entry> snapshot() {
        List<Entry> snapshot = new ArrayList<>(count);
        int start = (nextIndex - count + MAX_ENTRIES) % MAX_ENTRIES;
        for (int i = 0; i < count; i++) {
            Entry entry = ENTRIES[(start + i) % MAX_ENTRIES];
            if (entry != null) {
                snapshot.add(entry);
            }
        }
        return snapshot;
    }

    public static synchronized void clear() {
        for (int i = 0; i < ENTRIES.length; i++) {
            ENTRIES[i] = null;
        }
        nextIndex = 0;
        count = 0;
        nextSequence = 0;
    }

    private static String normalize(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static final class Entry {
        public final long sequence;
        public final long capturedAtMillis;
        public final String screen;
        public final String targetKind;
        public final String targetId;
        public final int rowIndex;
        public final String selectedBefore;
        public final String selectedAfter;
        public final boolean doubleClick;
        public final boolean shiftDown;
        public final boolean controlDown;
        public final String hitTarget;
        public final String action;

        private Entry(long sequence,
                      long capturedAtMillis,
                      String screen,
                      String targetKind,
                      String targetId,
                      int rowIndex,
                      String selectedBefore,
                      String selectedAfter,
                      boolean doubleClick,
                      boolean shiftDown,
                      boolean controlDown,
                      String hitTarget,
                      String action) {
            this.sequence = sequence;
            this.capturedAtMillis = capturedAtMillis;
            this.screen = screen;
            this.targetKind = targetKind;
            this.targetId = targetId;
            this.rowIndex = rowIndex;
            this.selectedBefore = selectedBefore;
            this.selectedAfter = selectedAfter;
            this.doubleClick = doubleClick;
            this.shiftDown = shiftDown;
            this.controlDown = controlDown;
            this.hitTarget = hitTarget;
            this.action = action;
        }

        public String plainText() {
            return String.format(Locale.ROOT,
                    "#%d %s %s=%s row=%d selected=%s->%s dbl=%s shift=%s ctrl=%s hit=%s action=%s",
                    sequence,
                    screen,
                    targetKind,
                    targetId,
                    rowIndex,
                    selectedBefore,
                    selectedAfter,
                    doubleClick ? "Y" : "N",
                    shiftDown ? "Y" : "N",
                    controlDown ? "Y" : "N",
                    hitTarget,
                    action);
        }
    }
}
