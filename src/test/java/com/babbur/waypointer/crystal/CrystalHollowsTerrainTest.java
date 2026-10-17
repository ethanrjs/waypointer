package com.babbur.waypointer.crystal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CrystalHollowsTerrainTest {

    @Test
    void storesSolidBlocksInsideTheHollowsOnly() {
        CrystalHollowsTerrain terrain = new CrystalHollowsTerrain();
        terrain.setSolid(300, 100, 400, true);
        assertTrue(terrain.solid(300, 100, 400));
        assertFalse(terrain.solid(301, 100, 400));
        assertTrue(terrain.solid(100, 100, 100), "outside the Hollows counts as solid");
        terrain.setSolid(300, 100, 400, false);
        assertFalse(terrain.solid(300, 100, 400));
    }

    @Test
    void pointsAtTheNearestUnseenChunk() {
        CrystalHollowsTerrain terrain = new CrystalHollowsTerrain();
        int cx = Math.floorDiv(512, 16), cz = Math.floorDiv(512, 16);
        terrain.markChunkSeen(cx, cz);
        assertArrayEquals(new int[]{cx + 1, cz}, terrain.nearestUnseenChunk(cx * 16 + 15.5, cz * 16 + 8));
        for (int z = CrystalHollowsTerrain.MIN_CHUNK; z <= CrystalHollowsTerrain.MAX_CHUNK; z++) {
            for (int x = CrystalHollowsTerrain.MIN_CHUNK; x <= CrystalHollowsTerrain.MAX_CHUNK; x++) {
                terrain.markChunkSeen(x, z);
            }
        }
        assertNull(terrain.nearestUnseenChunk(512, 512));
        assertEquals(CrystalHollowsTerrain.totalChunks(), terrain.seenChunkCount());
    }

    @Test
    void walkableNeedsTwoOpenBlocksNearTheGivenHeight() {
        CrystalHollowsTerrain terrain = new CrystalHollowsTerrain();
        for (int y = CrystalHollowsTerrain.MIN_Y; y <= CrystalHollowsTerrain.MAX_Y; y++) terrain.setSolid(400, y, 400, true);
        assertFalse(terrain.walkableNear(400, 400, 100, 2));
        terrain.setSolid(400, 101, 400, false);
        assertFalse(terrain.walkableNear(400, 400, 100, 2), "one open block is not enough");
        terrain.setSolid(400, 102, 400, false);
        assertTrue(terrain.walkableNear(400, 400, 100, 2));
        assertFalse(terrain.walkableNear(400, 400, 110, 2), "too far above");
    }

    @Test
    void roundTripsThroughAFileAndFillsGapsFromOtherScans() throws IOException {
        CrystalHollowsTerrain first = new CrystalHollowsTerrain();
        first.setSolid(210, 40, 220, true);
        first.markChunkSeen(Math.floorDiv(210, 16), Math.floorDiv(220, 16));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        first.write(bytes);
        CrystalHollowsTerrain read = CrystalHollowsTerrain.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(read.solid(210, 40, 220));
        assertTrue(read.chunkSeen(Math.floorDiv(210, 16), Math.floorDiv(220, 16)));
        assertTrue(bytes.size() < 200_000, "a mostly empty scan compresses well");

        CrystalHollowsTerrain merged = new CrystalHollowsTerrain();
        merged.fillFrom(read);
        assertTrue(merged.solid(210, 40, 220));
        assertEquals(1, merged.seenChunkCount());
        assertThrows(IOException.class, () -> CrystalHollowsTerrain.read(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test
    void lobbiesVoteOutStructuresAndMinedHoles() {
        int cx = Math.floorDiv(400, 16), cz = Math.floorDiv(400, 16);
        CrystalHollowsTerrain a = solidChunk(cx, cz), b = solidChunk(cx, cz), c = solidChunk(cx, cz);
        a.setSolid(400, 100, 400, false);                  // mined in one lobby only
        for (CrystalHollowsTerrain scan : new CrystalHollowsTerrain[]{a, b, c}) {
            scan.setSolid(401, 100, 400, false);           // a natural tunnel in every lobby
        }
        b.setSolid(402, 100, 400, false);
        c.setSolid(402, 100, 400, false);                  // a structure's room in two of three
        var vote = CrystalHollowsTerrainVote.vote(java.util.List.of(a, b, c));
        assertTrue(vote.terrain().solid(400, 100, 400), "a hole one lobby dug is rock");
        assertFalse(vote.terrain().solid(401, 100, 400), "the shared tunnel stays open");
        assertFalse(vote.terrain().solid(402, 100, 400), "majority wins");
        assertEquals(3, vote.scans(cx, cz));
        assertEquals(0, vote.scans(cx + 1, cz));

        var tie = CrystalHollowsTerrainVote.vote(java.util.List.of(a, b));
        assertTrue(tie.terrain().solid(400, 100, 400), "a tie counts as rock");
    }

    private static CrystalHollowsTerrain solidChunk(int cx, int cz) {
        CrystalHollowsTerrain terrain = new CrystalHollowsTerrain();
        for (int y = 95; y <= 105; y++) {
            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                for (int x = cx * 16; x < cx * 16 + 16; x++) terrain.setSolid(x, y, z, true);
            }
        }
        terrain.markChunkSeen(cx, cz);
        return terrain;
    }
}
