package com.babbur.waypointer.dungeon;

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
