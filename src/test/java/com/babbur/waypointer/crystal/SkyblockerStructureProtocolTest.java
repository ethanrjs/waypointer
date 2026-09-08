package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SkyblockerStructureProtocolTest {
    private static final long NOW = 1_788_800_000_000L;
    private static final String POINT = "{\"name\":\"JUNGLE_TEMPLE\",\"coordinates\":[300,100,300]}";

    private static String response(String message) {
        return "{\"type\":\"response\",\"service\":\"CRYSTAL_WAYPOINTS\",\"serverId\":\"mini192Q\",\"message\":" + message + "}";
    }

    @Test void subscriptionUsesEpochCloseTimeAndPreservesCase() {
        var json = JsonParser.parseString(SkyblockerStructureProtocol.subscribe("mini192Q", 11, NOW)).getAsJsonObject();
        assertEquals("mini192Q", json.get("serverId").getAsString());
        assertEquals(NOW / 1000 + 15 * 1200, json.getAsJsonObject("message").get("timestamp").getAsLong());
        assertThrows(IllegalArgumentException.class, () -> SkyblockerStructureProtocol.subscribe("mini192Q", -1, NOW));
        assertThrows(IllegalArgumentException.class, () -> SkyblockerStructureProtocol.subscribe("mini192Q", 26, NOW));
        assertThrows(IllegalArgumentException.class, () -> SkyblockerStructureProtocol.subscribe("not a server", 11, NOW));
    }

    @Test void liveAndSnapshotDataRemainRemoteAndNeverPublishable() {
        for (String payload : new String[]{response(POINT),
                "{\"type\":\"initialMessage\",\"service\":\"CRYSTAL_WAYPOINTS\",\"message\":[" + POINT + "]}"}) {
            var points = SkyblockerStructureProtocol.decode(payload, "mini192Q", NOW);
            assertEquals(1, points.size());
            var point = points.getFirst();
            assertEquals(SightingConfidence.SHARED_REMOTE, point.confidence());
            assertNull(point.remoteEvidence());
            assertEquals("skyblocker", point.source());
            assertFalse(StructureRelayProtocol.local(point));
            assertThrows(IllegalArgumentException.class,
                    () -> SkyblockerStructureProtocol.publish("mini192Q", point, NOW - 1000, NOW));
        }
    }

    @Test void rejectsOtherLobbyOtherServiceMalformedAndUnboundedData() {
        assertTrue(SkyblockerStructureProtocol.decode(response(POINT), "mini193Q", NOW).isEmpty());
        assertTrue(SkyblockerStructureProtocol.decode(response(POINT).replace("CRYSTAL_WAYPOINTS", "DUNGEON_SECRETS"), "mini192Q", NOW).isEmpty());
        for (String bad : new String[]{"[1,2]", "[300.1,100,300]", "[300,100,9999]", "[\"300\",100,300]"}) {
            assertTrue(SkyblockerStructureProtocol.decode(response(POINT.replace("[300,100,300]", bad)), "mini192Q", NOW).isEmpty());
        }
        assertTrue(SkyblockerStructureProtocol.decode("x".repeat(16385), "mini192Q", NOW).isEmpty());
        assertTrue(SkyblockerStructureProtocol.decode("{", "mini192Q", NOW).isEmpty());
    }

    @Test void unsupportedCategoriesDoNotDiscardUsableSnapshot() {
        String payload = "{\"type\":\"initialMessage\",\"service\":\"CRYSTAL_WAYPOINTS\",\"message\":["
                + POINT + "," + POINT.replace("JUNGLE_TEMPLE", "CORLEONE") + ","
                + POINT.replace("JUNGLE_TEMPLE", "UNKNOWN") + ",null,{},"
                + POINT.replace("[300,100,300]", "[1,2]") + "]}";
        assertEquals(1, SkyblockerStructureProtocol.decode(payload, "mini192Q", NOW).size());
    }

    @Test void onlyFreshLocalEvidenceCanBePublished() {
        var local = new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 300, 100, 300,
                SightingConfidence.ENTITY, "entity", NOW);
        var message = JsonParser.parseString(SkyblockerStructureProtocol.publish("mini192Q", local, NOW, NOW))
                .getAsJsonObject().getAsJsonObject("message");
        assertEquals("JUNGLE_TEMPLE", message.get("name").getAsString());
        assertEquals("[300,100,300]", message.get("coordinates").toString());
        assertThrows(IllegalArgumentException.class, () -> SkyblockerStructureProtocol.publish("mini192Q", local, NOW + 1, NOW));
        assertThrows(IllegalArgumentException.class, () -> SkyblockerStructureProtocol.publish("mini192Q", local, NOW, NOW + 1_800_001));
    }

    @Test void optOutRemovesOnlyThisProviderAndRestoresLocalFallback() {
        var lobby = new CrystalHollowsLobbyState("mini192Q", NOW, 1);
        var rough = new StructureSighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 300, 100, 300,
                SightingConfidence.ROUGH_AREA, "sidebar", NOW - 1);
        var peer = new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN, 600, 100, 300,
                SightingConfidence.SHARED_REMOTE, "relay", NOW);
        lobby.merge(rough);
        lobby.merge(SkyblockerStructureProtocol.decode(response(POINT), "mini192Q", NOW).getFirst());
        lobby.merge(peer);
        assertTrue(lobby.clearRemoteSightings("skyblocker"));
        assertTrue(lobby.sightings().contains(rough));
        assertTrue(lobby.sightings().contains(peer));
        assertFalse(lobby.clearRemoteSightings("skyblocker"));
    }
}
