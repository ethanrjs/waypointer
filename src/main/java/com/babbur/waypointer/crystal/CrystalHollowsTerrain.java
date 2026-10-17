package com.babbur.waypointer.crystal;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Which blocks of the Crystal Hollows are solid, as seen by the client: one bit per block over
 * the whole Hollows, plus which chunk columns have been seen at all. Scans from different
 * lobbies are kept apart and combined later, because each lobby has its own structures and
 * player-mined holes.
 */
public final class CrystalHollowsTerrain {
    public static final int MIN_X = CrystalHollowsGeometry.MIN_XZ;
    public static final int MIN_Z = CrystalHollowsGeometry.MIN_XZ;
    public static final int MIN_Y = 30;
    public static final int MAX_Y = 188;
    public static final int SIZE_XZ = CrystalHollowsGeometry.MAX_XZ - CrystalHollowsGeometry.MIN_XZ;
    public static final int SIZE_Y = MAX_Y - MIN_Y + 1;
    public static final int MIN_CHUNK = Math.floorDiv(MIN_X, 16);
    public static final int MAX_CHUNK = Math.floorDiv(MIN_X + SIZE_XZ - 1, 16);
    public static final int CHUNKS = MAX_CHUNK - MIN_CHUNK + 1;

    private static final int MAGIC = 0x57504348; // "WPCH"
    private static final int VERSION = 1;
    private static final long BLOCKS = (long) SIZE_XZ * SIZE_Y * SIZE_XZ;

    private final long[] solid = new long[(int) ((BLOCKS + 63) / 64)];
    private final BitSet seenChunks = new BitSet(CHUNKS * CHUNKS);
    private int revision;

    public static boolean contains(int x, int y, int z) {
        return x >= MIN_X && x < MIN_X + SIZE_XZ && z >= MIN_Z && z < MIN_Z + SIZE_XZ
                && y >= MIN_Y && y <= MAX_Y;
    }

    private static long index(int x, int y, int z) {
        return ((long) (y - MIN_Y) * SIZE_XZ + (z - MIN_Z)) * SIZE_XZ + (x - MIN_X);
    }

    public boolean solid(int x, int y, int z) {
        if (!contains(x, y, z)) return true;
        long i = index(x, y, z);
        return (solid[(int) (i >>> 6)] & (1L << (i & 63))) != 0;
    }

    public void setSolid(int x, int y, int z, boolean value) {
        if (!contains(x, y, z)) return;
        long i = index(x, y, z);
        if (value) solid[(int) (i >>> 6)] |= 1L << (i & 63);
        else solid[(int) (i >>> 6)] &= ~(1L << (i & 63));
    }

    public static boolean chunkInside(int chunkX, int chunkZ) {
        return chunkX >= MIN_CHUNK && chunkX <= MAX_CHUNK && chunkZ >= MIN_CHUNK && chunkZ <= MAX_CHUNK;
    }

    public boolean chunkSeen(int chunkX, int chunkZ) {
        return chunkInside(chunkX, chunkZ) && seenChunks.get(chunkIndex(chunkX, chunkZ));
    }

    public void markChunkSeen(int chunkX, int chunkZ) {
        if (!chunkInside(chunkX, chunkZ)) return;
        seenChunks.set(chunkIndex(chunkX, chunkZ));
        revision++;
    }

    private static int chunkIndex(int chunkX, int chunkZ) {
        return (chunkZ - MIN_CHUNK) * CHUNKS + (chunkX - MIN_CHUNK);
    }

    /** Changes whenever a chunk is added, so views know to redraw. */
    public int revision() {
        return revision;
    }

    public int seenChunkCount() {
        return seenChunks.cardinality();
    }

    public static int totalChunks() {
        return CHUNKS * CHUNKS;
    }

