package com.babbur.waypointer.diana;

import com.babbur.waypointer.crystal.compass.Vec3d;
import java.util.ArrayList;
import java.util.List;

/**
 * Fits the spade's cubic particle trail and extrapolates its endpoint.
 * Geometry adapted from SkyblockOverhaul/SBO PreciseGuessBurrow.kt (Apache-2.0);
 * see META-INF/licenses/sbo-notice.txt. The fit and validation are Waypointer's.
 */
public final class DianaSpadeSolver {
    private final List<Vec3d> particles = new ArrayList<>();
    private Vec3d origin;
    private long usedAt;
    private long lastParticleAt;
    private Vec3d estimate;

    public boolean use(Vec3d player, long now) {
        // A held use key must not restart a trail that is still arriving.
        if (origin != null && now - lastParticleAt < 200 && !particles.isEmpty()) return false;
        reset();
        origin = player;
        usedAt = now;
        return true;
    }

    public Vec3d estimate() { return estimate; }

    public boolean accept(Vec3d point, long now) {
        if (origin == null || now < usedAt || now - usedAt > 3_000 || particles.size() >= 128
                || !finite(point)) return false;
        if (particles.isEmpty()) {
            if (point.distanceTo(origin) > 8) return false;
        } else {
            double step = point.distanceTo(particles.getLast());
            if (step < 1.0e-7 || step > 3) return false;
        }
        particles.add(point);
        lastParticleAt = now;
        if (particles.size() < 10) return true;
        Vec3d full = fitEndpoint(particles);
        Vec3d prefix = fitEndpoint(particles.subList(0, particles.size() - 2));
        // Extrapolated fits can look smooth while giving wildly different targets.
        // Require agreement after adding two more samples before displaying one.
        estimate = full != null && prefix != null && full.distanceTo(prefix) <= 1
                && full.distanceTo(origin) <= 600 && full.y() >= 40 && full.y() <= 180
                && Math.abs(full.x()) <= 512 && Math.abs(full.z()) <= 512 ? full : null;
        return true;
    }

    public void reset() {
        particles.clear();
        origin = null;
        estimate = null;
        usedAt = lastParticleAt = 0;
    }

    static Vec3d fitEndpoint(List<Vec3d> samples) {
        int n = samples.size();
        if (n < 8) return null;
        // Fit in [0,1] using reorthogonalized QR, avoiding the ill-conditioned
        // inverse of the normal equations for raw particle indices.
        double[][] q = new double[4][n];
        double[][] r = new double[4][4];
        for (int column = 0; column < 4; column++) {
            for (int i = 0; i < n; i++) q[column][i] = Math.pow((double) i / (n - 1), column);
            for (int pass = 0; pass < 2; pass++) {
                for (int previous = 0; previous < column; previous++) {
                    double projection = 0;
                    for (int i = 0; i < n; i++) projection += q[previous][i] * q[column][i];
                    r[previous][column] += projection;
                    for (int i = 0; i < n; i++) q[column][i] -= projection * q[previous][i];
                }
            }
            double length = 0;
            for (double value : q[column]) length += value * value;
            r[column][column] = Math.sqrt(length);
            if (r[column][column] < 1.0e-10) return null;
            for (int i = 0; i < n; i++) q[column][i] /= r[column][column];
        }
        double[][] coefficients = new double[3][4];
        Vec3d start = samples.getFirst();
        for (int axis = 0; axis < 3; axis++) {
            for (int column = 0; column < 4; column++) {
                for (int i = 0; i < n; i++) {
                    coefficients[axis][column] += q[column][i]
                            * (coordinate(samples.get(i), axis) - coordinate(start, axis));
                }
            }
            for (int row = 3; row >= 0; row--) {
                for (int col = row + 1; col < 4; col++) {
                    coefficients[axis][row] -= r[row][col] * coefficients[axis][col];
                }
                coefficients[axis][row] /= r[row][row];
            }
        }
        double residual = 0;
        for (int i = 0; i < n; i++) {
            Vec3d fitted = evaluate(coefficients, (double) i / (n - 1)).add(start);
            residual += fitted.subtract(samples.get(i)).lengthSquared();
        }
        if (Math.sqrt(residual / n) > 0.02) return null;
        Vec3d derivative = new Vec3d(coefficients[0][1], coefficients[1][1], coefficients[2][1]);
        if (!finite(derivative) || derivative.length() < 1.0e-5) return null;
        double observedPitch = -Math.atan2(derivative.y(), Math.hypot(derivative.x(), derivative.z()));
        double low = -Math.PI / 2, high = Math.PI / 2;
        for (int i = 0; i < 60; i++) {
            double pitch = (low + high) / 2;
            if (Math.atan2(Math.sin(pitch) - 0.75, Math.cos(pitch)) < observedPitch) low = pitch;
            else high = pitch;
        }
        double controlDistance = Math.sqrt(25 - 24 * Math.sin((low + high) / 2));
        double t = 3 * controlDistance / derivative.length();
        // The captured trail begins at the cast; a predicted endpoint behind
        // the observed portion cannot be supported by this geometry.
        if (!Double.isFinite(t) || t < 0.95 || t > 100) return null;
        Vec3d endpoint = evaluate(coefficients, t).add(start);
        return finite(endpoint) ? endpoint : null;
    }

    private static double coordinate(Vec3d point, int axis) {
        return axis == 0 ? point.x() : axis == 1 ? point.y() : point.z();
    }

    private static Vec3d evaluate(double[][] c, double t) {
        return new Vec3d(polynomial(c[0], t), polynomial(c[1], t), polynomial(c[2], t));
    }

    private static double polynomial(double[] c, double t) {
        return ((c[3] * t + c[2]) * t + c[1]) * t + c[0];
    }

    private static boolean finite(Vec3d p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }
}
