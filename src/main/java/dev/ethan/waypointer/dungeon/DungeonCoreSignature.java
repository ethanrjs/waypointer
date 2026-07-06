package dev.ethan.waypointer.dungeon;

/**
 * Canonical runtime metadata for one scanned dungeon room-core column.
 *
 * <p>The hash remains compatible with the current Odin-derived catalog, while
 * the height/sample metadata gives the detector a stable place to grow toward
 * richer signatures without changing every caller again.
 */
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
