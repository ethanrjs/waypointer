package com.babbur.waypointer.crystal.compass;

/** Minimal immutable vector used by the Minecraft-free compass solver. */
public record Vec3d(double x, double y, double z) {

    private static final double NORMAL_EPSILON = 1.0e-12;

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d scale(double scalar) {
        return new Vec3d(x * scalar, y * scalar, z * scalar);
    }

    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double distanceTo(Vec3d other) {
        return subtract(other).length();
    }

    public Vec3d normalize() {
        double length = length();
        return length <= NORMAL_EPSILON ? new Vec3d(0.0, 0.0, 0.0) : scale(1.0 / length);
    }
}
