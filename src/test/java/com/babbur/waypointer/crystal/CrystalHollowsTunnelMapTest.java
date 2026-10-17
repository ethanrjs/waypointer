package com.babbur.waypointer.crystal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrystalHollowsTunnelMapTest {

    @Test
    void onlyTunnelsNearThePlayersHeightShow() {
        int here = CrystalHollowsTunnelMap.alpha(1 << 7, 16, 7);
        int oneBandAway = CrystalHollowsTunnelMap.alpha(1 << 8, 16, 7);
        assertTrue(here > oneBandAway && oneBandAway > 0);
        assertEquals(0, CrystalHollowsTunnelMap.alpha(1 << 1, 16, 7), "far below is hidden");
        assertEquals(here, CrystalHollowsTunnelMap.alpha(1 << 1 | 1 << 7, 16, 7), "the nearest band counts");
        assertEquals(0, CrystalHollowsTunnelMap.alpha(0, 16, 7));
        assertTrue(CrystalHollowsTunnelMap.alpha(1 << 7, 2, 7) < here, "thin edges are fainter");
    }

    @Test
    void tunnelsTakeTheirAreasColor() {
        int jungle = CrystalHollowsTunnelMap.tint(300, 300, 100);
        int mithril = CrystalHollowsTunnelMap.tint(700, 300, 100);
        int goblin = CrystalHollowsTunnelMap.tint(300, 700, 100);
        int precursor = CrystalHollowsTunnelMap.tint(700, 700, 100);
        assertEquals(4, java.util.Set.of(jungle, mithril, goblin, precursor).size());
        int magma = CrystalHollowsTunnelMap.tint(300, 300, 50);
        assertEquals(magma, CrystalHollowsTunnelMap.tint(700, 700, 40), "Magma Fields are one area");
        assertNotEquals(jungle, magma);
    }

    @Test
    void theShippedMapLoads() throws Exception {
        try (var in = CrystalHollowsTunnelMap.class.getResourceAsStream("/assets/waypointer/crystal_hollows/tunnels.bin.gz")) {
            assertNotNull(in, "tools/crystal_hollows/build_tunnels.py output is packaged");
            assertNotNull(CrystalHollowsTunnelMap.read(in));
        }
    }
}
