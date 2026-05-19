package dev.ethan.waypointer.math;

/**
 * Small geometric helpers shared by client-only features (Diana warp assist, etc.).
 */
public final class DistanceUtil {

    private DistanceUtil() {}

    /** Euclidean distance between two points in world space. */
    public static double euclidean(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
