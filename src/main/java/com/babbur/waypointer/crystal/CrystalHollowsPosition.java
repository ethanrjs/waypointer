package com.babbur.waypointer.crystal;

/** Integer world position used by the Minecraft-free Crystal Hollows model. */
public record CrystalHollowsPosition(int x, int y, int z) {

    public double distanceSquared(CrystalHollowsPosition other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return (double) (dx * dx + dy * dy + dz * dz);
    }
}