    /** The nearest chunk column not seen yet, as {chunkX, chunkZ}, or null once all are seen. */
    public int[] nearestUnseenChunk(double x, double z) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cz = MIN_CHUNK; cz <= MAX_CHUNK; cz++) {
            for (int cx = MIN_CHUNK; cx <= MAX_CHUNK; cx++) {
                if (seenChunks.get(chunkIndex(cx, cz))) continue;
                double dx = cx * 16 + 8 - x, dz = cz * 16 + 8 - z;
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[]{cx, cz};
                }
            }
        }
        return best;
    }

    /** An independent copy, e.g. to save on another thread while scanning goes on. */
    public CrystalHollowsTerrain copy() {
        CrystalHollowsTerrain copy = new CrystalHollowsTerrain();
        System.arraycopy(solid, 0, copy.solid, 0, solid.length);
        copy.seenChunks.or(seenChunks);
        copy.revision = revision;
        return copy;
    }

    /** Takes another scan's blocks for every chunk column it has seen and this one hasn't. */
    public void fillFrom(CrystalHollowsTerrain other) {
        for (int cz = MIN_CHUNK; cz <= MAX_CHUNK; cz++) {
            for (int cx = MIN_CHUNK; cx <= MAX_CHUNK; cx++) {
                if (chunkSeen(cx, cz) || !other.chunkSeen(cx, cz)) continue;
                copyChunk(other, cx, cz);
            }
        }
    }

    /** Replaces one chunk column with another scan's copy of it. */
    public void copyChunk(CrystalHollowsTerrain other, int chunkX, int chunkZ) {
        int x0 = Math.max(MIN_X, chunkX * 16), x1 = Math.min(MIN_X + SIZE_XZ - 1, chunkX * 16 + 15);
        int z0 = Math.max(MIN_Z, chunkZ * 16), z1 = Math.min(MIN_Z + SIZE_XZ - 1, chunkZ * 16 + 15);
        for (int y = MIN_Y; y <= MAX_Y; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) setSolid(x, y, z, other.solid(x, y, z));
            }
        }
        markChunkSeen(chunkX, chunkZ);
    }

    /**
     * Whether a player can stand somewhere in the column at (x, z) near height {@code feetY}:
     * two open blocks on top of each other within a few blocks of that height.
     */
    public boolean walkableNear(int x, int z, int feetY, int reach) {
        int run = 0;
        for (int y = feetY - reach; y <= feetY + reach + 1; y++) {
            if (!contains(x, y, z)) {
                run = 0;
                continue;
            }
            run = solid(x, y, z) ? 0 : run + 1;
            if (run >= 2) return true;
        }
        return false;
    }

    public void write(OutputStream raw) throws IOException {
        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(
                new GZIPOutputStream(raw, 1 << 16), 1 << 16));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(MIN_X);
        out.writeInt(MIN_Y);
        out.writeInt(MIN_Z);
        out.writeInt(SIZE_XZ);
        out.writeInt(SIZE_Y);
        long[] seen = seenChunks.toLongArray();
        out.writeInt(seen.length);
        for (long word : seen) out.writeLong(word);
        out.writeInt(solid.length);
        for (long word : solid) out.writeLong(word);
        out.flush();
        // Closing writes the gzip trailer; the stream is the scan file, written once.
        out.close();
    }

    public static CrystalHollowsTerrain read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                new GZIPInputStream(raw, 1 << 16), 1 << 16));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Not a Crystal Hollows scan");
        if (in.readInt() != MIN_X || in.readInt() != MIN_Y || in.readInt() != MIN_Z
                || in.readInt() != SIZE_XZ || in.readInt() != SIZE_Y) {
            throw new IOException("Crystal Hollows scan has different bounds");
        }
        CrystalHollowsTerrain terrain = new CrystalHollowsTerrain();
        int seenWords = in.readInt();
        if (seenWords < 0 || seenWords > (CHUNKS * CHUNKS + 63) / 64) throw new IOException("Bad scan coverage");
        long[] seen = new long[seenWords];
        for (int i = 0; i < seenWords; i++) seen[i] = in.readLong();
        terrain.seenChunks.or(BitSet.valueOf(seen));
        if (in.readInt() != terrain.solid.length) throw new IOException("Bad scan size");
        for (int i = 0; i < terrain.solid.length; i++) terrain.solid[i] = in.readLong();
        terrain.revision = 1;
        return terrain;
    }
}
