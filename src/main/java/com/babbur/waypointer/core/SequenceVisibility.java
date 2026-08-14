package com.babbur.waypointer.core;

public record SequenceVisibility(int previous, boolean current, int next) {

    public static final int MAX_CONTEXT_WAYPOINTS = 32;
    /** Shows every previous step. */
    public static final int ALL = MAX_CONTEXT_WAYPOINTS + 1;
    public static final SequenceVisibility DEFAULT = new SequenceVisibility(1, true, 1);

    public SequenceVisibility {
        previous = clampPrevious(previous);
        next = clampFinite(next);
    }

    public boolean allPrevious() {
        return previous == ALL;
    }

    public int previousLimit(int available) {
        return allPrevious() ? Math.max(0, available) : previous;
    }

    public int nextLimit(int available) {
        return next;
    }

    private static int clampPrevious(int value) {
        if (value == ALL) return ALL;
        return clampFinite(value);
    }

    private static int clampFinite(int value) {
        return Math.max(0, Math.min(MAX_CONTEXT_WAYPOINTS, value));
    }
}
