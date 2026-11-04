package com.babbur.waypointer.crystal;

public record CrystalHollowsPosition(int x, int y, int z) {

    public double distanceSquared(CrystalHollowsPosition other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return (double) (dx * dx + dy * dy + dz * dz);
    }
}
