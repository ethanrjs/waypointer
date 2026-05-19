package dev.ethan.waypointer.diana;

import java.util.List;
import java.util.Optional;

public final class DianaSpadeCurveSolver {

    private static final int DEGREE = 3;
    private static final int TERMS = DEGREE + 1;
    private static final int MIN_SAMPLES = 4;
    private static final double MAX_ENDPOINT_INDEX = 380.0;
    private static final double EPSILON = 1.0E-9;

    private DianaSpadeCurveSolver() {
    }

    public static Optional<Estimate> estimate(List<Sample> samples) {
        if (samples.size() < MIN_SAMPLES) return Optional.empty();

        double[][] coefficients = fitCubic(samples);
        if (coefficients == null) return Optional.empty();

        Vec3 tangent = new Vec3(coefficients[0][1], coefficients[1][1], coefficients[2][1]);
        double tangentLength = tangent.length();
        if (tangentLength < EPSILON) return Optional.empty();

        double endpointIndex = endpointIndex(tangent);
        if (!Double.isFinite(endpointIndex)
                || endpointIndex < samples.size() - 3.0
                || endpointIndex > MAX_ENDPOINT_INDEX) {
            return Optional.empty();
        }

        Vec3 point = evaluate(coefficients, endpointIndex);
        if (!point.isFinite()) return Optional.empty();

        return Optional.of(new Estimate(point.x(), point.y(), point.z(), endpointIndex, samples.size()));
    }

    private static double[][] fitCubic(List<Sample> samples) {
        double[][] normal = new double[TERMS][TERMS];
        double[][] rhs = new double[3][TERMS];

        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            double t = i;
            double[] basis = {1.0, t, t * t, t * t * t};
            for (int row = 0; row < TERMS; row++) {
                for (int col = 0; col < TERMS; col++) {
                    normal[row][col] += basis[row] * basis[col];
                }
                rhs[0][row] += sample.x() * basis[row];
                rhs[1][row] += sample.y() * basis[row];
                rhs[2][row] += sample.z() * basis[row];
            }
        }

        double[][] coefficients = new double[3][TERMS];
        for (int dim = 0; dim < 3; dim++) {
            double[] solved = solve(normal, rhs[dim]);
            if (solved == null) return null;
            coefficients[dim] = solved;
        }
        return coefficients;
    }

    private static double endpointIndex(Vec3 tangent) {
        double horizontal = Math.hypot(tangent.x(), tangent.z());
        if (horizontal < EPSILON) return Double.NaN;

        double observedPitch = -Math.atan2(tangent.y(), horizontal);
        double releaseAngle = invertSpadePitch(observedPitch);
        double curveReach = Math.sqrt(Math.max(0.0, 25.0 - 24.0 * Math.sin(releaseAngle)));
        return (3.0 * curveReach) / tangent.length();
    }

    private static double invertSpadePitch(double observedPitch) {
        double low = -Math.PI / 2.0;
        double high = Math.PI / 2.0;
        for (int i = 0; i < 72; i++) {
            double mid = (low + high) / 2.0;
            double transformed = Math.atan2(Math.sin(mid) - 0.75, Math.cos(mid));
            if (transformed < observedPitch) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2.0;
    }

    private static Vec3 evaluate(double[][] coefficients, double t) {
        double[] basis = {1.0, t, t * t, t * t * t};
        return new Vec3(
                dot(coefficients[0], basis),
                dot(coefficients[1], basis),
                dot(coefficients[2], basis));
    }

    private static double dot(double[] a, double[] b) {
        double total = 0.0;
        for (int i = 0; i < a.length; i++) {
            total += a[i] * b[i];
        }
        return total;
    }

    private static double[] solve(double[][] sourceMatrix, double[] sourceRhs) {
        int n = sourceRhs.length;
        double[][] matrix = new double[n][n + 1];
        for (int row = 0; row < n; row++) {
            System.arraycopy(sourceMatrix[row], 0, matrix[row], 0, n);
            matrix[row][n] = sourceRhs[row];
        }

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(matrix[row][col]) > Math.abs(matrix[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(matrix[pivot][col]) < EPSILON) return null;

            if (pivot != col) {
                double[] tmp = matrix[pivot];
                matrix[pivot] = matrix[col];
                matrix[col] = tmp;
            }

            double pivotValue = matrix[col][col];
            for (int j = col; j <= n; j++) {
                matrix[col][j] /= pivotValue;
            }

            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = matrix[row][col];
                if (Math.abs(factor) < EPSILON) continue;
                for (int j = col; j <= n; j++) {
                    matrix[row][j] -= factor * matrix[col][j];
                }
            }
        }

        double[] solution = new double[n];
        for (int row = 0; row < n; row++) {
            solution[row] = matrix[row][n];
        }
        return solution;
    }

    public record Sample(double x, double y, double z) {
    }

    public record Estimate(double x, double y, double z, double endpointIndex, int sampleCount) {
    }

    private record Vec3(double x, double y, double z) {
        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }
}
