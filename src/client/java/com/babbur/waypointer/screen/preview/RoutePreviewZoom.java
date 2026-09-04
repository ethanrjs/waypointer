package com.babbur.waypointer.screen.preview;

public final class RoutePreviewZoom {

    static final double DEFAULT_FACTOR = 1.00;
    static final double MIN_FACTOR = 0.50;
    static final double MAX_FACTOR = 4.00;
    static final double STEP_FACTOR = 1.15;

    private double factor = DEFAULT_FACTOR;

    public double factor() {
        return factor;
    }

    public void reset() {
        factor = DEFAULT_FACTOR;
    }

    public boolean scroll(double verticalAmount) {
        if (!Double.isFinite(verticalAmount) || verticalAmount == 0.0) return false;
        double next = factor * Math.pow(STEP_FACTOR, verticalAmount);
        if (!Double.isFinite(next)) next = verticalAmount > 0.0 ? MAX_FACTOR : MIN_FACTOR;
        next = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, next));
        if (Double.compare(next, factor) == 0) return false;
        factor = next;
        return true;
    }
}
