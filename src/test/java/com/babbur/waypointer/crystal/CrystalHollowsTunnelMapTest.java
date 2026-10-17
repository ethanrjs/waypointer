package com.babbur.waypointer.crystal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrystalHollowsTunnelMapTest {

    @Test
    void tunnelsNearThePlayersHeightAreBrightest() {
        int here = CrystalHollowsTunnelMap.alpha(1 << 7, 16, 7);
        int oneBandAway = CrystalHollowsTunnelMap.alpha(1 << 8, 16, 7);
        int farBelow = CrystalHollowsTunnelMap.alpha(1 << 1, 16, 7);
        assertTrue(here > oneBandAway && oneBandAway > farBelow && farBelow > 0);
        assertEquals(here, CrystalHollowsTunnelMap.alpha(1 << 1 | 1 << 7, 16, 7), "the nearest band counts");
        assertEquals(0, CrystalHollowsTunnelMap.alpha(0, 16, 7));
        assertTrue(CrystalHollowsTunnelMap.alpha(1 << 7, 2, 7) < here, "thin edges are fainter");
    }

    @Test
    void theShippedMapLoads() throws Exception {
        try (var in = CrystalHollowsTunnelMap.class.getResourceAsStream("/assets/waypointer/crystal_hollows/tunnels.bin.gz")) {
            assertNotNull(in, "tools/crystal_hollows/build_tunnels.py output is packaged");
            assertNotNull(CrystalHollowsTunnelMap.read(in));
        }
    }
}
