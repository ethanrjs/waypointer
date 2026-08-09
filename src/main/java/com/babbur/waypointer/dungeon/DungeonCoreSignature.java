package com.babbur.waypointer.dungeon;

/** A room-core hash plus the column range used to calculate it. */
public record DungeonCoreSignature(int hash, int topY, int sampleCount) {
    public static final DungeonCoreSignature UNKNOWN = new DungeonCoreSignature(0, 0, 0);

    public DungeonCoreSignature {
        topY = Math.max(0, topY);
        sampleCount = Math.max(0, sampleCount);
    }

    public int hash() {
        return hash;
    }

    public int topY() {
        return topY;
    }

    public int sampleCount() {
        return sampleCount;
    }
}
