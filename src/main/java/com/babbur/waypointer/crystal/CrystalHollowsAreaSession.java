package com.babbur.waypointer.crystal;

import java.util.ArrayList;
import java.util.List;

public final class CrystalHollowsAreaSession {

    public static final double MAX_AXIS_SPAN = 130.0;
    private static final double SIDEBAR_LAG = 16.0;
    private static final double STAND_SPACING_SQUARED = 4.0 * 4.0;
    private static final int MAX_STANDS = 512;
    private static final double NEAR_DISTANCE_SQUARED = 24.0 * 24.0;

    private final CrystalHollowsStructure structure;
    private final List<CrystalHollowsPosition> stands = new ArrayList<>();
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
        stands.add(stand(x, y, z));
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
        CrystalHollowsPosition latest = stand(x, y, z);
        stands.removeIf(stand -> stand.x() < Math.floor(minX) || stand.x() > maxX
                || stand.y() < Math.floor(minY) || stand.y() > maxY
                || stand.z() < Math.floor(minZ) || stand.z() > maxZ);
        if (stands.isEmpty() || stands.getLast().distanceSquared(latest) >= STAND_SPACING_SQUARED) {
            if (stands.size() == MAX_STANDS) stands.removeFirst();
            stands.add(latest);
        }
        double centreX = (minX + maxX) * 0.5;
        double centreY = (minY + maxY) * 0.5;
        double centreZ = (minZ + maxZ) * 0.5;
        CrystalHollowsPosition nearest = latest;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (CrystalHollowsPosition stand : stands) {
            double dx = stand.x() - centreX, dy = stand.y() - centreY, dz = stand.z() - centreZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= nearestDistance) {
                nearest = stand;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public boolean stoodNear(CrystalHollowsPosition position) {
        for (CrystalHollowsPosition stand : stands) {
            if (stand.distanceSquared(position) <= NEAR_DISTANCE_SQUARED) return true;
        }
        return false;
    }

    public static boolean sameArea(CrystalHollowsPosition first, CrystalHollowsPosition second) {
        return Math.abs(first.x() - second.x()) <= MAX_AXIS_SPAN + SIDEBAR_LAG
                && Math.abs(first.y() - second.y()) <= MAX_AXIS_SPAN + SIDEBAR_LAG
                && Math.abs(first.z() - second.z()) <= MAX_AXIS_SPAN + SIDEBAR_LAG;
    }

    private static CrystalHollowsPosition stand(double x, double y, double z) {
        return new CrystalHollowsPosition(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
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
