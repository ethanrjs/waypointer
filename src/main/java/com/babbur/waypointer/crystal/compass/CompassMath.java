package com.babbur.waypointer.crystal.compass;

import java.util.List;
import java.util.Optional;

/** Line fitting and triangulation math for Wishing Compass trails. */
public final class CompassMath {

    public static final double PARALLEL_DENOMINATOR = 1.0e-9;

    public record RayFit(Vec3d origin, Vec3d direction) {}
    public record ClosestPoints(Vec3d onFirst, Vec3d onSecond, double firstParameter,
                                double secondParameter) {
        public double gap() {
            return onFirst.distanceTo(onSecond);
        }

        public Vec3d midpoint() {
            return onFirst.add(onSecond).scale(0.5);
        }
    }

    private CompassMath() {}

    public static RayFit fitRay(List<Vec3d> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("at least two points are required");
        }
        Vec3d first = points.getFirst();
        Vec3d last = points.getLast();
        Vec3d travel = last.subtract(first);
        if (points.size() < 3) return new RayFit(first, travel.normalize());

        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (Vec3d point : points) {
            cx += point.x();
            cy += point.y();
            cz += point.z();
        }
        double count = points.size();
        cx /= count;
        cy /= count;
        cz /= count;

        double xx = 0.0;
        double xy = 0.0;
        double xz = 0.0;
        double yy = 0.0;
        double yz = 0.0;
        double zz = 0.0;
        for (Vec3d point : points) {
            double x = point.x() - cx;
            double y = point.y() - cy;
            double z = point.z() - cz;
            xx += x * x;
            xy += x * y;
            xz += x * z;
            yy += y * y;
            yz += y * z;
            zz += z * z;
        }

        Vec3d direction = travel.normalize();
        if (direction.lengthSquared() == 0.0) direction = new Vec3d(1.0, 0.0, 0.0);
        for (int iteration = 0; iteration < 30; iteration++) {
            direction = new Vec3d(
                    xx * direction.x() + xy * direction.y() + xz * direction.z(),
                    xy * direction.x() + yy * direction.y() + yz * direction.z(),
                    xz * direction.x() + yz * direction.y() + zz * direction.z()).normalize();
        }
        if (direction.dot(travel) < 0.0) direction = direction.scale(-1.0);
        return new RayFit(first, direction);
    }

    public static Optional<ClosestPoints> closestPoints(CompassRay first, CompassRay second) {
        Vec3d p1 = first.origin();
        Vec3d p2 = second.origin();
        Vec3d d1 = first.direction();
        Vec3d d2 = second.direction();
        Vec3d w0 = p1.subtract(p2);
        double a = d1.dot(d1);
        double b = d1.dot(d2);
        double c = d2.dot(d2);
        double d = d1.dot(w0);
        double e = d2.dot(w0);
        double denominator = a * c - b * b;
        if (Math.abs(denominator) < PARALLEL_DENOMINATOR) return Optional.empty();
        double firstParameter = (b * e - c * d) / denominator;
        double secondParameter = (a * e - b * d) / denominator;
        return Optional.of(new ClosestPoints(
                p1.add(d1.scale(firstParameter)),
                p2.add(d2.scale(secondParameter)),
                firstParameter,
                secondParameter));
    }

    public static double angleDegrees(CompassRay first, CompassRay second) {
        double dot = Math.clamp(first.direction().dot(second.direction()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
