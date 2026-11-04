package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LobbyStateMergeTest {

    @Test
    void sidebarCorrectionPreservesPreciseEvidenceManualAndSeparateGrottos() {
        var structure = CrystalHollowsStructure.FAIRY_GROTTO;
        var sidebar = new StructureSighting(structure, 246, 104, 625,
                SightingConfidence.ROUGH_AREA, "sidebar", 2);
        for (var confidence : new SightingConfidence[] {SightingConfidence.ENTITY,
                SightingConfidence.COMPASS, SightingConfidence.MANUAL}) {
            var lobby = lobby();
            var precise = new StructureSighting(structure, 253, 107, 618, confidence, "local", 1);
            lobby.merge(precise);
            lobby.merge(sidebar);
            assertEquals(List.of(precise), lobby.sightings());
        }
        var lobby = lobby();
        var remote = new StructureSighting(structure, 253, 107, 618,
                SightingConfidence.SHARED_REMOTE, "relay", 1, List.of(), "", SightingConfidence.ENTITY);
        lobby.merge(remote);
        lobby.merge(sidebar);
        assertEquals(List.of(remote), lobby.sightings());
        assertEquals(List.of(sidebar), lobby.localSightings());
        var separate = new StructureSighting(structure, 500, 104, 625,
                SightingConfidence.ROUGH_AREA, "sidebar", 2);
        lobby.merge(separate);
        assertEquals(List.of(remote, separate), lobby.sightings());
    }

    @Test
    void walkingIntoTheAreaKeepsTheSharedWaypointAndHoldsOurEstimateBehindIt() {
        var structure = CrystalHollowsStructure.MINES_OF_DIVAN;
        var shared = new StructureSighting(structure, 720, 100, 300,
                SightingConfidence.SHARED_REMOTE, "skyblocker", 1);
        var area = new StructureSighting(structure, 690, 110, 330,
                SightingConfidence.ROUGH_AREA, "sidebar", 2);

        var lobby = lobby();
        lobby.merge(shared);
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED, lobby.merge(area));
        assertEquals(List.of(shared), lobby.sightings(), "the waypoint the player followed stays put");
        assertEquals(List.of(area), lobby.localSightings());
        var walked = new StructureSighting(structure, 700, 110, 320, SightingConfidence.ROUGH_AREA, "sidebar", 3);
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED, lobby.merge(walked));
        assertEquals(List.of(shared), lobby.sightings());
        assertEquals(List.of(walked), lobby.localSightings());
        assertTrue(lobby.rejectSkyblocker(shared));
        assertEquals(List.of(walked), lobby.sightings(), "our estimate returns if the share is proven wrong");
        assertTrue(lobby.sightings().getFirst().approximate());

        var arrivedFirst = lobby();
        arrivedFirst.merge(area);
        assertTrue(arrivedFirst.sightings().getFirst().approximate());
        assertEquals(CrystalHollowsLobbyState.MergeResult.UPGRADED, arrivedFirst.merge(shared));
        assertEquals(List.of(shared), arrivedFirst.sightings());
        assertEquals(List.of(area), arrivedFirst.localSightings());
        assertTrue(arrivedFirst.clearRemoteSightings(SkyblockerStructureProtocol.SOURCE));
        assertEquals(List.of(area), arrivedFirst.sightings());
    }

    @Test
    void waypointerDetectionsBeatSkyblockerSharesAndAreasNeverBeatEither() {
        var structure = CrystalHollowsStructure.JUNGLE_TEMPLE;
        var skyblocker = new StructureSighting(structure, 300, 100, 300,
                SightingConfidence.SHARED_REMOTE, SkyblockerStructureProtocol.SOURCE, 1);
        var area = new StructureSighting(structure, 330, 100, 320, SightingConfidence.ROUGH_AREA, "sidebar", 3);
        var farArea = new StructureSighting(structure, 600, 100, 600, SightingConfidence.ROUGH_AREA, "sidebar", 3);
        List<StructureSighting> waypointer = new ArrayList<>();
        for (var local : List.of(SightingConfidence.ENTITY, SightingConfidence.COMPASS, SightingConfidence.NPC_CHAT)) {
            waypointer.add(new StructureSighting(structure, 310, 100, 310, local, "local", 2));
        }
        for (var evidence : List.of(SightingConfidence.ENTITY, SightingConfidence.COMPASS, SightingConfidence.NPC_CHAT)) {
            waypointer.add(new StructureSighting(structure, 310, 100, 310, SightingConfidence.SHARED_REMOTE,
                    "relay:45fdbbc1-e3dd-4a82-a898-1d43b48ebf07", 2, List.of(), "", evidence));
        }
        for (var detection : waypointer) {
            for (var order : List.of(List.of(skyblocker, detection), List.of(detection, skyblocker))) {
                var lobby = lobby();
                order.forEach(lobby::merge);
                assertEquals(List.of(detection), lobby.sightings(), detection + " after " + order.getFirst());
                lobby.merge(area);
                lobby.merge(farArea);
                assertEquals(List.of(detection), lobby.sightings(), "an area estimate never replaces " + detection);
            }
        }
        for (var order : List.of(List.of(skyblocker, area), List.of(area, skyblocker))) {
            var lobby = lobby();
            order.forEach(lobby::merge);
            assertEquals(List.of(skyblocker), lobby.sightings());
        }
    }

    @Test
    void aShareAtTheFarSideOfALargeAreaIsStillTheSameStructure() {
        var structure = CrystalHollowsStructure.LOST_PRECURSOR_CITY;
        var shared = new StructureSighting(structure, 600, 110, 600,
                SightingConfidence.SHARED_REMOTE, "skyblocker", 1);
        var entered = new StructureSighting(structure, 720, 100, 700,
                SightingConfidence.ROUGH_AREA, "sidebar", 2);
        var lobby = lobby();
        lobby.merge(shared);
        lobby.merge(entered);
        assertEquals(List.of(shared), lobby.sightings());
    }

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
