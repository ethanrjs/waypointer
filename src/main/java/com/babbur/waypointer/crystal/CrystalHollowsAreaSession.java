package com.babbur.waypointer.crystal;

/** Bounded player-position envelope used to refine one sidebar rough-area marker. */
public final class CrystalHollowsAreaSession {

    public static final double MAX_AXIS_SPAN = 130.0;

    private final CrystalHollowsStructure structure;
    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;

    public CrystalHollowsAreaSession(
            CrystalHollowsStructure structure, double x, double y, double z) {
        this.structure = structure;
        minX = maxX = x;
        minY = maxY = y;
        minZ = maxZ = z;
    }

    public CrystalHollowsStructure structure() { return structure; }

    public CrystalHollowsPosition sample(double x, double y, double z) {
        double[] xBounds = extend(minX, maxX, x);
        double[] yBounds = extend(minY, maxY, y);
        double[] zBounds = extend(minZ, maxZ, z);
        minX = xBounds[0];
        maxX = xBounds[1];
        minY = yBounds[0];
        maxY = yBounds[1];
        minZ = zBounds[0];
        maxZ = zBounds[1];
        return new CrystalHollowsPosition(
                (int) Math.floor((minX + maxX) * 0.5),
                (int) Math.floor((minY + maxY) * 0.5),
                (int) Math.floor((minZ + maxZ) * 0.5));
    }

    private static double[] extend(double minimum, double maximum, double value) {
        double nextMinimum = Math.min(minimum, value);
        double nextMaximum = Math.max(maximum, value);
        if (nextMaximum - nextMinimum <= MAX_AXIS_SPAN) {
            return new double[] {nextMinimum, nextMaximum};
        }
        if (value < minimum) return new double[] {value, value + MAX_AXIS_SPAN};
        return new double[] {value - MAX_AXIS_SPAN, value};
    }
}
