package com.babbur.waypointer.crystal;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines scans from different lobbies into the Hollows' own terrain. Every lobby has the same
 * rock and tunnels, but its own random structures and its own player-mined holes, so a block is
 * rock when most of the scans that covered it saw rock. Ties count as rock, since mining (the
 * most common difference) only ever opens blocks.
 */
public final class CrystalHollowsTerrainVote {
    /** Lobbies each area needs before structures and mined holes reliably vote out. */
    public static final int LOBBIES_NEEDED = 3;

    public record Result(CrystalHollowsTerrain terrain, int[] chunkScans) {
        public int scans(int chunkX, int chunkZ) {
            if (!CrystalHollowsTerrain.chunkInside(chunkX, chunkZ)) return 0;
            return chunkScans[(chunkZ - CrystalHollowsTerrain.MIN_CHUNK) * CrystalHollowsTerrain.CHUNKS
                    + (chunkX - CrystalHollowsTerrain.MIN_CHUNK)];
        }
    }

    private CrystalHollowsTerrainVote() {}

    public static Result vote(List<CrystalHollowsTerrain> scans) {
        CrystalHollowsTerrain merged = new CrystalHollowsTerrain();
        int[] counts = new int[CrystalHollowsTerrain.totalChunks()];
        List<CrystalHollowsTerrain> covering = new ArrayList<>(scans.size());
        for (int cz = CrystalHollowsTerrain.MIN_CHUNK; cz <= CrystalHollowsTerrain.MAX_CHUNK; cz++) {
            for (int cx = CrystalHollowsTerrain.MIN_CHUNK; cx <= CrystalHollowsTerrain.MAX_CHUNK; cx++) {
                covering.clear();
                for (CrystalHollowsTerrain scan : scans) {
                    if (scan.chunkSeen(cx, cz)) covering.add(scan);
                }
                if (covering.isEmpty()) continue;
                counts[(cz - CrystalHollowsTerrain.MIN_CHUNK) * CrystalHollowsTerrain.CHUNKS
                        + (cx - CrystalHollowsTerrain.MIN_CHUNK)] = covering.size();
                if (covering.size() == 1) merged.copyChunk(covering.getFirst(), cx, cz);
                else voteChunk(merged, covering, cx, cz);
            }
        }
        return new Result(merged, counts);
    }

    private static void voteChunk(CrystalHollowsTerrain merged, List<CrystalHollowsTerrain> covering, int cx, int cz) {
        int x0 = Math.max(CrystalHollowsTerrain.MIN_X, cx * 16);
        int x1 = Math.min(CrystalHollowsTerrain.MIN_X + CrystalHollowsTerrain.SIZE_XZ - 1, cx * 16 + 15);
        int z0 = Math.max(CrystalHollowsTerrain.MIN_Z, cz * 16);
        int z1 = Math.min(CrystalHollowsTerrain.MIN_Z + CrystalHollowsTerrain.SIZE_XZ - 1, cz * 16 + 15);
        int needed = (covering.size() + 1) / 2;
        for (int y = CrystalHollowsTerrain.MIN_Y; y <= CrystalHollowsTerrain.MAX_Y; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int solid = 0;
                    for (CrystalHollowsTerrain scan : covering) {
                        if (scan.solid(x, y, z) && ++solid >= needed) break;
                    }
                    if (solid >= needed) merged.setSolid(x, y, z, true);
                }
            }
        }
        merged.markChunkSeen(cx, cz);
    }
}
