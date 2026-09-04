package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LobbyStateMergeTest {

    @Test
    void addsUpgradesRefinesAndIgnoresByConfidence() {
        CrystalHollowsLobbyState lobby = lobby();
        assertEquals(CrystalHollowsLobbyState.MergeResult.ADDED,
                lobby.merge(sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 300, 80, 300,
                        SightingConfidence.ROUGH_AREA, 100)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED,
                lobby.merge(sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 310, 80, 310,
                        SightingConfidence.ROUGH_AREA, 101)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.UPGRADED,
                lobby.merge(sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 311, 80, 311,
                        SightingConfidence.SHARED_CHAT, 102)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 320, 80, 320,
                        SightingConfidence.ROUGH_AREA, 103)));
        assertEquals(311, lobby.sightings().getFirst().x());
    }

    @Test
    void equalNonRoughConfidenceNeedsNewerAndMoreThanTwoBlocks() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.ODAWA, 300, 100, 300,
                SightingConfidence.NPC_CHAT, 100));
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(sighting(CrystalHollowsStructure.ODAWA, 302, 100, 300,
                        SightingConfidence.NPC_CHAT, 101)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(sighting(CrystalHollowsStructure.ODAWA, 310, 100, 300,
                        SightingConfidence.NPC_CHAT, 99)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED,
                lobby.merge(sighting(CrystalHollowsStructure.ODAWA, 310, 100, 300,
                        SightingConfidence.NPC_CHAT, 102)));
    }

    @Test
    void manualSightingsAreProtected() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.KHAZAD_DUM, 400, 60, 400,
                SightingConfidence.MANUAL, 100));
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(sighting(CrystalHollowsStructure.KHAZAD_DUM, 500, 60, 500,
                        SightingConfidence.ENTITY, 200)));
        assertEquals(400, lobby.sightings().getFirst().x());
    }

    @Test
    void multiInstanceStructuresMergeOnlyWithinSixtyBlocks() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.FAIRY_GROTTO, 300, 100, 300,
                SightingConfidence.SHARED_CHAT, 100));
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED,
                lobby.merge(sighting(CrystalHollowsStructure.FAIRY_GROTTO, 350, 100, 300,
                        SightingConfidence.SHARED_CHAT, 101)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.ADDED,
                lobby.merge(sighting(CrystalHollowsStructure.FAIRY_GROTTO, 411, 100, 300,
                        SightingConfidence.SHARED_CHAT, 102)));
        assertEquals(2, lobby.sightings().size());
    }

    @Test
    void compassCanConfirmButNotMoveEntityAnchor() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.KING_YOLKAR, 377, 87, 550,
                SightingConfidence.ENTITY, 100));
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED,
                lobby.merge(sighting(CrystalHollowsStructure.KING_YOLKAR, 380, 87, 550,
                        SightingConfidence.COMPASS, 101)));
        StructureSighting sighting = lobby.sightings().getFirst();
        assertEquals(377, sighting.x());
        assertEquals("confirmed by compass", sighting.note());
    }

    @Test
    void concreteCandidateRemovesNearbyAmbiguousTarget() {
        CrystalHollowsLobbyState lobby = lobby();
        StructureSighting ambiguous = new StructureSighting(
                CrystalHollowsStructure.WISHING_TARGET, 343, 72, 424,
                SightingConfidence.COMPASS, "compass", 100,
                List.of(CrystalHollowsStructure.JUNGLE_TEMPLE,
                        CrystalHollowsStructure.KHAZAD_DUM), "");
        lobby.merge(ambiguous);
        lobby.merge(ambiguous);
        assertEquals(2, lobby.sightings().size(), "ambiguous results are always independent");
        lobby.merge(sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 344, 72, 424,
                SightingConfidence.ENTITY, 101));
        assertEquals(1, lobby.sightings().size());
        assertEquals(CrystalHollowsStructure.JUNGLE_TEMPLE,
                lobby.sightings().getFirst().structure());
    }

    @Test
    void remoteShareSeamOrdersBetweenChatAndNpc() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.XALX, 300, 100, 300,
                SightingConfidence.SHARED_CHAT, 100));
        assertEquals(CrystalHollowsLobbyState.MergeResult.UPGRADED,
                lobby.merge(sighting(CrystalHollowsStructure.XALX, 301, 100, 300,
                        SightingConfidence.SHARED_REMOTE, 101)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.UPGRADED,
                lobby.merge(sighting(CrystalHollowsStructure.XALX, 302, 100, 300,
                        SightingConfidence.NPC_CHAT, 102)));
    }

    private static CrystalHollowsLobbyState lobby() {
        return new CrystalHollowsLobbyState("m1A", 0, 0);
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure,
                                               int x, int y, int z,
                                               SightingConfidence confidence, long time) {
        return new StructureSighting(structure, x, y, z, confidence, "test", time);
    }
}
