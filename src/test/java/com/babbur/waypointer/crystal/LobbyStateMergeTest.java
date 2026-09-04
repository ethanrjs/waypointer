package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
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

    @Test
    void removesOnlyTheSelectedMultiInstance() {
        CrystalHollowsLobbyState lobby = lobby();
        lobby.merge(sighting(CrystalHollowsStructure.KEY_GUARDIAN, 300, 100, 300,
                SightingConfidence.ENTITY, 100));
        lobby.merge(sighting(CrystalHollowsStructure.KEY_GUARDIAN, 500, 100, 500,
                SightingConfidence.ENTITY, 101));

        lobby.removeSighting(new CrystalHollowsSightingSelector.Selection(
                CrystalHollowsStructure.KEY_GUARDIAN, 2));

        assertEquals(1, lobby.sightings().size());
        assertEquals(300, lobby.sightings().getFirst().x());
    }

    @Test
    void instanceRemovalIsExactEvenWhenSightingsCompareEqual() {
        CrystalHollowsLobbyState lobby = lobby();
        StructureSighting first = sighting(
                CrystalHollowsStructure.WISHING_TARGET, 400, 100, 400,
                SightingConfidence.COMPASS, 100);
        StructureSighting second = sighting(
                CrystalHollowsStructure.WISHING_TARGET, 400, 100, 400,
                SightingConfidence.COMPASS, 100);
        lobby.merge(first);
        lobby.merge(second);

        lobby.removeSighting(new CrystalHollowsSightingSelector.Selection(
                CrystalHollowsStructure.WISHING_TARGET, 2));

        assertEquals(1, lobby.sightings().size());
        assertSame(first, lobby.sightings().getFirst());
    }

    @Test
    void retainsSessionObservationsWhenLobbyIdentityArrives() {
        CrystalHollowsLobbyState restored = new CrystalHollowsLobbyState("m7A", 100, 6);
        restored.merge(sighting(CrystalHollowsStructure.ODAWA, 300, 100, 300,
                SightingConfidence.SHARED_CHAT, 110));
        restored.setCrystal(Crystal.JADE, CrystalState.MISSING);
        CrystalHollowsLobbyState session = new CrystalHollowsLobbyState("session-only", 50, -1);
        session.merge(sighting(CrystalHollowsStructure.ODAWA, 301, 100, 301,
                SightingConfidence.ENTITY, 120));
        session.merge(sighting(CrystalHollowsStructure.FAIRY_GROTTO, 500, 100, 500,
                SightingConfidence.SHARED_CHAT, 121));
        session.setCrystal(Crystal.JADE, CrystalState.COLLECTED);
        session.setDivanCentre(new CrystalHollowsPosition(501, 101, 501));

        CrystalHollowsLobbyState identified = CrystalHollowsLobbyState.identify(
                "m7A", 7, 200, restored, session);

        assertEquals("m7A", identified.serverId());
        assertEquals(50, identified.firstSeenMillis());
        assertEquals(200, identified.lastSeenMillis());
        assertEquals(7, identified.lastKnownDay());
        assertEquals(2, identified.sightings().size());
        assertEquals(SightingConfidence.ENTITY, identified.sightings().getFirst().confidence());
        assertEquals(CrystalState.COLLECTED, identified.crystals().get(Crystal.JADE));
        assertEquals(new CrystalHollowsPosition(501, 101, 501), identified.divanCentre());
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
