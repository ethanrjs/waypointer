package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StructureRelayProtocolTest {
    private static final long NOW = 1_800_000_000_000L;

    private static String message(String coordinates, long age) {
        return "{\"type\":\"sighting\",\"structure\":\"mines_of_divan\"," + coordinates
                + ",\"age\":" + age + ",\"at\":" + NOW + "}";
    }

    @Test
    void validatesPeerDataAndLobbyAge() {
        String valid = message("\"x\":600,\"y\":100,\"z\":300", 10_000);
        var received = StructureRelayProtocol.decode(valid, 10_000, NOW);
        assertEquals(1, received.size());
        assertEquals(SightingConfidence.SHARED_REMOTE, received.getFirst().confidence());
        assertNull(received.getFirst().remoteEvidence());
        assertFalse(StructureRelayProtocol.local(received.getFirst()));
        assertTrue(StructureRelayProtocol.decode(valid, 12_000, NOW).isEmpty());
        assertTrue(StructureRelayProtocol.decode(valid.replace("600", "600.1"), 10_000, NOW).isEmpty());
        assertTrue(StructureRelayProtocol.decode(valid.replace("600", "99999999999"), 10_000, NOW).isEmpty());
        assertTrue(StructureRelayProtocol.decode(valid.replace("mines_of_divan", "corleone"), 10_000, NOW).isEmpty());
        assertTrue(StructureRelayProtocol.decode("[", 10_000, NOW).isEmpty());
        assertTrue(StructureRelayProtocol.decode(" ".repeat(16_385), 10_000, NOW).isEmpty());
        assertEquals(1, StructureRelayProtocol.decode("{\"type\":\"snapshot\",\"sightings\":[" + valid + "]}", 10_000, NOW).size());
        assertFalse(StructureRelayProtocol.validServer("session-only"));
        assertTrue(StructureRelayProtocol.validServer("m14ap"));
        assertTrue(StructureRelayProtocol.validServer("mini123ab"));
        assertTrue(StructureRelayProtocol.validServer("m123a"));
    }

    private static StructureSighting remote(SightingConfidence evidence, int x, long at) {
        return new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                x, 100, 300, SightingConfidence.SHARED_REMOTE, "relay", at,
                java.util.List.of(), "", evidence);
    }

    @Test
    void preservesEvidenceWithoutGrantingLocalTrust() {
        String reporter = "45fdbbc1-e3dd-4a82-a898-1d43b48ebf07";
        for (var evidence : new SightingConfidence[] {
                SightingConfidence.NPC_CHAT, SightingConfidence.COMPASS, SightingConfidence.ENTITY}) {
            var local = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                    600, 100, 300, evidence, "local", NOW);
            var json = com.google.gson.JsonParser.parseString(StructureRelayProtocol.encode(local, 1)).getAsJsonObject();
            json.addProperty("at", NOW);
            json.addProperty("reporter", reporter);
            var decoded = StructureRelayProtocol.decode(json.toString(), 1, NOW).getFirst();
            assertEquals(evidence, decoded.remoteEvidence());
            assertEquals(evidence, decoded.withNote("test").remoteEvidence());
            assertEquals(SightingConfidence.SHARED_REMOTE, decoded.confidence());
            assertEquals("relay:" + reporter, decoded.source());
            assertFalse(StructureRelayProtocol.local(decoded));
            json.addProperty("evidence", "manual");
            assertTrue(StructureRelayProtocol.decode(json.toString(), 1, NOW).isEmpty());
            json.addProperty("evidence", "entity");
            json.addProperty("reporter", "pretend-player");
            assertTrue(StructureRelayProtocol.decode(json.toString(), 1, NOW).isEmpty());
            json.addProperty("evidence", 4);
            assertTrue(StructureRelayProtocol.decode(json.toString(), 1, NOW).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> remote(SightingConfidence.MANUAL, 600, NOW));
    }

    @Test
    void sharedConfirmedLocationBeatsCompassInEitherArrivalOrderAndRestoresLocalOnExpiry() throws Exception {
        var confirmed = remote(SightingConfidence.ENTITY, 600, NOW);
        var compass = new StructureSighting(confirmed.structure(), 620, 100, 300,
                SightingConfidence.COMPASS, "compass", NOW + 1);
        for (boolean remoteFirst : new boolean[] {true, false}) {
            var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
            lobby.merge(remoteFirst ? confirmed : compass);
            lobby.merge(remoteFirst ? compass : confirmed);
            assertEquals(confirmed, lobby.sightings().getFirst());
            assertEquals(java.util.List.of(compass), lobby.localSightings());
            var encode = CrystalHollowsStore.class.getDeclaredMethod("encodeLobby", CrystalHollowsLobbyState.class);
            encode.setAccessible(true);
            var saved = (com.google.gson.JsonObject) encode.invoke(null, lobby);
            var savedSighting = saved.getAsJsonArray("sightings").get(0).getAsJsonObject();
            assertEquals("COMPASS", savedSighting.get("confidence").getAsString());
            assertEquals(compass.x(), savedSighting.get("x").getAsInt());
            var identified = CrystalHollowsLobbyState.identify("m123a", 1, NOW, null, lobby);
            assertTrue(identified.clearRemoteSightings());
            assertEquals(compass, identified.sightings().getFirst());
            assertTrue(lobby.expireRemoteSightings(NOW + 1));
            assertEquals(compass, lobby.sightings().getFirst());
        }
    }

    @Test
    void peersCannotOverwriteDirectObservationsOrMoveEqualStrengthLocations() {
        for (var confidence : new SightingConfidence[] {SightingConfidence.ENTITY, SightingConfidence.MANUAL}) {
            var local = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                    600, 100, 300, confidence, "local", NOW);
            for (boolean remoteFirst : new boolean[] {true, false}) {
                var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
                var remote = remote(SightingConfidence.ENTITY, 620, NOW + 1);
                lobby.merge(remoteFirst ? remote : local);
                lobby.merge(remoteFirst ? local : remote);
                assertEquals(local, lobby.sightings().getFirst());
            }
        }
        var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
        var confirmed = remote(SightingConfidence.ENTITY, 600, NOW);
        lobby.merge(confirmed);
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(remote(SightingConfidence.COMPASS, 620, NOW + 1)));
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(remote(SightingConfidence.ENTITY, 620, NOW + 2)));
        assertEquals(confirmed, lobby.sightings().getFirst());
        var refreshed = remote(SightingConfidence.ENTITY, 600, NOW + 3);
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED, lobby.merge(refreshed));
        assertEquals(refreshed, lobby.sightings().getFirst());
        lobby.clearSightings();
        var compass = new StructureSighting(confirmed.structure(), 620, 100, 300,
                SightingConfidence.COMPASS, "local", NOW);
        lobby.merge(remote(SightingConfidence.COMPASS, 600, NOW));
        lobby.merge(compass);
        assertEquals(compass, lobby.sightings().getFirst());
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED,
                lobby.merge(remote(SightingConfidence.COMPASS, 600, NOW + 1)));
    }

    @Test
    void localSightingsWinAndOptOutKeepsThem() {
        var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
        var remote = StructureRelayProtocol.decode(message("\"x\":600,\"y\":100,\"z\":300", 1), 1, NOW).getFirst();
        lobby.merge(remote);
        lobby.merge(remote);
        assertEquals(1, lobby.sightings().size());
        var refreshed = new StructureSighting(remote.structure(), remote.x(), remote.y(), remote.z(),
                SightingConfidence.SHARED_REMOTE, "relay", NOW + 1);
        assertEquals(CrystalHollowsLobbyState.MergeResult.REFINED, lobby.merge(refreshed));
        assertFalse(lobby.expireRemoteSightings(NOW + 1));
        var local = new StructureSighting(remote.structure(), 601, 100, 300,
                SightingConfidence.ENTITY, "entity", NOW + 1);
        lobby.merge(local);
        assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, lobby.merge(remote));
        assertFalse(lobby.clearRemoteSightings());
        assertEquals(local, lobby.sightings().getFirst());
        lobby.clearSightings();
        lobby.merge(remote);
        assertTrue(lobby.expireRemoteSightings(NOW + 1));
        assertTrue(lobby.sightings().isEmpty());
    }

    @Test
    void relayUpgradesPreserveLocalEvidenceAcrossExpiryIdentificationAndSaving() throws Exception {
        for (var confidence : new SightingConfidence[] {
                SightingConfidence.ROUGH_AREA, SightingConfidence.SHARED_CHAT}) {
            for (boolean remoteFirst : new boolean[] {false, true}) {
                var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
                var local = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                        580, 100, 300, confidence, "local", NOW);
                var remote = StructureRelayProtocol.decode(message("\"x\":600,\"y\":100,\"z\":300", 1), 1, NOW).getFirst();
                lobby.merge(remoteFirst ? remote : local);
                lobby.merge(remoteFirst ? local : remote);
                assertEquals(remote, lobby.sightings().getFirst());
                assertEquals(java.util.List.of(local), lobby.localSightings());
                assertEquals(CrystalHollowsLobbyState.MergeResult.IGNORED, lobby.merge(local));
                var encode = CrystalHollowsStore.class.getDeclaredMethod("encodeLobby", CrystalHollowsLobbyState.class);
                encode.setAccessible(true);
                var json = (com.google.gson.JsonObject) encode.invoke(null, lobby);
                assertEquals(confidence.name(), json.getAsJsonArray("sightings").get(0)
                        .getAsJsonObject().get("confidence").getAsString());
                for (boolean restored : new boolean[] {false, true}) {
                    var identified = CrystalHollowsLobbyState.identify("m123a", 1, NOW,
                            restored ? lobby : null, restored ? null : lobby);
                    assertTrue(identified.expireRemoteSightings(NOW + 1));
                    assertEquals(java.util.List.of(local), identified.sightings());
                }
                assertTrue(lobby.clearRemoteSightings());
                assertEquals(java.util.List.of(local), lobby.sightings());
            }
        }
    }

    @Test
    void refreshedRemoteRetainsFallbackAndStrongerLocalReplacesIt() {
        var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
        var rough = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO,
                500, 100, 300, SightingConfidence.ROUGH_AREA, "local", NOW);
        var remote = new StructureSighting(rough.structure(), 510, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay", NOW);
        var refreshed = new StructureSighting(rough.structure(), 520, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay", NOW + 1);
        lobby.merge(rough);
        lobby.merge(remote);
        lobby.merge(refreshed);
        assertEquals(java.util.List.of(rough), lobby.localSightings());
        var local = new StructureSighting(rough.structure(), 521, 100, 300,
                SightingConfidence.ENTITY, "entity", NOW + 2);
        lobby.merge(local);
        assertFalse(lobby.clearRemoteSightings());
        assertEquals(java.util.List.of(local), lobby.sightings());
        assertEquals(java.util.List.of(local), lobby.localSightings());
    }

    @Test
    void localRefinementMatchesFallbackWhenRemotePositionMovedAcrossInstanceRadius() {
        var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
        var rough = new StructureSighting(CrystalHollowsStructure.FAIRY_GROTTO,
                500, 100, 300, SightingConfidence.ROUGH_AREA, "local", NOW);
        lobby.merge(rough);
        lobby.merge(new StructureSighting(rough.structure(), 559, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay", NOW));
        var refined = new StructureSighting(rough.structure(), 498, 100, 300,
                SightingConfidence.ROUGH_AREA, "local", NOW + 1);
        lobby.merge(refined);
        assertEquals(1, lobby.sightings().size());
        assertTrue(lobby.clearRemoteSightings());
        assertEquals(java.util.List.of(refined), lobby.sightings());
    }

    @Test
    void removingRemoteDisplayAlsoRemovesItsLocalFallback() {
        for (int removal = 0; removal < 3; removal++) {
            var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
            var local = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                    580, 100, 300, SightingConfidence.ROUGH_AREA, "local", NOW);
            lobby.merge(local);
            lobby.merge(StructureRelayProtocol.decode(message("\"x\":600,\"y\":100,\"z\":300", 1), 1, NOW).getFirst());
            if (removal == 0) lobby.removeStructure(local.structure());
            else if (removal == 1) lobby.removeSighting(new CrystalHollowsSightingSelector.Selection(local.structure(), 1));
            else lobby.clearSightings();
            lobby.clearRemoteSightings();
            assertTrue(lobby.sightings().isEmpty());
            assertTrue(lobby.localSightings().isEmpty());
        }
    }

    @Test
    void remoteSightingsStayOutOfSavedData() throws Exception {
        var lobby = new CrystalHollowsLobbyState("m123a", NOW, 1);
        lobby.merge(StructureRelayProtocol.decode(message("\"x\":600,\"y\":100,\"z\":300", 1), 1, NOW).getFirst());
        var encode = CrystalHollowsStore.class.getDeclaredMethod("encodeLobby", CrystalHollowsLobbyState.class);
        encode.setAccessible(true);
        var json = (com.google.gson.JsonObject) encode.invoke(null, lobby);
        assertTrue(json.getAsJsonArray("sightings").isEmpty());
    }
}
